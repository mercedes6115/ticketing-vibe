package com.ticketing.dto.event;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
public class EventUpdateRequest {

    private String title;
    private String description;
    private String venue;
    private String imageUrl;
    private LocalDateTime startAt;
    private LocalDateTime openAt;
}
