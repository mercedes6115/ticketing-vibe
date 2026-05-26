package com.ticketing.dto.event;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class EventCreateRequest {

    @NotBlank(message = "제목은 필수입니다")
    private String title;

    private String description;

    @NotBlank(message = "장소는 필수입니다")
    private String venue;

    private String imageUrl;

    @NotNull(message = "공연 시작 시간은 필수입니다")
    @Future(message = "공연 시작 시간은 현재보다 미래여야 합니다")
    private LocalDateTime startAt;

    @NotNull(message = "예매 오픈 시간은 필수입니다")
    private LocalDateTime openAt;

    @NotNull(message = "총 좌석 수는 필수입니다")
    @Min(value = 1, message = "좌석은 1개 이상이어야 합니다")
    private Integer totalSeats;
}
