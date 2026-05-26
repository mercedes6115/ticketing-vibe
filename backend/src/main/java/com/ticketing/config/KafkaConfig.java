package com.ticketing.config;

import com.ticketing.dto.kafka.BookingRequestEvent;
import com.ticketing.service.BookingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 토픽 설정
 * 애플리케이션 시작 시 토픽이 없으면 자동 생성
 */
@Slf4j
@Configuration
public class KafkaConfig {

    /**
     * booking-requests 토픽
     * - partitions(10): 높은 병렬성으로 대량 예매 요청 처리
     * - replicas(1): 브로커 1대 환경 (프로덕션은 replicas ≥ 2 권장)
     *
     * 파티션 키: userId → 같은 사용자의 요청이 항상 같은 파티션으로 (순서 보장)
     */
    @Bean
    public NewTopic bookingRequestsTopic() {
        return TopicBuilder.name("booking-requests")
                .partitions(10)
                .replicas(1)
                .build();
    }

    /**
     * booking-events 토픽
     * - partitions(5): 알림/통계 다운스트림 처리
     * - replicas(1): 브로커 1대 환경 (프로덕션은 replicas ≥ 2 권장)
     *
     * 파티션 키: bookingId → 같은 예매의 이벤트는 항상 같은 파티션 (순서 보장)
     */
    @Bean
    public NewTopic bookingEventsTopic() {
        return TopicBuilder.name("booking-events")
                .partitions(5)
                .replicas(1)
                .build();
    }

    /** booking-requests DLQ: 3회 재시도 후 여기로 격리 */
    @Bean
    public NewTopic bookingRequestsDlqTopic() {
        return TopicBuilder.name("booking-requests.DLQ")
                .partitions(10)
                .replicas(1)
                .build();
    }

    /** booking-events DLQ */
    @Bean
    public NewTopic bookingEventsDlqTopic() {
        return TopicBuilder.name("booking-events.DLQ")
                .partitions(5)
                .replicas(1)
                .build();
    }

    /**
     * 공통 KafkaListenerContainerFactory
     *
     * DefaultErrorHandler: 1초 간격으로 최대 3회 재시도 후 .DLQ 토픽으로 격리한다.
     * 재시도 없이 루핑하는 poison-message 문제를 방지한다.
     *
     * FAILED 상태는 모든 재시도 소진 후 recoverer에서만 기록한다.
     * Consumer catch 블록에서 기록하면 재시도 중 프론트엔드가 false FAILED를 수신하고
     * 폴링을 중단하므로, 재시도 성공 시에도 사용자는 실패로 인식하는 문제가 생긴다.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate,
            RedisTemplate<String, Object> redisTemplate) {

        DeadLetterPublishingRecoverer dlqRecoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLQ", record.partition()));

        // 모든 재시도 소진 후에만 FAILED 기록 → DLQ 격리
        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            Object value = record.value();
            if (value instanceof BookingRequestEvent event) {
                String statusKey = BookingService.BOOKING_STATUS_PREFIX + event.getBookingNo();
                redisTemplate.opsForValue().set(statusKey, "FAILED", BookingService.BOOKING_STATUS_TTL);
                log.error("[DLQ] 예매 요청 영구 실패, DLQ 격리: bookingNo={}", event.getBookingNo(), ex);
            }
            dlqRecoverer.accept(record, ex);
        };

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(1000L, 3L));

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(10);
        return factory;
    }
}
