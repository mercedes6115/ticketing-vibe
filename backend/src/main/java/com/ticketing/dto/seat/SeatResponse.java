package com.ticketing.dto.seat;

import com.ticketing.entity.Seat;
import com.ticketing.entity.enums.SeatStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SeatResponse {

    private Long id;
    private Long eventId;
    private String section;
    private String seatRow;
    private Integer seatNumber;
    private Integer price;
    private SeatStatus status;
    /** 좌석 선점 응답 전용 — hold 시 남은 TTL(초). 일반 조회 시 null. */
    private Long holdExpiresInSeconds;

    public static SeatResponse from(Seat seat) {
        return SeatResponse.builder()
                .id(seat.getId())
                .eventId(seat.getEvent().getId())
                .section(seat.getSection())
                .seatRow(seat.getSeatRow())
                .seatNumber(seat.getSeatNumber())
                .price(seat.getPrice())
                .status(seat.getStatus())
                .build();
    }

    public static SeatResponse fromHold(Seat seat, long holdTtlSeconds) {
        return SeatResponse.builder()
                .id(seat.getId())
                .eventId(seat.getEvent().getId())
                .section(seat.getSection())
                .seatRow(seat.getSeatRow())
                .seatNumber(seat.getSeatNumber())
                .price(seat.getPrice())
                .status(seat.getStatus())
                .holdExpiresInSeconds(holdTtlSeconds)
                .build();
    }
}
