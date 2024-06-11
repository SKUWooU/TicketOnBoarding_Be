package com.onticket.concert.repository;

import com.onticket.concert.domain.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByConcertTimeId(Long concertTimeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.concertTime.id = :concertTimeId AND s.seatNumber = :seatNumber")
    Optional<Seat> findByConcertTimeIdAndSeatNumberWithLock(@Param("concertTimeId") Long concertTimeId, @Param("seatNumber") String seatNumber);
}
