package com.ticketing.service;

import com.ticketing.config.QueueEmitterManager;
import com.ticketing.dto.queue.QueueStatusResponse;
import com.ticketing.dto.queue.QueueTokenResponse;
import com.ticketing.exception.ForbiddenException;
import com.ticketing.exception.NotFoundException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final QueueEmitterManager emitterManager;
    private final MeterRegistry meterRegistry;

    private static final String QUEUE_PREFIX = "queue:event:";
    private static final String TOKEN_PREFIX = "queue:token:";
    private static final String ACTIVE_EVENTS_KEY = "queue:active:events";
    private static final long TOKEN_TTL_SECONDS = 600;
    private static final int MAX_ENTER_COUNT = 100;

    private final AtomicInteger activeQueueEventCount = new AtomicInteger();
    private MultiGauge queueSizeGauge;
    private MultiGauge issuedTokenGauge;

    @PostConstruct
    void registerQueueMetrics() {
        queueSizeGauge = MultiGauge.builder("ticketing.queue.size")
                .description("Current waiting users per event queue")
                .register(meterRegistry);
        issuedTokenGauge = MultiGauge.builder("ticketing.queue.issued.tokens")
                .description("Currently issued queue tokens per event")
                .register(meterRegistry);
        Gauge.builder("ticketing.queue.active.events", activeQueueEventCount, AtomicInteger::get)
                .description("Number of active events with queues")
                .register(meterRegistry);
        refreshQueueMetrics();
    }

    public QueueStatusResponse enter(Long eventId, Long userId) {
        String queueKey = QUEUE_PREFIX + eventId;
        double score = System.currentTimeMillis();

        Double existingScore = redisTemplate.opsForZSet().score(queueKey, userId.toString());
        if (existingScore != null) {
            log.info("User already in queue: eventId={}, userId={}", eventId, userId);
            return getStatus(eventId, userId);
        }

        redisTemplate.opsForZSet().add(queueKey, userId.toString(), score);
        redisTemplate.opsForSet().add(ACTIVE_EVENTS_KEY, eventId.toString());

        refreshQueueMetrics();
        log.info("User entered queue: eventId={}, userId={}, score={}", eventId, userId, score);
        return getStatus(eventId, userId);
    }

    public QueueStatusResponse getStatus(Long eventId, Long userId) {
        String queueKey = QUEUE_PREFIX + eventId;

        Long rank = redisTemplate.opsForZSet().rank(queueKey, userId.toString());
        if (rank == null) {
            throw new NotFoundException("대기열에 등록되어 있지 않습니다");
        }
        long position = rank + 1;

        Long totalWaiting = redisTemplate.opsForZSet().size(queueKey);
        if (totalWaiting == null) {
            totalWaiting = 0L;
        }

        boolean canEnter = position <= MAX_ENTER_COUNT;
        return QueueStatusResponse.of(eventId, userId, position, totalWaiting, canEnter);
    }

    public void exit(Long eventId, Long userId) {
        String queueKey = QUEUE_PREFIX + eventId;
        redisTemplate.opsForZSet().remove(queueKey, userId.toString());
        redisTemplate.delete(TOKEN_PREFIX + userId + ":" + eventId);

        refreshQueueMetrics();
        log.info("User exited queue: eventId={}, userId={}", eventId, userId);
    }

    public QueueTokenResponse issueToken(Long eventId, Long userId) {
        String tokenKey = TOKEN_PREFIX + userId + ":" + eventId;

        Object existingToken = redisTemplate.opsForValue().get(tokenKey);
        if (existingToken != null) {
            Long ttl = redisTemplate.getExpire(tokenKey, TimeUnit.SECONDS);
            log.info("Existing token returned: eventId={}, userId={}", eventId, userId);
            return QueueTokenResponse.of(
                    existingToken.toString(),
                    eventId,
                    userId,
                    ttl != null && ttl > 0 ? ttl : TOKEN_TTL_SECONDS
            );
        }

        QueueStatusResponse status = getStatus(eventId, userId);
        if (!status.isCanEnter()) {
            throw new ForbiddenException("아직 입장 순서가 아닙니다. 현재 순번: " + status.getPosition());
        }

        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(tokenKey, token, Duration.ofSeconds(TOKEN_TTL_SECONDS));
        redisTemplate.opsForZSet().remove(QUEUE_PREFIX + eventId, userId.toString());

        refreshQueueMetrics();
        log.info("Manual token issued: eventId={}, userId={}", eventId, userId);
        return QueueTokenResponse.of(token, eventId, userId, TOKEN_TTL_SECONDS);
    }

    public boolean validateToken(Long eventId, Long userId, String token) {
        String tokenKey = TOKEN_PREFIX + userId + ":" + eventId;
        Object storedToken = redisTemplate.opsForValue().get(tokenKey);
        return storedToken != null && token.equals(storedToken.toString());
    }

    public boolean hasValidToken(Long eventId, Long userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_PREFIX + userId + ":" + eventId));
    }

    public void invalidateToken(Long eventId, Long userId) {
        redisTemplate.delete(TOKEN_PREFIX + userId + ":" + eventId);
        refreshQueueMetrics();
        log.info("Token invalidated: eventId={}, userId={}", eventId, userId);
    }

    public Long getQueueSize(Long eventId) {
        Long size = redisTemplate.opsForZSet().size(QUEUE_PREFIX + eventId);
        return size != null ? size : 0L;
    }

    public void clearQueue(Long eventId) {
        redisTemplate.delete(QUEUE_PREFIX + eventId);
        redisTemplate.opsForSet().remove(ACTIVE_EVENTS_KEY, eventId.toString());
        refreshQueueMetrics();
        log.info("Queue cleared: eventId={}", eventId);
    }

    public List<Long> processQueue(Long eventId) {
        String queueKey = QUEUE_PREFIX + eventId;
        Long queueSize = redisTemplate.opsForZSet().size(queueKey);

        if (queueSize == null || queueSize == 0) {
            redisTemplate.opsForSet().remove(ACTIVE_EVENTS_KEY, eventId.toString());
            return Collections.emptyList();
        }

        long limit = Math.min(queueSize, MAX_ENTER_COUNT);
        Set<Object> topUsers = redisTemplate.opsForZSet().range(queueKey, 0, limit - 1);
        if (topUsers == null || topUsers.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> issued = new ArrayList<>();
        for (Object userIdObj : topUsers) {
            Long userId = Long.valueOf(userIdObj.toString());
            String tokenKey = TOKEN_PREFIX + userId + ":" + eventId;

            String token = UUID.randomUUID().toString();
            Boolean set = redisTemplate.opsForValue()
                    .setIfAbsent(tokenKey, token, Duration.ofSeconds(TOKEN_TTL_SECONDS));
            if (!Boolean.TRUE.equals(set)) {
                continue;
            }

            redisTemplate.opsForZSet().remove(queueKey, userIdObj);
            issued.add(userId);
            log.info("Auto token issued: eventId={}, userId={}", eventId, userId);
        }

        return issued;
    }

    @Scheduled(fixedDelay = 5000)
    public void processAllActiveQueues() {
        Set<Object> activeEvents = redisTemplate.opsForSet().members(ACTIVE_EVENTS_KEY);
        if (activeEvents == null || activeEvents.isEmpty()) {
            refreshQueueMetrics();
            return;
        }

        for (Object eventIdObj : activeEvents) {
            try {
                Long eventId = Long.valueOf(eventIdObj.toString());
                List<Long> issuedUserIds = processQueue(eventId);

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

        refreshQueueMetrics();
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 1000)
    public void refreshQueueMetrics() {
        Set<Object> activeEvents = redisTemplate.opsForSet().members(ACTIVE_EVENTS_KEY);
        if (activeEvents == null || activeEvents.isEmpty()) {
            activeQueueEventCount.set(0);
            queueSizeGauge.register(Collections.emptyList(), true);
            issuedTokenGauge.register(Collections.emptyList(), true);
            return;
        }

        activeQueueEventCount.set(activeEvents.size());

        List<MultiGauge.Row<?>> queueRows = new ArrayList<>();
        List<MultiGauge.Row<?>> tokenRows = new ArrayList<>();
        for (Object eventIdObj : activeEvents) {
            String eventId = eventIdObj.toString();
            queueRows.add(MultiGauge.Row.of(
                    Tags.of("event_id", eventId),
                    getQueueSize(Long.valueOf(eventId))
            ));
            tokenRows.add(MultiGauge.Row.of(
                    Tags.of("event_id", eventId),
                    getIssuedTokenCount(eventId)
            ));
        }

        queueSizeGauge.register(queueRows, true);
        issuedTokenGauge.register(tokenRows, true);
    }

    private long getIssuedTokenCount(String eventId) {
        Set<String> tokenKeys = redisTemplate.keys(TOKEN_PREFIX + "*:" + eventId);
        return tokenKeys != null ? tokenKeys.size() : 0L;
    }
}
