package com.ticketing.service;

import com.ticketing.dto.booking.BookingResponse;
import com.ticketing.dto.kafka.BookingEvent;
import com.ticketing.dto.seat.SeatStatusMessage;
import com.ticketing.entity.Booking;
import com.ticketing.entity.Seat;
import com.ticketing.entity.enums.BookingProcessStatus;
import com.ticketing.exception.ForbiddenException;
import com.ticketing.exception.NotFoundException;
import com.ticketing.repository.BookingRepository;
import com.ticketing.repository.SeatRepository;
import com.ticketing.util.TransactionUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingCancellationService {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final BookingEventProducer bookingEventProducer;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public BookingResponse cancelBooking(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithEventAndSeat(bookingId)
                .orElseThrow(() -> NotFoundException.booking(bookingId));

        validateOwnership(booking, userId);
        cancelBookingInternal(booking);

        return BookingResponse.from(booking);
    }

    @Transactional
    public Booking cancelBookingForRefund(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findByIdWithEventAndSeat(bookingId)
                .orElseThrow(() -> NotFoundException.booking(bookingId));

        validateOwnership(booking, userId);
        cancelBookingInternal(booking);

        return booking;
    }

    private void validateOwnership(Booking booking, Long userId) {
        if (!booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("본인 예매만 취소할 수 있습니다.");
        }
    }

    private void cancelBookingInternal(Booking booking) {
        booking.cancel();

        if (booking.getPayment() != null && booking.getPayment().isSuccess()) {
            booking.getPayment().refund();
        }

        Seat seat = booking.getSeat();
        seat.release();
        seatRepository.save(seat);

        log.info("Booking cancelled: bookingNo={}, userId={}", booking.getBookingNo(), booking.getUser().getId());

        long seatId = seat.getId();
        long eventId = booking.getEvent().getId();
        BookingEvent cancelEvent = new BookingEvent(
                booking.getId(), booking.getBookingNo(),
                booking.getUser().getId(), booking.getUser().getNickname(),
                booking.getEvent().getId(), booking.getEvent().getTitle(), BookingProcessStatus.CANCELLED,
                (long) booking.getSeat().getPrice(), LocalDateTime.now()
        );
        TransactionUtils.afterCommit(() -> {
            messagingTemplate.convertAndSend(
                    "/topic/events/" + eventId + "/seats",
                    SeatStatusMessage.release(seatId, eventId)
            );
            bookingEventProducer.send(cancelEvent);
        });
    }
}
