package com.ticketing.service;

import com.ticketing.dto.payment.PaymentResponse;
import com.ticketing.entity.Booking;
import com.ticketing.entity.Payment;
import com.ticketing.exception.NotFoundException;
import com.ticketing.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final BookingService bookingService;

    public PaymentService(
            PaymentRepository paymentRepository,
            @Lazy BookingService bookingService
    ) {
        this.paymentRepository = paymentRepository;
        this.bookingService = bookingService;
    }

    /**
     * 결제 상세 조회
     */
    public PaymentResponse getPayment(Long paymentId) {
        Payment payment = paymentRepository.findByIdWithBooking(paymentId)
                .orElseThrow(() -> new NotFoundException("결제를 찾을 수 없습니다. id=" + paymentId));
        return PaymentResponse.from(payment);
    }

    /**
     * 예매 ID로 결제 조회
     */
    public PaymentResponse getPaymentByBookingId(Long bookingId) {
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new NotFoundException("결제 정보를 찾을 수 없습니다. bookingId=" + bookingId));
        return PaymentResponse.from(payment);
    }

    /**
     * 환불 처리 (예매 취소 연계)
     *
     * BookingService.cancelBooking()에 위임해 원자적 available_seats 복구,
     * WebSocket 브로드캐스트, Kafka 이벤트 발행이 동일하게 보장된다.
     */
    @Transactional
    public PaymentResponse refund(Long paymentId, Long userId) {
        Payment payment = paymentRepository.findByIdWithBooking(paymentId)
                .orElseThrow(() -> new NotFoundException("결제를 찾을 수 없습니다. id=" + paymentId));

        Booking booking = payment.getBooking();

        if (!booking.getUser().getId().equals(userId)) {
            throw new IllegalStateException("본인의 결제만 환불할 수 있습니다.");
        }

        if (!payment.isSuccess()) {
            throw new IllegalStateException("환불 가능한 결제 상태가 아닙니다. 현재 상태: " + payment.getStatus());
        }

        // 취소 위임 — 원자적 available_seats 복구 + WebSocket + Kafka afterCommit 포함
        bookingService.cancelBooking(booking.getId(), userId);

        log.info("Payment refunded: paymentId={}, bookingNo={}", paymentId, booking.getBookingNo());
        return PaymentResponse.from(payment);
    }
}
