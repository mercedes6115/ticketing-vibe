package com.ticketing.dto.queue;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class QueueTokenResponse {

    private String token;            // UUID 입장 토큰
    private Long eventId;
    private Long userId;
    private LocalDateTime expiresAt; // 만료 시간
    private long ttlSeconds;         // 남은 시간 (초)

    public static QueueTokenResponse of(String token, Long eventId, Long userId, long ttlSeconds) {
        return QueueTokenResponse.builder()
                .token(token)
                .eventId(eventId)
                .userId(userId)
                .expiresAt(LocalDateTime.now().plusSeconds(ttlSeconds))
                .ttlSeconds(ttlSeconds)
                .build();
    }
}
