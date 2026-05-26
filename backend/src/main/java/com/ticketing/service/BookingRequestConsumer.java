package com.ticketing.service;

import com.ticketing.dto.kafka.BookingEvent;
import com.ticketing.dto.kafka.BookingRequestEvent;
import com.ticketing.exception.NonRetryableBookingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Consumes booking requests from Kafka and persists the booking asynchronously.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingRequestConsumer {

    private final BookingService bookingService;
    private final BookingEventProducer bookingEventProducer;
    private final RedisTemplate<String, Object> redisTemplate;

    @KafkaListener(topics = "booking-requests", groupId = "booking-requests-group")
    public void consume(
            BookingRequestEvent requestEvent,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        long consumerStartedAtNanos = System.nanoTime();
        long queueDelayMs = millisBetween(requestEvent.getRequestedAt(), LocalDateTime.now());

        log.info(
                "[BookingRequestConsumer] received: partition={}, offset={}, bookingNo={}, queueDelayMs={}",
                partition,
                offset,
                requestEvent.getBookingNo(),
                queueDelayMs
        );

        String statusKey = BookingService.BOOKING_STATUS_PREFIX + requestEvent.getBookingNo();

        BookingEvent bookingEvent;
        try {
            long persistStartedAtNanos = System.nanoTime();
            bookingEvent = bookingService.persistBookingRequest(requestEvent);
            long persistMs = nanosToMillis(System.nanoTime() - persistStartedAtNanos);

            long statusUpdateStartedAtNanos = System.nanoTime();
            redisTemplate.opsForValue().set(statusKey, "CONFIRMED", BookingService.BOOKING_STATUS_TTL);
            long statusUpdateMs = nanosToMillis(System.nanoTime() - statusUpdateStartedAtNanos);

            long consumerWorkMs = nanosToMillis(System.nanoTime() - consumerStartedAtNanos);
            long totalAsyncMs = millisBetween(requestEvent.getRequestedAt(), LocalDateTime.now());

            log.info(
                    "[BookingRequestConsumer] completed: bookingNo={}, queueDelayMs={}, persistMs={}, statusUpdateMs={}, consumerWorkMs={}, totalAsyncMs={}",
                    requestEvent.getBookingNo(),
                    queueDelayMs,
                    persistMs,
                    statusUpdateMs,
                    consumerWorkMs,
                    totalAsyncMs
            );
        } catch (NonRetryableBookingException e) {
            redisTemplate.opsForValue().set(statusKey, "FAILED", BookingService.BOOKING_STATUS_TTL);
            log.warn(
                    "[BookingRequestConsumer] failed(non-retryable): bookingNo={}, queueDelayMs={}, error={}",
                    requestEvent.getBookingNo(),
                    queueDelayMs,
                    e.getMessage()
            );
            return;
        } catch (Exception e) {
            log.error(
                    "[BookingRequestConsumer] failed: bookingNo={}, queueDelayMs={}, error={}",
                    requestEvent.getBookingNo(),
                    queueDelayMs,
                    e.getMessage(),
                    e
            );
            throw e;
        }

        try {
            bookingEventProducer.send(bookingEvent);
        } catch (Exception e) {
            log.error(
                    "[BookingRequestConsumer] booking-events publish failed after confirmation: bookingNo={}",
                    requestEvent.getBookingNo(),
                    e
            );
        }
    }

    private long millisBetween(LocalDateTime start, LocalDateTime end) {
        return start == null ? -1L : ChronoUnit.MILLIS.between(start, end);
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
