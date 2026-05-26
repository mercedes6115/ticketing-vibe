package com.ticketing.controller;

import com.ticketing.dto.event.EventCreateRequest;
import com.ticketing.dto.event.EventResponse;
import com.ticketing.dto.event.EventUpdateRequest;
import com.ticketing.entity.enums.EventStatus;
import com.ticketing.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventCreateRequest request) {
        EventResponse response = eventService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getById(@PathVariable Long id) {
        EventResponse response = eventService.getById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<EventResponse>> getAll(
            @PageableDefault(size = 10, sort = "openAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<EventResponse> response = eventService.getOpenEvents(pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<Page<EventResponse>> getByStatus(
            @PathVariable EventStatus status,
            @PageableDefault(size = 10, sort = "openAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<EventResponse> response = eventService.getByStatus(status, pageable);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<EventResponse> update(
            @PathVariable Long id,
            @RequestBody EventUpdateRequest request
    ) {
        EventResponse response = eventService.update(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<EventResponse> updateStatus(
            @PathVariable Long id,
            @RequestParam EventStatus status
    ) {
        EventResponse response = eventService.updateStatus(id, status);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EventResponse> uploadImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file
    ) {
        EventResponse response = eventService.uploadImage(id, file);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        eventService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
