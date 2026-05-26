package com.ticketing.dto.payment;

import com.ticketing.entity.Payment;
import com.ticketing.entity.enums.PaymentMethod;
import com.ticketing.entity.enums.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class PaymentResponse {

    private Long id;
    private Long bookingId;
    private String bookingNo;
    private Integer amount;
    private PaymentMethod method;
    private PaymentStatus status;
    private String idempotencyKey;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;

    public static PaymentResponse from(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId())
                .bookingId(payment.getBooking().getId())
                .bookingNo(payment.getBooking().getBookingNo())
                .amount(payment.getAmount())
                .method(payment.getMethod())
                .status(payment.getStatus())
                .idempotencyKey(payment.getIdempotencyKey())
                .paidAt(payment.getPaidAt())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
