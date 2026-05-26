package com.ticketing.repository;

import com.ticketing.entity.Seat;
import com.ticketing.entity.enums.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventId(Long eventId);

    List<Seat> findByEventIdAndStatus(Long eventId, SeatStatus status);

    @Query("SELECT s FROM Seat s WHERE s.event.id = :eventId AND s.section = :section")
    List<Seat> findByEventIdAndSection(@Param("eventId") Long eventId, @Param("section") String section);

    @Query("SELECT s FROM Seat s JOIN FETCH s.event WHERE s.id = :id")
    Optional<Seat> findByIdWithEvent(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s JOIN FETCH s.event WHERE s.id = :id")
    Optional<Seat> findByIdWithEventForUpdate(@Param("id") Long id);

    @Query("SELECT COUNT(s) FROM Seat s WHERE s.event.id = :eventId AND s.status = :status")
    long countByEventIdAndStatus(@Param("eventId") Long eventId, @Param("status") SeatStatus status);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Seat s SET s.status = :status WHERE s.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") SeatStatus status);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Seat s SET s.status = 'AVAILABLE' WHERE s.id = :id AND s.status = 'HOLD'")
    int releaseHoldSeat(@Param("id") Long id);
}
