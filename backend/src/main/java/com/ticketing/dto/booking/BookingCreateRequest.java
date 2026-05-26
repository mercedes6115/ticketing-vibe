package com.ticketing.dto.booking;

import com.ticketing.entity.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class BookingCreateRequest {

    @NotNull(message = "좌석 ID는 필수입니다")
    private Long seatId;

    @NotNull(message = "결제 수단은 필수입니다")
    private PaymentMethod paymentMethod;
}
