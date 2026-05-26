package com.ticketing.service;

import com.ticketing.dto.event.EventCreateRequest;
import com.ticketing.dto.event.EventResponse;
import com.ticketing.dto.event.EventUpdateRequest;
import com.ticketing.entity.Event;
import com.ticketing.entity.enums.EventStatus;
import com.ticketing.exception.NotFoundException;
import com.ticketing.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository eventRepository;

    private static final String UPLOAD_DIR = "/opt/app/uploads/events/";
    private static final String URL_PREFIX = "/uploads/events/";

    @Transactional
    public EventResponse create(EventCreateRequest request) {
        Event event = Event.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .venue(request.getVenue())
                .imageUrl(request.getImageUrl())
                .startAt(request.getStartAt())
                .openAt(request.getOpenAt())
                .totalSeats(request.getTotalSeats())
                .build();

        Event saved = eventRepository.save(event);
        log.info("Event created: id={}, title={}", saved.getId(), saved.getTitle());

        return EventResponse.from(saved);
    }

    public EventResponse getById(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> NotFoundException.event(id));
        return EventResponse.from(event);
    }

    public Page<EventResponse> getAll(Pageable pageable) {
        return eventRepository.findAll(pageable)
                .map(EventResponse::from);
    }

    public Page<EventResponse> getByStatus(EventStatus status, Pageable pageable) {
        return eventRepository.findByStatus(status, pageable)
                .map(EventResponse::from);
    }

    public Page<EventResponse> getOpenEvents(Pageable pageable) {
        List<EventStatus> statuses = List.of(EventStatus.SCHEDULED, EventStatus.OPEN);
        return eventRepository.findByStatusIn(statuses, pageable)
                .map(EventResponse::from);
    }

    @Transactional
    public EventResponse update(Long id, EventUpdateRequest request) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> NotFoundException.event(id));

        event.update(
                request.getTitle(),
                request.getDescription(),
                request.getVenue(),
                request.getImageUrl(),
                request.getStartAt(),
                request.getOpenAt()
        );

        log.info("Event updated: id={}", id);
        return EventResponse.from(event);
    }

    @Transactional
    public EventResponse updateStatus(Long id, EventStatus status) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> NotFoundException.event(id));

        event.updateStatus(status);
        log.info("Event status updated: id={}, status={}", id, status);

        return EventResponse.from(event);
    }

    @Transactional
    public EventResponse uploadImage(Long id, MultipartFile file) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> NotFoundException.event(id));

        String originalFilename = file.getOriginalFilename();
        String ext = (originalFilename != null && originalFilename.contains("."))
                ? originalFilename.substring(originalFilename.lastIndexOf('.'))
                : ".jpg";
        String filename = UUID.randomUUID() + ext;
        Path filePath = Paths.get(UPLOAD_DIR).resolve(filename);

        try {
            Files.createDirectories(filePath.getParent());
            file.transferTo(filePath);
        } catch (IOException e) {
            throw new IllegalStateException("이미지 업로드에 실패했습니다: " + e.getMessage());
        }

        // DB 롤백 시 업로드한 파일 삭제 (고아 파일 방지)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        Files.deleteIfExists(filePath);
                    } catch (IOException e) {
                        log.warn("고아 파일 삭제 실패: {}", filePath, e);
                    }
                }
            }
        });

        event.update(null, null, null, URL_PREFIX + filename, null, null);
        log.info("Event image uploaded: id={}, file={}", id, filename);
        return EventResponse.from(event);
    }

    @Transactional
    public void delete(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> NotFoundException.event(id));

        if (event.getStatus() == EventStatus.OPEN) {
            throw new IllegalStateException("진행 중인 이벤트는 삭제할 수 없습니다");
        }

        eventRepository.delete(event);
        log.info("Event deleted: id={}", id);
    }
}
