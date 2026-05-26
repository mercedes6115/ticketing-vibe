package com.ticketing.dto.booking;

import com.ticketing.entity.Booking;
import com.ticketing.entity.enums.BookingStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class BookingListResponse {

    private Long id;
    private String bookingNo;
    private BookingStatus status;
    private String eventTitle;
    private LocalDateTime eventStartAt;
    private String section;
    private String seatRow;
    private Integer seatNumber;
    private Integer price;
    private LocalDateTime createdAt;
    // Admin-only fields (null for regular user responses)
    private Long userId;
    private String userNickname;

    public static BookingListResponse from(Booking booking) {
        return BookingListResponse.builder()
                .id(booking.getId())
                .bookingNo(booking.getBookingNo())
                .status(booking.getStatus())
                .eventTitle(booking.getEvent().getTitle())
                .eventStartAt(booking.getEvent().getStartAt())
                .section(booking.getSeat().getSection())
                .seatRow(booking.getSeat().getSeatRow())
                .seatNumber(booking.getSeat().getSeatNumber())
                .price(booking.getSeat().getPrice())
                .createdAt(booking.getCreatedAt())
                .build();
    }

    public static BookingListResponse fromAdmin(Booking booking) {
        return BookingListResponse.builder()
                .id(booking.getId())
                .bookingNo(booking.getBookingNo())
                .status(booking.getStatus())
                .eventTitle(booking.getEvent().getTitle())
                .eventStartAt(booking.getEvent().getStartAt())
                .section(booking.getSeat().getSection())
                .seatRow(booking.getSeat().getSeatRow())
                .seatNumber(booking.getSeat().getSeatNumber())
                .price(booking.getSeat().getPrice())
                .createdAt(booking.getCreatedAt())
                .userId(booking.getUser().getId())
                .userNickname(booking.getUser().getNickname())
                .build();
    }
}
