package com.ticketing.dto.kafka;

import com.ticketing.entity.enums.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BookingRequestEvent {

    private String bookingNo;
    private Long userId;
    private Long seatId;
    private PaymentMethod paymentMethod;
    private LocalDateTime requestedAt;
}
