package com.onticket.concert.repository;

import com.onticket.concert.domain.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByConcertTimeId(Long concertTimeId);

    long countByConcertTimeId(Long concertTimeId);

    long countByConcertTimeIdAndReservedTrue(Long concertTimeId);

    @Query("""
            SELECT COUNT(s)
            FROM Seat s
            WHERE s.concertTime.id = :concertTimeId
              AND s.heldBy IS NOT NULL
              AND s.heldUntil IS NOT NULL
            """)
    long countHoldRows(@Param("concertTimeId") Long concertTimeId);

    @Query("""
            SELECT COUNT(s)
            FROM Seat s
            WHERE s.concertTime.id = :concertTimeId
              AND s.reserved = false
              AND s.heldBy IS NOT NULL
              AND s.heldUntil > :now
            """)
    long countActiveHolds(
            @Param("concertTimeId") Long concertTimeId,
            @Param("now") LocalDateTime now
    );

    @Query("""
            SELECT COUNT(s)
            FROM Seat s
            WHERE s.concertTime.id = :concertTimeId
              AND ((s.heldBy IS NULL AND s.heldUntil IS NOT NULL)
                OR (s.heldBy IS NOT NULL AND s.heldUntil IS NULL))
            """)
    long countPartialHoldStates(@Param("concertTimeId") Long concertTimeId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Seat s
            SET s.heldBy = null,
                s.heldUntil = null
            WHERE s.concertTime.id = :concertTimeId
            """)
    int clearHoldsByConcertTimeId(@Param("concertTimeId") Long concertTimeId);

    @Query("SELECT s FROM Seat s WHERE s.concertTime.id = :concertTimeId AND s.seatNumber = :seatNumber")
    Seat findByConcertTimeAndSeatNumber(@Param("concertTimeId")Long concertTimeId, @Param("seatNumber") String seatNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.concertTime.id = :concertTimeId AND s.seatNumber = :seatNumber")
    Optional<Seat> findByConcertTimeIdAndSeatNumberWithLock(@Param("concertTimeId") Long concertTimeId, @Param("seatNumber") String seatNumber);
}
