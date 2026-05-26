package com.ticketing.dto.booking;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * GET /api/bookings/status/{bookingNo} 폴링 응답
 * status: PROCESSING | CONFIRMED | FAILED
 */
@Getter
@AllArgsConstructor
public class BookingStatusResponse {

    private String bookingNo;
    private String status;
}
