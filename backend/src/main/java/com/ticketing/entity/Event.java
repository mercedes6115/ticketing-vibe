package com.ticketing.entity;

import com.ticketing.entity.enums.EventStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "open_at", nullable = false)
    private LocalDateTime openAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status = EventStatus.SCHEDULED;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats = 0;

    @Column(name = "available_seats", nullable = false)
    private Integer availableSeats = 0;

    @OneToMany(mappedBy = "event", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<Seat> seats = new ArrayList<>();

    @OneToMany(mappedBy = "event", fetch = FetchType.LAZY)
    private List<Booking> bookings = new ArrayList<>();

    @Builder
    public Event(String title, String description, String venue, String imageUrl,
                 LocalDateTime startAt, LocalDateTime openAt, EventStatus status,
                 Integer totalSeats) {
        this.title = title;
        this.description = description;
        this.venue = venue;
        this.imageUrl = imageUrl;
        this.startAt = startAt;
        this.openAt = openAt;
        this.status = status != null ? status : EventStatus.SCHEDULED;
        this.totalSeats = totalSeats != null ? totalSeats : 0;
        this.availableSeats = this.totalSeats;
    }

    public void updateStatus(EventStatus status) {
        this.status = status;
    }

    public boolean isOpen() {
        return this.status == EventStatus.OPEN;
    }

    public boolean canBook() {
        return isOpen() && this.availableSeats > 0;
    }

    public void update(String title, String description, String venue, String imageUrl,
                       LocalDateTime startAt, LocalDateTime openAt) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (venue != null) this.venue = venue;
        if (imageUrl != null) this.imageUrl = imageUrl;
        if (startAt != null) this.startAt = startAt;
        if (openAt != null) this.openAt = openAt;
    }
}
