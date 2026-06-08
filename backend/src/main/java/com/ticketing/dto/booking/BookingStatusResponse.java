package com.ticketing.dto.booking;

import com.ticketing.entity.enums.BookingProcessStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BookingStatusResponse {

    private String bookingNo;
    private BookingProcessStatus status;
}
