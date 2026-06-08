package com.ticketing.service;

import com.ticketing.dto.booking.BookingAcceptedResponse;
import com.ticketing.dto.booking.BookingCreateRequest;
import com.ticketing.dto.booking.BookingListResponse;
import com.ticketing.dto.booking.BookingResponse;
import com.ticketing.dto.booking.BookingStatusResponse;
import com.ticketing.dto.kafka.BookingEvent;
import com.ticketing.dto.kafka.BookingRequestEvent;
import com.ticketing.entity.Booking;
import com.ticketing.entity.Event;
import com.ticketing.entity.Payment;
import com.ticketing.entity.Seat;
import com.ticketing.entity.User;
import com.ticketing.entity.enums.BookingProcessStatus;
import com.ticketing.exception.ForbiddenException;
import com.ticketing.exception.NonRetryableBookingException;
import com.ticketing.exception.NotFoundException;
import com.ticketing.repository.BookingRepository;
import com.ticketing.repository.PaymentRepository;
import com.ticketing.repository.SeatRepository;
import com.ticketing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
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
    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String SEAT_HOLD_PREFIX = "seat:hold:";
    public static final String BOOKING_STATUS_PREFIX = "booking:status:";
    public static final Duration BOOKING_STATUS_TTL = Duration.ofMinutes(10);
    private static final String BOOKING_REQUESTS_TOPIC = "booking-requests";
    private static final DateTimeFormatter BOOKING_NO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookingAcceptedResponse createBooking(BookingCreateRequest request, Long userId) {
        long startedAtNanos = System.nanoTime();
        String holdKey = SEAT_HOLD_PREFIX + request.getSeatId();
        Object holdUserId = redisTemplate.opsForValue().get(holdKey);
        if (holdUserId == null || !userId.equals(Long.valueOf(holdUserId.toString()))) {
            throw new ForbiddenException("본인이 점유한 좌석만 예매할 수 있습니다. 먼저 좌석을 점유해 주세요.");
        }

        String bookingNo = generateBookingNo();
        LocalDateTime requestedAt = LocalDateTime.now();

        redisTemplate.opsForValue().set(
                BOOKING_STATUS_PREFIX + bookingNo,
                BookingProcessStatus.PROCESSING,
                BOOKING_STATUS_TTL
        );

        BookingRequestEvent event = new BookingRequestEvent(
                bookingNo, userId, request.getSeatId(),
                request.getPaymentMethod(), requestedAt
        );
        try {
            kafkaTemplate.send(BOOKING_REQUESTS_TOPIC, String.valueOf(userId), event)
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            redisTemplate.delete(BOOKING_STATUS_PREFIX + bookingNo);
            throw new IllegalStateException("예매 요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", e);
        } catch (ExecutionException | TimeoutException e) {
            redisTemplate.delete(BOOKING_STATUS_PREFIX + bookingNo);
            throw new IllegalStateException("예매 요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", e);
        }

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

        return new BookingAcceptedResponse(bookingNo, BookingProcessStatus.PROCESSING);
    }

    @Transactional
    public BookingEvent persistBookingRequest(BookingRequestEvent requestEvent) {
        Optional<Booking> existing = bookingRepository.findByBookingNo(requestEvent.getBookingNo());
        if (existing.isPresent()) {
            log.info("[Consumer] already processed bookingNo={}", requestEvent.getBookingNo());
            return toBookingEvent(existing.get(), BookingProcessStatus.CONFIRMED);
        }

        User user = userRepository.findById(requestEvent.getUserId())
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다. id=" + requestEvent.getUserId()));
        Seat seat = seatRepository.findByIdWithEventForUpdate(requestEvent.getSeatId())
                .orElseThrow(() -> new IllegalStateException("좌석을 찾을 수 없습니다. id=" + requestEvent.getSeatId()));

        if (!seat.isHold()) {
            throw new NonRetryableBookingException("좌석이 점유 상태가 아닙니다. seatId=" + requestEvent.getSeatId());
        }

        Event event = seat.getEvent();
        seat.sell();

        Booking booking = Booking.builder()
                .user(user).event(event).seat(seat)
                .bookingNo(requestEvent.getBookingNo())
                .build();
        booking.confirm();
        bookingRepository.save(booking);

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

        return toBookingEvent(booking, BookingProcessStatus.CONFIRMED);
    }

    private BookingEvent toBookingEvent(Booking booking, BookingProcessStatus status) {
        return new BookingEvent(
                booking.getId(), booking.getBookingNo(),
                booking.getUser().getId(), booking.getUser().getNickname(),
                booking.getEvent().getId(), booking.getEvent().getTitle(), status,
                (long) booking.getSeat().getPrice(), LocalDateTime.now()
        );
    }

    public BookingStatusResponse getBookingStatus(String bookingNo) {
        String statusKey = BOOKING_STATUS_PREFIX + bookingNo;
        Object rawStatus = redisTemplate.opsForValue().get(statusKey);
        BookingProcessStatus status = toBookingProcessStatus(rawStatus);

        if (status == null) {
            boolean exists = bookingRepository.findByBookingNo(bookingNo).isPresent();
            return new BookingStatusResponse(
                    bookingNo,
                    exists ? BookingProcessStatus.CONFIRMED : BookingProcessStatus.UNKNOWN
            );
        }

        if (status == BookingProcessStatus.PROCESSING) {
            boolean exists = bookingRepository.findByBookingNo(bookingNo).isPresent();
            if (exists) {
                return new BookingStatusResponse(bookingNo, BookingProcessStatus.CONFIRMED);
            }
        }
        return new BookingStatusResponse(bookingNo, status);
    }

    public BookingResponse getBooking(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithEventAndSeat(bookingId)
                .orElseThrow(() -> NotFoundException.booking(bookingId));
        if (!booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인 예매만 조회할 수 있습니다.");
        }
        return BookingResponse.from(booking);
    }

    public BookingResponse getBookingByNo(String bookingNo, Long userId) {
        Booking booking = bookingRepository.findByBookingNo(bookingNo)
                .orElseThrow(() -> NotFoundException.bookingByNo(bookingNo));
        if (!booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인 예매만 조회할 수 있습니다.");
        }
        return BookingResponse.from(booking);
    }

    public Page<BookingListResponse> getBookingsByUserId(Long userId, Pageable pageable) {
        return bookingRepository.findByUserId(userId, pageable)
                .map(BookingListResponse::from);
    }

    public Page<BookingListResponse> getAllBookings(Long eventId, Pageable pageable) {
        if (eventId != null) {
            return bookingRepository.findByEventId(eventId, pageable)
                    .map(BookingListResponse::fromAdmin);
        }
        return bookingRepository.findAll(pageable)
                .map(BookingListResponse::fromAdmin);
    }

    private BookingProcessStatus toBookingProcessStatus(Object rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        if (rawStatus instanceof BookingProcessStatus bookingProcessStatus) {
            return bookingProcessStatus;
        }
        return BookingProcessStatus.valueOf(rawStatus.toString());
    }

    private String generateBookingNo() {
        String dateStr = LocalDate.now().format(BOOKING_NO_DATE_FORMAT);
        String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return "BK" + dateStr + uid;
    }
}
