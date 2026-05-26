package com.ticketing.controller;

import com.ticketing.config.QueueEmitterManager;
import com.ticketing.dto.queue.QueueEnterRequest;
import com.ticketing.dto.queue.QueueStatusResponse;
import com.ticketing.dto.queue.QueueTokenResponse;
import com.ticketing.service.QueueService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;
    private final QueueEmitterManager emitterManager;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(50);

    @PreDestroy
    public void shutdownScheduler() {
        scheduler.shutdown();
        log.info("QueueController scheduler shut down");
    }

    /**
     * 대기열 진입 (userId는 JWT에서 추출 — IDOR 방지)
     */
    @PostMapping("/enter")
    public ResponseEntity<QueueStatusResponse> enter(
            @Valid @RequestBody QueueEnterRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        QueueStatusResponse response = queueService.enter(request.getEventId(), userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 대기열 상태 조회 (userId는 JWT에서 추출 — IDOR 방지)
     */
    @GetMapping("/status")
    public ResponseEntity<QueueStatusResponse> getStatus(
            @RequestParam Long eventId,
            @AuthenticationPrincipal Long userId
    ) {
        QueueStatusResponse response = queueService.getStatus(eventId, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 대기열 이탈 (userId는 JWT에서 추출 — IDOR 방지)
     */
    @PostMapping("/exit")
    public ResponseEntity<Void> exit(
            @RequestParam Long eventId,
            @AuthenticationPrincipal Long userId
    ) {
        queueService.exit(eventId, userId);
        emitterManager.completeAndRemove(eventId, userId);
        return ResponseEntity.ok().build();
    }

    /**
     * 입장 토큰 발급 (userId는 JWT에서 추출 — IDOR 방지)
     */
    @PostMapping("/token")
    public ResponseEntity<QueueTokenResponse> issueToken(
            @RequestParam Long eventId,
            @AuthenticationPrincipal Long userId
    ) {
        QueueTokenResponse response = queueService.issueToken(eventId, userId);
        emitterManager.completeAndRemove(eventId, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * SSE - 대기열 순번 실시간 스트림
     *
     * EventSource는 커스텀 헤더(Authorization)를 지원하지 않으므로
     * 프론트엔드가 ?token=ACCESS_TOKEN 쿼리 파라미터로 JWT를 전달한다.
     * JwtAuthenticationFilter가 쿼리 파라미터 토큰을 인식해 SecurityContext에 인증 정보를 세팅.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQueueStatus(
            @RequestParam Long eventId,
            @AuthenticationPrincipal Long userId
    ) {
        // 30분 타임아웃
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        emitterManager.register(eventId, userId, emitter);

        // 최초 상태 전송
        try {
            QueueStatusResponse status = queueService.getStatus(eventId, userId);
            emitter.send(SseEmitter.event()
                    .name("queue-status")
                    .data(status));
        } catch (IOException e) {
            emitterManager.remove(eventId, userId);
            emitter.completeWithError(e);
            return emitter;
        }

        // 주기적으로 순번 전송 (2초마다)
        // AtomicReference: 태스크 내부에서 자기 자신을 취소하기 위해 사용
        // (lambda는 effectively-final만 캡처 가능 → 일반 변수로는 자기참조 불가)
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            SseEmitter currentEmitter = emitterManager.get(eventId, userId);
            if (currentEmitter == null) {
                // emitter가 이미 제거된 경우 태스크도 자기 취소
                ScheduledFuture<?> self = futureRef.get();
                if (self != null) self.cancel(false);
                return;
            }

            try {
                QueueStatusResponse status = queueService.getStatus(eventId, userId);
                currentEmitter.send(SseEmitter.event()
                        .name("queue-status")
                        .data(status));
            } catch (IllegalStateException | IOException e) {
                // 클라이언트 연결 끊김 또는 유저 큐 제거 — emitter 정리 후 태스크 자기 취소
                emitterManager.remove(eventId, userId);
                ScheduledFuture<?> self = futureRef.get();
                if (self != null) self.cancel(false);
            }
        }, 2, 2, TimeUnit.SECONDS);

        futureRef.set(future);

        emitter.onCompletion(() -> {
            future.cancel(true);
            emitterManager.remove(eventId, userId);
            log.debug("SSE completed: eventId={}, userId={}", eventId, userId);
        });

        emitter.onTimeout(() -> {
            future.cancel(true);
            emitterManager.remove(eventId, userId);
            log.debug("SSE timeout: eventId={}, userId={}", eventId, userId);
        });

        emitter.onError(e -> {
            future.cancel(true);
            emitterManager.remove(eventId, userId);
            log.debug("SSE error: eventId={}, userId={}", eventId, userId);
        });

        return emitter;
    }

    /**
     * 대기열 인원 조회
     */
    @GetMapping("/size")
    public ResponseEntity<Map<String, Long>> getQueueSize(@RequestParam Long eventId) {
        Long size = queueService.getQueueSize(eventId);
        return ResponseEntity.ok(Map.of("eventId", eventId, "size", size));
    }
}
