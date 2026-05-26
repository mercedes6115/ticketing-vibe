package com.ticketing.dto.kafka;

import com.ticketing.entity.enums.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Kafka booking-requests 토픽에 발행되는 예매 요청 메시지
 * Producer: BookingService (POST /api/bookings 수신 시)
 * Consumer: BookingRequestConsumer (DB 쓰기 + 상태 업데이트)
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BookingRequestEvent {

    private String bookingNo;      // 아이디엠포턴시 키
    private Long userId;
    private Long seatId;
    private PaymentMethod paymentMethod;
    private LocalDateTime requestedAt;
}
