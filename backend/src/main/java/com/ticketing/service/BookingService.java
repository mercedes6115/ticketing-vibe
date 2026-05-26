package com.ticketing.service;

import com.ticketing.dto.booking.BookingAcceptedResponse;
import com.ticketing.dto.booking.BookingCreateRequest;
import com.ticketing.dto.booking.BookingListResponse;
import com.ticketing.dto.booking.BookingResponse;
import com.ticketing.dto.booking.BookingStatusResponse;
import com.ticketing.dto.kafka.BookingEvent;
import com.ticketing.dto.kafka.BookingRequestEvent;
import com.ticketing.dto.seat.SeatStatusMessage;
import com.ticketing.entity.*;
import com.ticketing.exception.ForbiddenException;
import com.ticketing.exception.NonRetryableBookingException;
import com.ticketing.exception.NotFoundException;
import com.ticketing.repository.*;
import com.ticketing.util.TransactionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingService {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final SeatRepository seatRepository;
    private final PaymentRepository paymentRepository;
    private final EventRepository eventRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final BookingEventProducer bookingEventProducer;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String SEAT_HOLD_PREFIX = "seat:hold:";
    public static final String BOOKING_STATUS_PREFIX = "booking:status:";
    public static final Duration BOOKING_STATUS_TTL = Duration.ofMinutes(10);
    private static final String BOOKING_REQUESTS_TOPIC = "booking-requests";
    private static final DateTimeFormatter BOOKING_NO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 예매 요청 수락 (비동기)
     *
     * 크리티컬 패스: Redis 검증 → Kafka 발행 → hold 키 삭제 → 202 반환
     * DB 쓰기는 BookingRequestConsumer가 비동기로 처리
     *
     * hold 키 삭제를 Kafka 발행 성공 이후로 미룬다:
     * 발행 전 삭제 후 크래시 시 seat:hold 키가 사라져 만료 리스너도 동작 불가 → 좌석 영구 HOLD 잔류 방지
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingAcceptedResponse createBooking(BookingCreateRequest request, Long userId) {
        long startedAtNanos = System.nanoTime();
        // 1. Redis 좌석 홀드 검증 (DB 없이 Redis만으로 빠르게 처리)
        String holdKey = SEAT_HOLD_PREFIX + request.getSeatId();
        Object holdUserId = redisTemplate.opsForValue().get(holdKey);
        if (holdUserId == null || !userId.equals(Long.valueOf(holdUserId.toString()))) {
            throw new ForbiddenException("본인이 선점한 좌석만 예매할 수 있습니다. 먼저 좌석을 선점하세요.");
        }

        // 2. bookingNo 생성
        String bookingNo = generateBookingNo();
        LocalDateTime requestedAt = LocalDateTime.now();

        // 3. Redis에 처리 상태 기록 (TTL 10분 — Consumer 처리 완료까지 유지)
        redisTemplate.opsForValue().set(BOOKING_STATUS_PREFIX + bookingNo, "PROCESSING", BOOKING_STATUS_TTL);

        // 4. Kafka booking-requests 발행 (key=userId → 같은 사용자 요청은 같은 파티션)
        BookingRequestEvent event = new BookingRequestEvent(
                bookingNo, userId, request.getSeatId(),
                request.getPaymentMethod(), requestedAt
        );
        try {
            // 브로커 ack를 동기 대기 — 발행 성공 확인 후에만 hold 키 삭제
            // fire-and-forget 시: 브로커 거부를 async로 수신하더라도 hold 키는 이미 삭제됨
            //   → 만료 리스너도 동작 불가 → 좌석 영구 HOLD 잔류 위험
            kafkaTemplate.send(BOOKING_REQUESTS_TOPIC, String.valueOf(userId), event)
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            redisTemplate.delete(BOOKING_STATUS_PREFIX + bookingNo);
            throw new IllegalStateException("예매 요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.", e);
        } catch (ExecutionException | TimeoutException e) {
            // 발행 실패: 상태 키 정리하고 hold 키는 보존 → 사용자 재시도 가능
            redisTemplate.delete(BOOKING_STATUS_PREFIX + bookingNo);
            throw new IllegalStateException("예매 요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.", e);
        }

        // 5. Kafka 발행 성공 후 hold 키 삭제 (자동 해제 방지)
        redisTemplate.delete(holdKey);

        long acceptedLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        log.info(
                "Booking request accepted: bookingNo={}, userId={}, seatId={}, acceptedLatencyMs={}, requestedAt={}",
                bookingNo,
                userId,
                request.getSeatId(),
                acceptedLatencyMs,
                requestedAt.truncatedTo(ChronoUnit.MILLIS)
        );

        return new BookingAcceptedResponse(bookingNo, "PROCESSING");
    }

    /**
     * 예매 요청 DB 영속화 (BookingRequestConsumer에서 호출)
     *
     * 아이디엠포턴트: bookingNo가 이미 DB에 존재하면 재처리 건너뜀
     * 반환값: 다운스트림 발행용 BookingEvent (모든 lazy 로딩을 트랜잭션 안에서 처리)
     */
    @Transactional
    public BookingEvent persistBookingRequest(BookingRequestEvent requestEvent) {
        // idempotency 체크: 동일 bookingNo가 이미 DB에 있으면 그대로 반환
        Optional<Booking> existing = bookingRepository.findByBookingNo(requestEvent.getBookingNo());
        if (existing.isPresent()) {
            log.info("[Consumer] 이미 처리된 예매 (idempotent skip): bookingNo={}",
                    requestEvent.getBookingNo());
            return toBookingEvent(existing.get(), "CONFIRMED");
        }

        // 엔티티 로드 (PESSIMISTIC_WRITE: 동일 좌석 중복 처리 방지)
        User user = userRepository.findById(requestEvent.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found: " + requestEvent.getUserId()));
        Seat seat = seatRepository.findByIdWithEventForUpdate(requestEvent.getSeatId())
                .orElseThrow(() -> new IllegalStateException("Seat not found: " + requestEvent.getSeatId()));

        // DB 좌석 상태 확인 (HOLD여야 함)
        if (!seat.isHold()) {
            throw new NonRetryableBookingException(
                    "Seat is not in HOLD state: seatId=" + requestEvent.getSeatId());
        }

        Event event = seat.getEvent();

        // 좌석 → SOLD
        // availableSeats 카운터는 이벤트 row 핫스팟이 되어 persist 경로를 느리게 만들 수 있어
        // 예매 확정 크리티컬 패스에서는 즉시 갱신하지 않는다.
        seat.sell();

        // 예매 생성
        Booking booking = Booking.builder()
                .user(user).event(event).seat(seat)
                .bookingNo(requestEvent.getBookingNo())
                .build();
        booking.confirm();
        bookingRepository.save(booking);

        // 결제 생성 (Mock: 바로 SUCCESS)
        Payment payment = Payment.builder()
                .booking(booking)
                .amount(seat.getPrice())
                .method(requestEvent.getPaymentMethod())
                .idempotencyKey(UUID.randomUUID().toString())
                .build();
        payment.success();
        paymentRepository.save(payment);

        log.info("[Consumer] Booking persisted: bookingNo={}, userId={}, seatId={}",
                requestEvent.getBookingNo(), user.getId(), seat.getId());

        // 트랜잭션 안에서 모든 lazy 관계 접근 → 순수 데이터 객체로 반환
        return toBookingEvent(booking, "CONFIRMED");
    }

    private BookingEvent toBookingEvent(Booking booking, String status) {
        return new BookingEvent(
                booking.getId(), booking.getBookingNo(),
                booking.getUser().getId(), booking.getUser().getNickname(),
                booking.getEvent().getId(), booking.getEvent().getTitle(), status,
                (long) booking.getSeat().getPrice(), LocalDateTime.now()
        );
    }

    /**
     * 예매 처리 상태 조회 (프론트엔드 폴링용)
     */
    public BookingStatusResponse getBookingStatus(String bookingNo) {
        String statusKey = BOOKING_STATUS_PREFIX + bookingNo;
        Object status = redisTemplate.opsForValue().get(statusKey);
        if (status == null) {
            // Redis 키 만료 — DB 직접 확인
            boolean exists = bookingRepository.findByBookingNo(bookingNo).isPresent();
            return new BookingStatusResponse(bookingNo, exists ? "CONFIRMED" : "UNKNOWN");
        }
        // Redis가 PROCESSING이더라도 DB 확인
        // Consumer가 DB 커밋 후 Redis SET 실패 시 stale PROCESSING 키가 잔류할 수 있음
        if ("PROCESSING".equals(status.toString())) {
            boolean exists = bookingRepository.findByBookingNo(bookingNo).isPresent();
            if (exists) {
                return new BookingStatusResponse(bookingNo, "CONFIRMED");
            }
        }
        return new BookingStatusResponse(bookingNo, status.toString());
    }

    /**
     * 예매 상세 조회 (소유권 검사)
     */
    public BookingResponse getBooking(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithEventAndSeat(bookingId)
                .orElseThrow(() -> NotFoundException.booking(bookingId));
        if (!booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인의 예매만 조회할 수 있습니다.");
        }
        return BookingResponse.from(booking);
    }

    /**
     * 예매 번호로 조회 (소유권 검사)
     */
    public BookingResponse getBookingByNo(String bookingNo, Long userId) {
        Booking booking = bookingRepository.findByBookingNo(bookingNo)
                .orElseThrow(() -> NotFoundException.bookingByNo(bookingNo));
        if (!booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인의 예매만 조회할 수 있습니다.");
        }
        return BookingResponse.from(booking);
    }

    /**
     * 사용자별 예매 목록 조회
     */
    public Page<BookingListResponse> getBookingsByUserId(Long userId, Pageable pageable) {
        return bookingRepository.findByUserId(userId, pageable)
                .map(BookingListResponse::from);
    }

    /**
     * 전체 예매 목록 조회 (관리자용) — eventId 지정 시 해당 이벤트 예매만 반환
     */
    public Page<BookingListResponse> getAllBookings(Long eventId, Pageable pageable) {
        if (eventId != null) {
            return bookingRepository.findByEventId(eventId, pageable)
                    .map(BookingListResponse::fromAdmin);
        }
        return bookingRepository.findAll(pageable)
                .map(BookingListResponse::fromAdmin);
    }

    /**
     * 예매 취소 (동기)
     * 취소는 경합 없음 — 비동기화 불필요
     */
    @Transactional
    public BookingResponse cancelBooking(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithEventAndSeat(bookingId)
                .orElseThrow(() -> NotFoundException.booking(bookingId));

        if (!booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인의 예매만 취소할 수 있습니다.");
        }

        if (booking.isCancelled()) {
            throw new IllegalStateException("이미 취소된 예매입니다.");
        }

        booking.cancel();

        if (booking.getPayment() != null && booking.getPayment().isSuccess()) {
            booking.getPayment().refund();
        }

        Seat seat = booking.getSeat();
        seat.release();
        seatRepository.save(seat);

        log.info("Booking cancelled: bookingNo={}, userId={}", booking.getBookingNo(), userId);

        // 트랜잭션 커밋 후 WebSocket 브로드캐스트 + Kafka 발행
        // Kafka를 커밋 전에 발행하면 롤백 시 CANCELLED 이벤트가 이미 발행된 상태가 됨
        long seatId = seat.getId();
        long eventId = booking.getEvent().getId();
        BookingEvent cancelEvent = new BookingEvent(
                booking.getId(), booking.getBookingNo(),
                booking.getUser().getId(), booking.getUser().getNickname(),
                booking.getEvent().getId(), booking.getEvent().getTitle(), "CANCELLED",
                (long) booking.getSeat().getPrice(), LocalDateTime.now()
        );
        TransactionUtils.afterCommit(() -> {
            messagingTemplate.convertAndSend(
                    "/topic/events/" + eventId + "/seats",
                    SeatStatusMessage.release(seatId, eventId)
            );
            bookingEventProducer.send(cancelEvent);
        });

        return BookingResponse.from(booking);
    }

    private String generateBookingNo() {
        String dateStr = LocalDate.now().format(BOOKING_NO_DATE_FORMAT);
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return "BK" + dateStr + uid;
    }
}
