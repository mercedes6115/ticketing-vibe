package com.ticketing.service;

import com.ticketing.dto.payment.PaymentResponse;
import com.ticketing.entity.Booking;
import com.ticketing.entity.Payment;
import com.ticketing.exception.ForbiddenException;
import com.ticketing.exception.NotFoundException;
import com.ticketing.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentResponse getPayment(Long paymentId, Long userId) {
        Payment payment = paymentRepository.findByIdWithBooking(paymentId)
                .orElseThrow(() -> new NotFoundException("결제를 찾을 수 없습니다. id=" + paymentId));
        validateOwnership(payment.getBooking(), userId, "본인 결제만 조회할 수 있습니다.");
        return PaymentResponse.from(payment);
    }

    public PaymentResponse getPaymentByBookingId(Long bookingId, Long userId) {
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new NotFoundException("결제 정보를 찾을 수 없습니다. bookingId=" + bookingId));
        validateOwnership(payment.getBooking(), userId, "본인 결제만 조회할 수 있습니다.");
        return PaymentResponse.from(payment);
    }

    public Payment getRefundablePayment(Long paymentId, Long userId) {
        Payment payment = paymentRepository.findByIdWithBooking(paymentId)
                .orElseThrow(() -> new NotFoundException("결제를 찾을 수 없습니다. id=" + paymentId));

        validateOwnership(payment.getBooking(), userId, "본인 결제만 환불할 수 있습니다.");

        if (!payment.isSuccess()) {
            throw new IllegalStateException("환불 가능한 결제 상태가 아닙니다. 현재 상태=" + payment.getStatus());
        }

        return payment;
    }

    private void validateOwnership(Booking booking, Long userId, String message) {
        if (!booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException(message);
        }
    }
}
