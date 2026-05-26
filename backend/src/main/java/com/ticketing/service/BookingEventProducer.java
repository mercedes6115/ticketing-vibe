package com.ticketing.service;

import com.ticketing.dto.kafka.BookingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventProducer {

    private static final String TOPIC = "booking-events";

    private final KafkaTemplate<String, BookingEvent> kafkaTemplate;

    /**
     * 예매 이벤트 발행
     * key: bookingId 문자열 → 같은 예매의 이벤트는 항상 동일 파티션으로 라우팅
     */
    public void send(BookingEvent event) {
        String key = String.valueOf(event.getBookingId());

        kafkaTemplate.send(TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Kafka Producer] 전송 실패: bookingNo={}, error={}",
                                event.getBookingNo(), ex.getMessage());
                    } else {
                        log.info("[Kafka Producer] 전송 완료: bookingNo={}, topic={}, partition={}, offset={}",
                                event.getBookingNo(),
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
