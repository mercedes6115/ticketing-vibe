package com.ticketing.service;

import com.ticketing.config.QueueEmitterManager;
import com.ticketing.dto.queue.QueueStatusResponse;
import com.ticketing.dto.queue.QueueTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.ticketing.exception.ForbiddenException;
import com.ticketing.exception.NotFoundException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final QueueEmitterManager emitterManager;

    private static final String QUEUE_PREFIX = "queue:event:";
    private static final String TOKEN_PREFIX = "queue:token:";
    private static final String ACTIVE_EVENTS_KEY = "queue:active:events";
    private static final long TOKEN_TTL_SECONDS = 600; // 10분
    private static final int MAX_ENTER_COUNT = 100;    // 동시 입장 허용 인원

    /**
     * 대기열 진입
     */
    public QueueStatusResponse enter(Long eventId, Long userId) {
        String queueKey = QUEUE_PREFIX + eventId;
        double score = System.currentTimeMillis();

        // 이미 대기열에 있는지 확인
        // ZSCORE queue:event:2 101
        Double existingScore = redisTemplate.opsForZSet().score(queueKey, userId.toString());
        if (existingScore != null) {
            log.info("User already in queue: eventId={}, userId={}", eventId, userId);
            return getStatus(eventId, userId);
        }

        // 대기열에 추가 (ZADD)
        // ZADD queue:event:2 1770000000001 101
        redisTemplate.opsForZSet().add(queueKey, userId.toString(), score);

        // 활성 이벤트 목록에 등록
        // SADD queue:active:events 2
        redisTemplate.opsForSet().add(ACTIVE_EVENTS_KEY, eventId.toString());

        log.info("User entered queue: eventId={}, userId={}, score={}", eventId, userId, score);
        return getStatus(eventId, userId);
    }

    /**
     * 대기열 상태 조회
     */
    public QueueStatusResponse getStatus(Long eventId, Long userId) {
        String queueKey = QUEUE_PREFIX + eventId;

        Long rank = redisTemplate.opsForZSet().rank(queueKey, userId.toString());
        if (rank == null) {
            throw new NotFoundException("대기열에 등록되어 있지 않습니다");
        }
        long position = rank + 1;

        Long totalWaiting = redisTemplate.opsForZSet().size(queueKey);
        if (totalWaiting == null) totalWaiting = 0L;

        boolean canEnter = position <= MAX_ENTER_COUNT;

        return QueueStatusResponse.of(eventId, userId, position, totalWaiting, canEnter);
    }

    /**
     * 대기열 이탈
     */
    public void exit(Long eventId, Long userId) {
        String queueKey = QUEUE_PREFIX + eventId;
        redisTemplate.opsForZSet().remove(queueKey, userId.toString());

        // 입장 토큰도 삭제
        String tokenKey = TOKEN_PREFIX + userId + ":" + eventId;
        redisTemplate.delete(tokenKey);
        log.info("User exited queue: eventId={}, userId={}", eventId, userId);
    }

    /**
     * 입장 토큰 발급 (수동 요청).
     * processQueue()가 미리 발급한 토큰이 있으면 그대로 반환한다.
     */
    public QueueTokenResponse issueToken(Long eventId, Long userId) {
        String tokenKey = TOKEN_PREFIX + userId + ":" + eventId;

        // processQueue()가 이미 발급한 토큰이면 재사용
        Object existingToken = redisTemplate.opsForValue().get(tokenKey);
        if (existingToken != null) {
            Long ttl = redisTemplate.getExpire(tokenKey, TimeUnit.SECONDS);
            log.info("Existing token returned: eventId={}, userId={}", eventId, userId);
            return QueueTokenResponse.of(existingToken.toString(), eventId, userId,
                    ttl != null && ttl > 0 ? ttl : TOKEN_TTL_SECONDS);
        }

        // 대기열 순번 확인
        QueueStatusResponse status = getStatus(eventId, userId);
        if (!status.isCanEnter()) {
            throw new ForbiddenException("아직 입장 순서가 아닙니다. 현재 순번: " + status.getPosition());
        }

        // 토큰 발급
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(tokenKey, token, Duration.ofSeconds(TOKEN_TTL_SECONDS));

        // 대기열에서 제거
        redisTemplate.opsForZSet().remove(QUEUE_PREFIX + eventId, userId.toString());

        log.info("Manual token issued: eventId={}, userId={}", eventId, userId);
        return QueueTokenResponse.of(token, eventId, userId, TOKEN_TTL_SECONDS);
    }

    /**
     * 입장 토큰 검증
     */
    public boolean validateToken(Long eventId, Long userId, String token) {
        String tokenKey = TOKEN_PREFIX + userId + ":" + eventId;
        Object storedToken = redisTemplate.opsForValue().get(tokenKey);
        return storedToken != null && token.equals(storedToken.toString());
    }

    /**
     * 입장 토큰 존재 여부 확인
     */
    public boolean hasValidToken(Long eventId, Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_PREFIX + userId + ":" + eventId));
    }

    /**
     * 입장 토큰 삭제 (사용 완료)
     */
    public void invalidateToken(Long eventId, Long userId) {
        redisTemplate.delete(TOKEN_PREFIX + userId + ":" + eventId);
        log.info("Token invalidated: eventId={}, userId={}", eventId, userId);
    }

    /**
     * 대기열 전체 인원 조회
     */
    public Long getQueueSize(Long eventId) {
        Long size = redisTemplate.opsForZSet().size(QUEUE_PREFIX + eventId);
        return size != null ? size : 0L;
    }

    /**
     * 대기열 초기화 (관리자용)
     */
    public void clearQueue(Long eventId) {
        redisTemplate.delete(QUEUE_PREFIX + eventId);
        redisTemplate.opsForSet().remove(ACTIVE_EVENTS_KEY, eventId.toString());
        log.info("Queue cleared: eventId={}", eventId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // processQueue: 서버가 상위 N명에게 토큰을 자동 발급
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 특정 이벤트 대기열에서 상위 N명에게 토큰을 자동 발급한다.
     * 이미 토큰이 있는 유저(이전 실행에서 발급)는 건너뛴다.
     *
     * @return 이번 실행에서 새로 토큰을 발급받은 userId 목록
     */
    public List<Long> processQueue(Long eventId) {
        String queueKey = QUEUE_PREFIX + eventId;
        Long queueSize = redisTemplate.opsForZSet().size(queueKey);

        if (queueSize == null || queueSize == 0) {
            // 큐가 비었으면 활성 목록에서 제거
            redisTemplate.opsForSet().remove(ACTIVE_EVENTS_KEY, eventId.toString());
            return Collections.emptyList();
        }

        long limit = Math.min(queueSize, MAX_ENTER_COUNT);
        Set<Object> topUsers = redisTemplate.opsForZSet().range(queueKey, 0, limit - 1);
        if (topUsers == null || topUsers.isEmpty()) return Collections.emptyList();

        List<Long> issued = new ArrayList<>();
        for (Object userIdObj : topUsers) {
            Long userId = Long.valueOf(userIdObj.toString());
            String tokenKey = TOKEN_PREFIX + userId + ":" + eventId;

            // SET NX (원자적): 다중 인스턴스 동시 실행 시 하나의 인스턴스만 성공
            String token = UUID.randomUUID().toString();
            Boolean set = redisTemplate.opsForValue()
                    .setIfAbsent(tokenKey, token, Duration.ofSeconds(TOKEN_TTL_SECONDS));
            if (!Boolean.TRUE.equals(set)) continue;   // 이미 다른 인스턴스가 발급

            redisTemplate.opsForZSet().remove(queueKey, userIdObj);
            issued.add(userId);
            log.info("Auto token issued: eventId={}, userId={}", eventId, userId);
        }

        return issued;
    }

    /**
     * 5초마다 모든 활성 이벤트의 대기열을 처리한다.
     */
    @Scheduled(fixedDelay = 5000)
    public void processAllActiveQueues() {
        Set<Object> activeEvents = redisTemplate.opsForSet().members(ACTIVE_EVENTS_KEY);
        if (activeEvents == null || activeEvents.isEmpty()) return;

        for (Object eventIdObj : activeEvents) {
            try {
                Long eventId = Long.valueOf(eventIdObj.toString());
                List<Long> issuedUserIds = processQueue(eventId);

                // 토큰을 발급받은 유저에게 SSE 알림
                for (Long userId : issuedUserIds) {
                    emitterManager.sendTokenIssued(eventId, userId);
                }

                if (!issuedUserIds.isEmpty()) {
                    log.info("Queue processed: eventId={}, issued={}", eventId, issuedUserIds.size());
                }
            } catch (Exception e) {
                log.error("Queue processing failed for eventId={}", eventIdObj, e);
            }
        }
    }
}
