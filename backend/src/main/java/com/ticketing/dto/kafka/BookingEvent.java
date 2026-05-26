package com.ticketing.dto.kafka;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Kafka booking-events 토픽에 발행되는 이벤트 메시지
 * Producer: BookingService (예매 생성/취소 시)
 * Consumer: BookingEventConsumer (알림, 통계 등 처리)
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BookingEvent {

    private Long bookingId;
    private String bookingNo;
    private Long userId;
    private String userNickname;
    private Long eventId;
    private String eventTitle;
    private String status;       // CONFIRMED | CANCELLED
    private Long price;
    private LocalDateTime occurredAt;
}
