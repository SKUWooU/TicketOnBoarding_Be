package com.onticket.concert.repository;
import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface ConcertTimeRepository extends JpaRepository<ConcertTime,Long> {
    List<ConcertTime> findByConcert_ConcertId(String concertId);
    Optional<ConcertTime> findByDateAndStartTimeAndConcert(LocalDate date, LocalTime startTime, Concert concert);
    @Query("SELECT ct.id FROM ConcertTime ct WHERE ct.concert.concertId = :concertId")
    List<Long> findConcertTimeIdsByConcertId(@Param("concertId") String concertId);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ConcertTime ct
            SET ct.seatAmount = ct.seatAmount - :seatCount
            WHERE ct.id = :concertTimeId
              AND ct.seatAmount >= :seatCount
            """)
    int decreaseSeatAmountIfAvailable(
            @Param("concertTimeId") Long concertTimeId,
            @Param("seatCount") int seatCount
    );

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ConcertTime ct
            SET ct.seatAmount = ct.seatAmount + 1
            WHERE ct.id = :concertTimeId
            """)
    int increaseSeatAmount(@Param("concertTimeId") Long concertTimeId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ConcertTime ct WHERE ct.concert.concertId = :concertId")
    void deleteByConcertId(@Param("concertId") String concertId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Seat s WHERE s.concertTime.id = :concertTimeId")
    void deleteSeatsByConcertTimeId(@Param("concertTimeId") Long concertTimeId);

}
