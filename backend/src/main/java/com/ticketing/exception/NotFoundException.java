package com.ticketing.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException event(Long id) {
        return new NotFoundException("이벤트를 찾을 수 없습니다: " + id);
    }

    public static NotFoundException user(Long id) {
        return new NotFoundException("사용자를 찾을 수 없습니다: " + id);
    }

    public static NotFoundException seat(Long id) {
        return new NotFoundException("좌석을 찾을 수 없습니다: " + id);
    }

    public static NotFoundException booking(Long id) {
        return new NotFoundException("예매를 찾을 수 없습니다: " + id);
    }

    public static NotFoundException bookingByNo(String bookingNo) {
        return new NotFoundException("예매를 찾을 수 없습니다: " + bookingNo);
    }
}
