package com.ticketing.dto.queue;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class QueueEnterRequest {

    @NotNull(message = "이벤트 ID는 필수입니다")
    private Long eventId;
}
