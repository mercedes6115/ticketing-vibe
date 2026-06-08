package com.ticketing.controller;

import com.ticketing.dto.payment.PaymentResponse;
import com.ticketing.service.PaymentService;
import com.ticketing.service.RefundApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final RefundApplicationService refundApplicationService;

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable Long paymentId,
            @AuthenticationPrincipal Long userId
    ) {
        PaymentResponse response = paymentService.getPayment(paymentId, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/bookings/{bookingId}")
    public ResponseEntity<PaymentResponse> getPaymentByBookingId(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal Long userId
    ) {
        PaymentResponse response = paymentService.getPaymentByBookingId(bookingId, userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<PaymentResponse> refund(
            @PathVariable Long paymentId,
            @AuthenticationPrincipal Long userId
    ) {
        PaymentResponse response = refundApplicationService.refund(paymentId, userId);
        return ResponseEntity.ok(response);
    }
}
