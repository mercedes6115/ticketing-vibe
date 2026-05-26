package com.ticketing.repository;

import com.ticketing.entity.Booking;
import com.ticketing.entity.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingNo(String bookingNo);

    Page<Booking> findByUserId(Long userId, Pageable pageable);

    Page<Booking> findByUserIdAndStatus(Long userId, BookingStatus status, Pageable pageable);

    List<Booking> findByEventId(Long eventId);

    Page<Booking> findByEventId(Long eventId, Pageable pageable);

    List<Booking> findByEventIdAndStatus(Long eventId, BookingStatus status);

    boolean existsBySeatIdAndStatusIn(Long seatId, List<BookingStatus> statuses);

    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.event " +
           "JOIN FETCH b.seat " +
           "WHERE b.id = :id")
    Optional<Booking> findByIdWithEventAndSeat(@Param("id") Long id);

    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.event " +
           "JOIN FETCH b.seat " +
           "LEFT JOIN FETCH b.payment " +
           "WHERE b.user.id = :userId")
    List<Booking> findByUserIdWithDetails(@Param("userId") Long userId);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.event.id = :eventId AND b.status = :status")
    long countByEventIdAndStatus(@Param("eventId") Long eventId, @Param("status") BookingStatus status);
}
