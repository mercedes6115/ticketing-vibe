package com.ticketing.dto.kafka;

import com.ticketing.entity.enums.BookingProcessStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BookingEvent {

    private Long bookingId;
    private String bookingNo;
    private Long userId;
    private String userNickname;
    private Long eventId;
    private String eventTitle;
    private BookingProcessStatus status;
    private Long price;
    private LocalDateTime occurredAt;
}
