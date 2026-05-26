package com.ticketing.repository;

import com.ticketing.entity.Event;
import com.ticketing.entity.enums.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Page<Event> findByStatus(EventStatus status, Pageable pageable);

    Page<Event> findByStatusIn(List<EventStatus> statuses, Pageable pageable);

    @Query("SELECT e FROM Event e WHERE e.openAt <= :now AND e.status = 'SCHEDULED'")
    List<Event> findEventsToOpen(@Param("now") LocalDateTime now);

    @Query("SELECT e FROM Event e WHERE e.startAt <= :now AND e.status = 'OPEN'")
    List<Event> findEventsToClose(@Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    Optional<Event> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT e FROM Event e LEFT JOIN FETCH e.seats WHERE e.id = :id")
    Optional<Event> findByIdWithSeats(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Event e SET e.availableSeats = e.availableSeats - 1 WHERE e.id = :id AND e.availableSeats > 0")
    int decreaseAvailableSeats(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Event e SET e.availableSeats = e.availableSeats + 1 WHERE e.id = :id AND e.availableSeats < e.totalSeats")
    int increaseAvailableSeats(@Param("id") Long id);
}
