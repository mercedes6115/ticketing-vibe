package com.ticketing.dto.queue;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class QueueStatusResponse {

    private Long eventId;
    private Long userId;
    private Long position;           // 현재 순번 (1부터 시작)
    private Long totalWaiting;       // 전체 대기 인원
    private Long estimatedWaitSeconds;  // 예상 대기 시간 (초)
    private boolean canEnter;        // 입장 가능 여부

    public static QueueStatusResponse of(Long eventId, Long userId, Long position,
                                          Long totalWaiting, boolean canEnter) {
        // 예상 대기 시간: 1명당 약 3초로 계산 (조정 가능)
        long estimatedSeconds = canEnter ? 0 : (position - 1) * 3;

        return QueueStatusResponse.builder()
                .eventId(eventId)
                .userId(userId)
                .position(position)
                .totalWaiting(totalWaiting)
                .estimatedWaitSeconds(estimatedSeconds)
                .canEnter(canEnter)
                .build();
    }
}
