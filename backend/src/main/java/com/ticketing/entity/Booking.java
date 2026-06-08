package com.ticketing.entity;

import com.ticketing.entity.enums.BookingStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "bookings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(name = "booking_no", nullable = false, unique = true, length = 20)
    private String bookingNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.PENDING;

    @OneToOne(mappedBy = "booking", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private Payment payment;

    @Builder
    public Booking(User user, Event event, Seat seat, String bookingNo) {
        this.user = user;
        this.event = event;
        this.seat = seat;
        this.bookingNo = bookingNo;
        this.status = BookingStatus.PENDING;
    }

    public void confirm() {
        if (!isPending()) {
            throw new IllegalStateException("대기 중인 예매만 확정할 수 있습니다. status=" + status);
        }
        this.status = BookingStatus.CONFIRMED;
    }

    public void cancel() {
        if (isCancelled()) {
            throw new IllegalStateException("이미 취소된 예매입니다.");
        }
        this.status = BookingStatus.CANCELLED;
    }

    public boolean isPending() {
        return this.status == BookingStatus.PENDING;
    }

    public boolean isConfirmed() {
        return this.status == BookingStatus.CONFIRMED;
    }

    public boolean isCancelled() {
        return this.status == BookingStatus.CANCELLED;
    }
}
