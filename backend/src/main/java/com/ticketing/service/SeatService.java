package com.ticketing.service;

import com.ticketing.dto.seat.SeatBulkCreateRequest;
import com.ticketing.dto.seat.SeatResponse;
import com.ticketing.dto.seat.SeatStatusMessage;
import com.ticketing.entity.Event;
import com.ticketing.entity.Seat;
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
    private static final long HOLD_TTL_SECONDS = 300;
    private static final long LOCK_WAIT_TIME = 5;
    private static final long LOCK_LEASE_TIME = 10;

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

    @Transactional
    public SeatResponse holdSeat(Long seatId, Long userId) {
        Seat seat = seatRepository.findByIdWithEvent(seatId)
                .orElseThrow(() -> NotFoundException.seat(seatId));

        String tokenKey = TOKEN_PREFIX + userId + ":" + seat.getEvent().getId();
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(tokenKey))) {
            throw new ForbiddenException("유효한 입장 토큰이 없습니다. 대기열에서 토큰을 발급받아 주세요.");
        }

        String lockKey = SEAT_LOCK_PREFIX + seatId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);

            if (!acquired) {
                throw new IllegalStateException("다른 사용자가 해당 좌석을 점유 중입니다.");
            }

            seat = seatRepository.findByIdWithEventForUpdate(seatId)
                    .orElseThrow(() -> NotFoundException.seat(seatId));

            if (!seat.isAvailable()) {
                throw new IllegalStateException("이미 점유되었거나 판매된 좌석입니다.");
            }

            seat.hold();
            seatRepository.save(seat);

            String holdKey = SEAT_HOLD_PREFIX + seatId;
            redisTemplate.opsForValue().set(holdKey, userId, Duration.ofSeconds(HOLD_TTL_SECONDS));

            log.info("Seat held: seatId={}, userId={}, ttl={}s", seatId, userId, HOLD_TTL_SECONDS);

            SeatStatusMessage statusMessage = SeatStatusMessage.hold(seatId, seat.getEvent().getId(), userId);
            TransactionUtils.afterCommit(() -> broadcastSeatStatus(statusMessage));

            return SeatResponse.fromHold(seat, HOLD_TTL_SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("좌석 점유 중 오류가 발생했습니다.", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Transactional
    public SeatResponse releaseSeat(Long seatId, Long userId) {
        String holdKey = SEAT_HOLD_PREFIX + seatId;
        Object holdUserId = redisTemplate.opsForValue().get(holdKey);

        if (holdUserId == null) {
            throw new IllegalStateException("점유 중인 좌석이 아닙니다.");
        }
        if (!userId.equals(Long.valueOf(holdUserId.toString()))) {
            throw new ForbiddenException("본인이 점유한 좌석만 해제할 수 있습니다.");
        }

        Seat seat = seatRepository.findByIdWithEvent(seatId)
                .orElseThrow(() -> NotFoundException.seat(seatId));

        if (!seat.isHold()) {
            throw new IllegalStateException("점유 상태의 좌석만 해제할 수 있습니다.");
        }

        seat.release();
        seatRepository.save(seat);

        log.info("Seat released: seatId={}, userId={}", seatId, userId);

        SeatStatusMessage statusMessage = SeatStatusMessage.release(seatId, seat.getEvent().getId());
        TransactionUtils.afterCommit(() -> {
            redisTemplate.delete(holdKey);
            broadcastSeatStatus(statusMessage);
        });

        return SeatResponse.from(seat);
    }

    public Long getHoldUserId(Long seatId) {
        String holdKey = SEAT_HOLD_PREFIX + seatId;
        Object userId = redisTemplate.opsForValue().get(holdKey);
        return userId != null ? Long.valueOf(userId.toString()) : null;
    }

    public boolean isHeldByUser(Long seatId, Long userId) {
        Long holdUserId = getHoldUserId(seatId);
        return userId.equals(holdUserId);
    }

    private void broadcastSeatStatus(SeatStatusMessage message) {
        String destination = "/topic/events/" + message.getEventId() + "/seats";
        messagingTemplate.convertAndSend(destination, message);
        log.debug("Broadcast seat status: destination={}, message={}", destination, message);
    }
}
