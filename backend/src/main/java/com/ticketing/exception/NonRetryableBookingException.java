package com.ticketing.exception;

/**
 * 예매 요청 중 재시도로 해결되지 않는 비즈니스 실패를 나타낸다.
 */
public class NonRetryableBookingException extends RuntimeException {

    public NonRetryableBookingException(String message) {
        super(message);
    }
}
