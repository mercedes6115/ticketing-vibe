package com.ticketing.service;

import com.ticketing.dto.kafka.BookingEvent;
import com.ticketing.entity.enums.BookingProcessStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BookingEventConsumer {

    @KafkaListener(topics = "booking-events", groupId = "ticketing-group")
    public void consume(
            BookingEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("[Kafka Consumer] received: partition={}, offset={}, bookingNo={}, status={}",
                partition, offset, event.getBookingNo(), event.getStatus());

        if (event.getStatus() == BookingProcessStatus.CONFIRMED) {
            handleConfirmed(event);
            return;
        }

        if (event.getStatus() == BookingProcessStatus.CANCELLED) {
            handleCancelled(event);
            return;
        }

        log.warn("[Kafka Consumer] unsupported status: {}", event.getStatus());
    }

    private void handleConfirmed(BookingEvent event) {
        log.info("  [확정] 예매자: {}, 공연: {}, 금액: {}원",
                event.getUserNickname(), event.getEventTitle(), event.getPrice());
        log.info("  알림 발송 예정 (이메일/SMS) to userId={}", event.getUserId());
    }

    private void handleCancelled(BookingEvent event) {
        log.info("  [취소] 예매번호: {}, 환불 금액: {}원",
                event.getBookingNo(), event.getPrice());
        log.info("  환불 처리 예정 to userId={}", event.getUserId());
    }
}
