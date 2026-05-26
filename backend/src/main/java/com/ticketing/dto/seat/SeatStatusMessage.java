package com.ticketing.dto.seat;

import com.ticketing.entity.enums.SeatStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatStatusMessage {

    private Long seatId;
    private Long eventId;
    private SeatStatus status;
    private Long holdUserId;  // 선점한 사용자 (HOLD 상태일 때)
    private String message;

    public static SeatStatusMessage hold(Long seatId, Long eventId, Long userId) {
        return SeatStatusMessage.builder()
                .seatId(seatId)
                .eventId(eventId)
                .status(SeatStatus.HOLD)
                .holdUserId(userId)
                .message("좌석이 선점되었습니다")
                .build();
    }

    public static SeatStatusMessage release(Long seatId, Long eventId) {
        return SeatStatusMessage.builder()
                .seatId(seatId)
                .eventId(eventId)
                .status(SeatStatus.AVAILABLE)
                .holdUserId(null)
                .message("좌석이 해제되었습니다")
                .build();
    }

    public static SeatStatusMessage sold(Long seatId, Long eventId) {
        return SeatStatusMessage.builder()
                .seatId(seatId)
                .eventId(eventId)
                .status(SeatStatus.SOLD)
                .holdUserId(null)
                .message("좌석이 판매되었습니다")
                .build();
    }
}
