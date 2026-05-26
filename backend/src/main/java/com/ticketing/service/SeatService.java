package com.ticketing.service;

import com.ticketing.dto.seat.*;
import com.ticketing.entity.Event;
import com.ticketing.entity.Seat;
import com.ticketing.entity.enums.SeatStatus;
import com.ticketing.exception.ForbiddenException;
import com.ticketing.exception.NotFoundException;
import com.ticketing.repository.EventRepository;
import com.ticketing.repository.SeatRepository;
import com.ticketing.util.TransactionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatService {

    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;
    private final RedissonClient redissonClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String SEAT_LOCK_PREFIX = "seat:lock:";
    private static final String SEAT_HOLD_PREFIX = "seat:hold:";
    private static final String TOKEN_PREFIX = "queue:token:";
    private static final long HOLD_TTL_SECONDS = 300; // 5분
    private static final long LOCK_WAIT_TIME = 5;     // 락 대기 시간 (초)
    private static final long LOCK_LEASE_TIME = 10;   // 락 유지 시간 (초)

    public List<SeatResponse> getSeatsByEventId(Long eventId) {
        List<Seat> seats = seatRepository.findByEventId(eventId);
        return seats.stream()
                .map(SeatResponse::from)
                .toList();
    }

    public SeatResponse getSeatById(Long seatId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> NotFoundException.seat(seatId));
        return SeatResponse.from(seat);
    }

    @Transactional
    public List<SeatResponse> createSeats(Long eventId, SeatBulkCreateRequest request) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> NotFoundException.event(eventId));

        List<Seat> seats = new ArrayList<>();
        for (int row = 1; row <= request.getRowCount(); row++) {
            for (int num = 1; num <= request.getSeatsPerRow(); num++) {
                Seat seat = Seat.builder()
                        .event(event)
                        .section(request.getSection())
                        .seatRow(String.valueOf(row))
                        .seatNumber(num)
                        .price(request.getPrice())
                        .build();
                seats.add(seat);
            }
        }

        List<Seat> savedSeats = seatRepository.saveAll(seats);
        log.info("Seats created: eventId={}, count={}", eventId, savedSeats.size());

        return savedSeats.stream()
                .map(SeatResponse::from)
                .toList();
    }

    /**
     * 좌석 임시 선점 (Redis 분산락 사용)
     *
     * @Transactional 보장: seatRepository.save() 커밋 전 redisTemplate.set() 실패 시
     * 트랜잭션 롤백으로 DB 상태도 AVAILABLE로 복원된다.
     */
    @Transactional
    public SeatResponse holdSeat(Long seatId, Long userId) {
        // JOIN FETCH로 event 함께 로드 (token 검증 시 N+1 방지)
        Seat seat = seatRepository.findByIdWithEvent(seatId)
                .orElseThrow(() -> NotFoundException.seat(seatId));

        // 입장 토큰 검증 (대기열 우회 차단)
        String tokenKey = TOKEN_PREFIX + userId + ":" + seat.getEvent().getId();
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(tokenKey))) {
            throw new ForbiddenException("유효한 입장 토큰이 없습니다. 대기열에서 토큰을 발급받으세요.");
        }

        String lockKey = SEAT_LOCK_PREFIX + seatId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 분산락 획득 시도
            boolean acquired = lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);

            if (!acquired) {
                throw new IllegalStateException("다른 사용자가 해당 좌석을 선점 중입니다");
            }

            // PESSIMISTIC_WRITE로 재조회: DB 행 잠금이 트랜잭션 커밋까지 유지됨
            // Redisson 락은 finally에서 메서드 반환 전에 해제되지만, Spring 프록시는
            // 메서드 반환 후에 커밋 — 락 해제~커밋 사이 창에서 다른 스레드가
            // AVAILABLE 상태를 읽는 이중 선점 방지
            seat = seatRepository.findByIdWithEventForUpdate(seatId)
                    .orElseThrow(() -> NotFoundException.seat(seatId));

            if (!seat.isAvailable()) {
                throw new IllegalStateException("이미 선점되었거나 판매된 좌석입니다");
            }

            // DB 상태 변경
            seat.hold();
            seatRepository.save(seat);

            // Redis에 선점 정보 저장 (TTL 5분)
            String holdKey = SEAT_HOLD_PREFIX + seatId;
            redisTemplate.opsForValue().set(holdKey, userId, Duration.ofSeconds(HOLD_TTL_SECONDS));

            log.info("Seat held: seatId={}, userId={}, ttl={}s", seatId, userId, HOLD_TTL_SECONDS);

            // WebSocket 브로드캐스트는 트랜잭션 커밋 이후로 지연
            // 커밋 전 전송 시 롤백되더라도 클라이언트는 HOLD 상태를 수신 → 유령 HOLD 방지
            SeatStatusMessage statusMessage = SeatStatusMessage.hold(seatId, seat.getEvent().getId(), userId);
            TransactionUtils.afterCommit(() -> broadcastSeatStatus(statusMessage));

            return SeatResponse.fromHold(seat, HOLD_TTL_SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("좌석 선점 중 오류가 발생했습니다");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 좌석 선점 해제
     */
    @Transactional
    public SeatResponse releaseSeat(Long seatId, Long userId) {
        String holdKey = SEAT_HOLD_PREFIX + seatId;
        Object holdUserId = redisTemplate.opsForValue().get(holdKey);

        // 선점한 사용자 확인
        if (holdUserId == null) {
            throw new IllegalStateException("선점된 좌석이 아닙니다");
        }
        if (!userId.equals(Long.valueOf(holdUserId.toString()))) {
            throw new ForbiddenException("본인이 선점한 좌석만 해제할 수 있습니다");
        }

        Seat seat = seatRepository.findByIdWithEvent(seatId)
                .orElseThrow(() -> NotFoundException.seat(seatId));

        if (!seat.isHold()) {
            throw new IllegalStateException("선점 상태가 아닌 좌석입니다");
        }

        // DB 상태 변경
        seat.release();
        seatRepository.save(seat);

        log.info("Seat released: seatId={}, userId={}", seatId, userId);

        // Redis 삭제 + WebSocket 브로드캐스트 모두 커밋 후 실행
        // 커밋 전 hold 키 삭제 시 DB 롤백이 발생하면: 키는 없고 DB는 HOLD 상태 유지
        // → 만료 리스너도 동작 불가 → 좌석 영구 HOLD 잠김
        SeatStatusMessage statusMessage = SeatStatusMessage.release(seatId, seat.getEvent().getId());
        TransactionUtils.afterCommit(() -> {
            redisTemplate.delete(holdKey);
            broadcastSeatStatus(statusMessage);
        });

        return SeatResponse.from(seat);
    }

    /**
     * 좌석 선점 사용자 조회
     */
    public Long getHoldUserId(Long seatId) {
        String holdKey = SEAT_HOLD_PREFIX + seatId;
        Object userId = redisTemplate.opsForValue().get(holdKey);
        return userId != null ? Long.valueOf(userId.toString()) : null;
    }

    /**
     * 좌석 선점 여부 확인
     */
    public boolean isHeldByUser(Long seatId, Long userId) {
        Long holdUserId = getHoldUserId(seatId);
        return userId.equals(holdUserId);
    }

    /**
     * WebSocket으로 좌석 상태 변경 브로드캐스트
     */
    private void broadcastSeatStatus(SeatStatusMessage message) {
        String destination = "/topic/events/" + message.getEventId() + "/seats";
        messagingTemplate.convertAndSend(destination, message);
        log.debug("Broadcast seat status: destination={}, message={}", destination, message);
    }
}
