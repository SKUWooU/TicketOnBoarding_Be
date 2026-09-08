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

    @Query("""
            SELECT s.layoutSectionCode AS sectionCode,
                   s.layoutSectionName AS sectionName,
                   s.layoutSectionOrder AS sectionOrder,
                   COUNT(s) AS totalSeats,
                   SUM(CASE WHEN s.reserved = true THEN 1 ELSE 0 END) AS reservedSeats,
                   SUM(CASE WHEN s.reserved = false
                                  AND s.heldBy IS NOT NULL
                                  AND s.heldUntil > :now
                            THEN 1 ELSE 0 END) AS heldSeats
            FROM Seat s
            WHERE s.concertTime.id = :concertTimeId
              AND s.layoutSectionCode IS NOT NULL
            GROUP BY s.layoutSectionCode, s.layoutSectionName, s.layoutSectionOrder
            ORDER BY s.layoutSectionOrder
            """)
    List<SeatSectionInventoryProjection> summarizeLayoutSections(
            @Param("concertTimeId") Long concertTimeId,
            @Param("now") LocalDateTime now
    );

    List<Seat> findByConcertTimeIdAndLayoutSectionCodeOrderByLayoutRowOrderAscLayoutSeatIndexAscIdAsc(
            Long concertTimeId,
            String layoutSectionCode
    );

    interface SeatSectionInventoryProjection {
        String getSectionCode();
        String getSectionName();
        Integer getSectionOrder();
        Long getTotalSeats();
        Long getReservedSeats();
        Long getHeldSeats();
    }
}
