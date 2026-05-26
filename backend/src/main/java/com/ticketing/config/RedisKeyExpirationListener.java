package com.ticketing.config;

import com.ticketing.dto.seat.SeatStatusMessage;
import com.ticketing.repository.SeatRepository;
import com.ticketing.util.TransactionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisKeyExpirationListener {

    @Bean
    public RedisMessageListenerContainer keyExpirationListenerContainer(
            RedisConnectionFactory connectionFactory,
            SeatHoldExpirationHandler handler
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(handler, new PatternTopic("__keyevent@*__:expired"));
        return container;
    }

    @Slf4j
    @Component
    @RequiredArgsConstructor
    public static class SeatHoldExpirationHandler implements MessageListener {

        private final SeatRepository seatRepository;
        private final SimpMessagingTemplate messagingTemplate;

        private static final String SEAT_HOLD_PREFIX = "seat:hold:";

        @Override
        @Transactional
        public void onMessage(Message message, byte[] pattern) {
            String expiredKey = new String(message.getBody());

            if (expiredKey.startsWith(SEAT_HOLD_PREFIX)) {
                Long seatId = Long.valueOf(expiredKey.substring(SEAT_HOLD_PREFIX.length()));
                log.info("Seat hold expired: seatId={}", seatId);

                try {
                    // 원자적 조건부 UPDATE: status = 'HOLD' 인 경우에만 AVAILABLE로 변경
                    // 동시 만료·예매 처리 시 double-release 방지
                    int updated = seatRepository.releaseHoldSeat(seatId);
                    if (updated > 0) {
                        log.info("Seat auto-released: seatId={}", seatId);

                        // eventId 조회: JOIN FETCH로 lazy 로드 방지 (entity는 이미 detached)
                        seatRepository.findByIdWithEvent(seatId).ifPresent(seat -> {
                            SeatStatusMessage msg = SeatStatusMessage.release(seatId, seat.getEvent().getId());
                            // 커밋 후 브로드캐스트 — 롤백 시 클라이언트가 잘못된 AVAILABLE 수신 방지
                            TransactionUtils.afterCommit(() -> messagingTemplate.convertAndSend(
                                    "/topic/events/" + msg.getEventId() + "/seats", msg));
                        });
                    }
                } catch (Exception e) {
                    log.error("Failed to release expired seat: seatId={}", seatId, e);
                }
            }
        }
    }
}
