package com.ticketing.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 이미터 중앙 관리 컴포넌트.
 * QueueController(등록/조회)와 QueueService(processQueue 알림) 모두에서 접근한다.
 */
@Slf4j
@Component
public class QueueEmitterManager {

    // key: "{eventId}:{userId}"
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public void register(Long eventId, Long userId, SseEmitter emitter) {
        emitters.put(key(eventId, userId), emitter);
        log.debug("SSE registered: eventId={}, userId={}", eventId, userId);
    }

    public void remove(Long eventId, Long userId) {
        emitters.remove(key(eventId, userId));
    }

    public SseEmitter get(Long eventId, Long userId) {
        return emitters.get(key(eventId, userId));
    }

    /** SSE 연결 정상 종료 */
    public void completeAndRemove(Long eventId, Long userId) {
        SseEmitter emitter = emitters.remove(key(eventId, userId));
        if (emitter != null) {
            emitter.complete();
        }
    }

    /**
     * processQueue() 호출 후 토큰 발급 알림 전송.
     * token-issued 이벤트를 보낸 뒤 SSE 연결을 정상 종료한다.
     */
    public void sendTokenIssued(Long eventId, Long userId) {
        String k = key(eventId, userId);
        SseEmitter emitter = emitters.remove(k);
        if (emitter == null) {
            // 유저가 SSE를 열지 않은 경우 – 토큰은 Redis에 저장되어 있으므로 무시
            log.debug("No SSE emitter for token-issued: eventId={}, userId={}", eventId, userId);
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("token-issued")
                    .data(Map.of(
                            "message", "입장 토큰이 발급되었습니다. 좌석을 선택하세요.",
                            "eventId", eventId
                    )));
            emitter.complete();
            log.info("token-issued SSE sent: eventId={}, userId={}", eventId, userId);
        } catch (IOException e) {
            log.warn("Failed to send token-issued SSE: eventId={}, userId={}", eventId, userId);
            emitter.completeWithError(e);
        }
    }

    private String key(Long eventId, Long userId) {
        return eventId + ":" + userId;
    }
}
