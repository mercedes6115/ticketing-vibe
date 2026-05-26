package com.ticketing.entity;

import com.ticketing.entity.enums.SeatStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "section", "seat_row", "seat_number"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false, length = 50)
    private String section;

    @Column(name = "seat_row", nullable = false, length = 10)
    private String seatRow;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Column(nullable = false)
    private Integer price = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Builder
    public Seat(Event event, String section, String seatRow, Integer seatNumber, Integer price) {
        this.event = event;
        this.section = section;
        this.seatRow = seatRow;
        this.seatNumber = seatNumber;
        this.price = price != null ? price : 0;
        this.status = SeatStatus.AVAILABLE;
    }

    public void hold() {
        this.status = SeatStatus.HOLD;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
    }

    public void sell() {
        this.status = SeatStatus.SOLD;
    }

    public boolean isAvailable() {
        return this.status == SeatStatus.AVAILABLE;
    }

    public boolean isHold() {
        return this.status == SeatStatus.HOLD;
    }

    public boolean isSold() {
        return this.status == SeatStatus.SOLD;
    }
}
