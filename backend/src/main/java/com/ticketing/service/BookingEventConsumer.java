package com.ticketing.service;

import com.ticketing.dto.kafka.BookingEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BookingEventConsumer {

    /**
     * booking-events 토픽 소비
     *
     * groupId = "ticketing-group"
     *   → 같은 그룹 내 여러 인스턴스가 파티션을 나눠서 처리 (수평 확장)
     *   → 파티션 3개, 인스턴스 3개면 각 인스턴스가 파티션 1개씩 담당
     *
     * @Header 로 파티션 번호와 오프셋을 주입받아 로깅
     *   → Kafka의 오프셋 = 메시지 위치 좌표. 컨슈머는 오프셋을 커밋하며 진행
     */
    @KafkaListener(topics = "booking-events", groupId = "ticketing-group")
    public void consume(
            BookingEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("[Kafka Consumer] 수신: partition={}, offset={}, bookingNo={}, status={}",
                partition, offset, event.getBookingNo(), event.getStatus());

        switch (event.getStatus()) {
            case "CONFIRMED" -> handleConfirmed(event);
            case "CANCELLED" -> handleCancelled(event);
            default -> log.warn("[Kafka Consumer] 알 수 없는 상태: {}", event.getStatus());
        }
    }

    private void handleConfirmed(BookingEvent event) {
        // 실제 서비스라면: 이메일/SMS 발송, 포인트 적립, 통계 카운터 증가 등
        log.info("  → [확정] 예매자: {}, 공연: {}, 금액: {}원",
                event.getUserNickname(), event.getEventTitle(), event.getPrice());
        log.info("  → 알림 발송 예정 (이메일/SMS) to userId={}", event.getUserId());
    }

    private void handleCancelled(BookingEvent event) {
        // 실제 서비스라면: 환불 처리, 재고 복구 알림, 대기자 통보 등
        log.info("  → [취소] 예매번호: {}, 환불 금액: {}원",
                event.getBookingNo(), event.getPrice());
        log.info("  → 환불 처리 예정 to userId={}", event.getUserId());
    }
}
