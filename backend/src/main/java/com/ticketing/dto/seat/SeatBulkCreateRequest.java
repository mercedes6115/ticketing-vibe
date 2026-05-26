package com.ticketing.dto.seat;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SeatBulkCreateRequest {

    @NotBlank(message = "구역은 필수입니다")
    private String section;

    @NotNull(message = "열 수는 필수입니다")
    @Min(value = 1, message = "열 수는 1 이상이어야 합니다")
    private Integer rowCount;

    @NotNull(message = "열당 좌석 수는 필수입니다")
    @Min(value = 1, message = "열당 좌석 수는 1 이상이어야 합니다")
    private Integer seatsPerRow;

    @NotNull(message = "가격은 필수입니다")
    @Min(value = 0, message = "가격은 0 이상이어야 합니다")
    private Integer price;
}
