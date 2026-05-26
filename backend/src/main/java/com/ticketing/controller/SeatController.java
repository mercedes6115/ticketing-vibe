package com.ticketing.controller;

import com.ticketing.dto.seat.SeatBulkCreateRequest;
import com.ticketing.dto.seat.SeatResponse;
import com.ticketing.service.SeatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    /**
     * 이벤트별 좌석 목록 조회
     */
    @GetMapping("/events/{eventId}/seats")
    public ResponseEntity<List<SeatResponse>> getSeatsByEventId(@PathVariable Long eventId) {
        List<SeatResponse> response = seatService.getSeatsByEventId(eventId);
        return ResponseEntity.ok(response);
    }

    /**
     * 좌석 상세 조회
     */
    @GetMapping("/seats/{seatId}")
    public ResponseEntity<SeatResponse> getSeatById(@PathVariable Long seatId) {
        SeatResponse response = seatService.getSeatById(seatId);
        return ResponseEntity.ok(response);
    }

    /**
     * 좌석 일괄 생성 (관리자)
     */
    @PostMapping("/events/{eventId}/seats/bulk")
    public ResponseEntity<List<SeatResponse>> createSeats(
            @PathVariable Long eventId,
            @Valid @RequestBody SeatBulkCreateRequest request
    ) {
        List<SeatResponse> response = seatService.createSeats(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 좌석 임시 선점 (분산락, JWT에서 userId 추출 — IDOR 방지)
     */
    @PostMapping("/seats/{seatId}/hold")
    public ResponseEntity<SeatResponse> holdSeat(
            @PathVariable Long seatId,
            @AuthenticationPrincipal Long userId
    ) {
        SeatResponse response = seatService.holdSeat(seatId, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 좌석 선점 해제 (JWT에서 userId 추출 — IDOR 방지)
     */
    @DeleteMapping("/seats/{seatId}/hold")
    public ResponseEntity<SeatResponse> releaseSeat(
            @PathVariable Long seatId,
            @AuthenticationPrincipal Long userId
    ) {
        SeatResponse response = seatService.releaseSeat(seatId, userId);
        return ResponseEntity.ok(response);
    }
}
