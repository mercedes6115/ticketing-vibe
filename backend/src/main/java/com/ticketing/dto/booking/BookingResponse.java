package com.ticketing.dto.booking;

import com.ticketing.entity.Booking;
import com.ticketing.entity.enums.BookingStatus;
import com.ticketing.entity.enums.PaymentMethod;
import com.ticketing.entity.enums.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class BookingResponse {

    private Long id;
    private String bookingNo;
    private BookingStatus status;
    private LocalDateTime createdAt;

    // Event 정보
    private Long eventId;
    private String eventTitle;
    private LocalDateTime eventStartAt;

    // Seat 정보
    private Long seatId;
    private String section;
    private String seatRow;
    private Integer seatNumber;
    private Integer price;

    // Payment 정보
    private Long paymentId;
    private PaymentMethod paymentMethod;
    private PaymentStatus paymentStatus;
    private Integer amount;

    public static BookingResponse from(Booking booking) {
        BookingResponseBuilder builder = BookingResponse.builder()
                .id(booking.getId())
                .bookingNo(booking.getBookingNo())
                .status(booking.getStatus())
                .createdAt(booking.getCreatedAt())
                .eventId(booking.getEvent().getId())
                .eventTitle(booking.getEvent().getTitle())
                .eventStartAt(booking.getEvent().getStartAt())
                .seatId(booking.getSeat().getId())
                .section(booking.getSeat().getSection())
                .seatRow(booking.getSeat().getSeatRow())
                .seatNumber(booking.getSeat().getSeatNumber())
                .price(booking.getSeat().getPrice());

        if (booking.getPayment() != null) {
            builder.paymentId(booking.getPayment().getId())
                    .paymentMethod(booking.getPayment().getMethod())
                    .paymentStatus(booking.getPayment().getStatus())
                    .amount(booking.getPayment().getAmount());
        }

        return builder.build();
    }
}
