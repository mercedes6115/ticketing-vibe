package com.ticketing.controller;

import com.ticketing.dto.booking.BookingAcceptedResponse;
import com.ticketing.dto.booking.BookingCreateRequest;
import com.ticketing.dto.booking.BookingListResponse;
import com.ticketing.dto.booking.BookingResponse;
import com.ticketing.dto.booking.BookingStatusResponse;
import com.ticketing.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /**
     * 예매 요청 (비동기)
     * 202 Accepted + { bookingNo, status: "PROCESSING" }
     * 프론트엔드는 /status/{bookingNo}를 폴링해 완료 여부를 확인한다.
     */
    @PostMapping
    public ResponseEntity<BookingAcceptedResponse> createBooking(
            @Valid @RequestBody BookingCreateRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        BookingAcceptedResponse response = bookingService.createBooking(request, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 예매 처리 상태 폴링
     * Redis booking:status:{bookingNo} 조회 → PROCESSING | CONFIRMED | FAILED
     */
    @GetMapping("/status/{bookingNo}")
    public ResponseEntity<BookingStatusResponse> getBookingStatus(@PathVariable String bookingNo) {
        BookingStatusResponse response = bookingService.getBookingStatus(bookingNo);
        return ResponseEntity.ok(response);
    }

    /**
     * 예매 상세 조회 (본인 예매만)
     */
    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> getBooking(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal Long userId
    ) {
        BookingResponse response = bookingService.getBooking(bookingId, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 예매 번호로 조회 (본인 예매만 — IDOR 방지)
     */
    @GetMapping("/no/{bookingNo}")
    public ResponseEntity<BookingResponse> getBookingByNo(
            @PathVariable String bookingNo,
            @AuthenticationPrincipal Long userId
    ) {
        BookingResponse response = bookingService.getBookingByNo(bookingNo, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 내 예매 목록 조회 (JWT에서 userId 추출 — IDOR 방지)
     */
    @GetMapping("/my")
    public ResponseEntity<Page<BookingListResponse>> getMyBookings(
            @AuthenticationPrincipal Long userId,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<BookingListResponse> response = bookingService.getBookingsByUserId(userId, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * 전체 예매 목록 조회 (관리자용) — ?eventId=N 으로 이벤트 필터링 가능
     */
    @GetMapping("/admin")
    public ResponseEntity<Page<BookingListResponse>> getAllBookings(
            @RequestParam(required = false) Long eventId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<BookingListResponse> response = bookingService.getAllBookings(eventId, pageable);
        return ResponseEntity.ok(response);
    }

    /**
     * 예매 취소 (동기, JWT에서 userId 추출 — IDOR 방지)
     */
    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal Long userId
    ) {
        BookingResponse response = bookingService.cancelBooking(bookingId, userId);
        return ResponseEntity.ok(response);
    }
}
