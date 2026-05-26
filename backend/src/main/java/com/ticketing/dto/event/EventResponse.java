package com.ticketing.dto.event;

import com.ticketing.entity.Event;
import com.ticketing.entity.enums.EventStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class EventResponse {

    private Long id;
    private String title;
    private String description;
    private String venue;
    private String imageUrl;
    private LocalDateTime startAt;
    private LocalDateTime openAt;
    private EventStatus status;
    private Integer totalSeats;
    private Integer availableSeats;
    private LocalDateTime createdAt;

    public static EventResponse from(Event event) {
        return EventResponse.builder()
                .id(event.getId())
                .title(event.getTitle())
                .description(event.getDescription())
                .venue(event.getVenue())
                .imageUrl(event.getImageUrl())
                .startAt(event.getStartAt())
                .openAt(event.getOpenAt())
                .status(event.getStatus())
                .totalSeats(event.getTotalSeats())
                .availableSeats(event.getAvailableSeats())
                .createdAt(event.getCreatedAt())
                .build();
    }
}
