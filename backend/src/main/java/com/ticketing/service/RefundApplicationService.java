package com.ticketing.service;

import com.ticketing.dto.payment.PaymentResponse;
import com.ticketing.entity.Booking;
import com.ticketing.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundApplicationService {

    private final PaymentService paymentService;
    private final BookingCancellationService bookingCancellationService;

    @Transactional
    public PaymentResponse refund(Long paymentId, Long userId) {
        Payment payment = paymentService.getRefundablePayment(paymentId, userId);
        Booking booking = bookingCancellationService.cancelBookingForRefund(payment.getBooking().getId(), userId);

        log.info("Payment refunded: paymentId={}, bookingNo={}", paymentId, booking.getBookingNo());
        return PaymentResponse.from(payment);
    }
}
