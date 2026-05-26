package com.ticketing.dto.booking;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * POST /api/bookings 202 Accepted 응답
 * 프론트엔드는 bookingNo로 /status/{bookingNo}를 폴링한다.
 */
@Getter
@AllArgsConstructor
public class BookingAcceptedResponse {

    private String bookingNo;
    private String status;  // "PROCESSING"
}
