package com.ticketing.dto.seat;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SeatCreateRequest {

    @NotBlank(message = "구역은 필수입니다")
    private String section;

    @NotBlank(message = "열은 필수입니다")
    private String seatRow;

    @NotNull(message = "좌석 번호는 필수입니다")
    @Min(value = 1, message = "좌석 번호는 1 이상이어야 합니다")
    private Integer seatNumber;

    @NotNull(message = "가격은 필수입니다")
    @Min(value = 0, message = "가격은 0 이상이어야 합니다")
    private Integer price;
}
