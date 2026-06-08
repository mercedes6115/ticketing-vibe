package com.ticketing.config;

import com.ticketing.dto.kafka.BookingRequestEvent;
import com.ticketing.entity.enums.BookingProcessStatus;
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

@Slf4j
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic bookingRequestsTopic() {
        return TopicBuilder.name("booking-requests")
                .partitions(10)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic bookingEventsTopic() {
        return TopicBuilder.name("booking-events")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic bookingRequestsDlqTopic() {
        return TopicBuilder.name("booking-requests.DLQ")
                .partitions(10)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic bookingEventsDlqTopic() {
        return TopicBuilder.name("booking-events.DLQ")
                .partitions(5)
                .replicas(1)
                .build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate,
            RedisTemplate<String, Object> redisTemplate) {

        DeadLetterPublishingRecoverer dlqRecoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLQ", record.partition()));

        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            Object value = record.value();
            if (value instanceof BookingRequestEvent event) {
                String statusKey = BookingService.BOOKING_STATUS_PREFIX + event.getBookingNo();
                redisTemplate.opsForValue().set(
                        statusKey,
                        BookingProcessStatus.FAILED,
                        BookingService.BOOKING_STATUS_TTL
                );
                log.error("[DLQ] 예매 요청이 최종 실패하여 DLQ로 이동했습니다. bookingNo={}", event.getBookingNo(), ex);
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
