package com.ticketing.repository;

import com.ticketing.entity.Payment;
import com.ticketing.entity.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<Payment> findByStatus(PaymentStatus status);

    @Query("SELECT p FROM Payment p JOIN FETCH p.booking WHERE p.id = :id")
    Optional<Payment> findByIdWithBooking(@Param("id") Long id);

    @Query("SELECT SUM(p.amount) FROM Payment p " +
           "WHERE p.booking.event.id = :eventId AND p.status = 'SUCCESS'")
    Long sumAmountByEventId(@Param("eventId") Long eventId);
}
