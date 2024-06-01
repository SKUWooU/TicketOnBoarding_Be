package com.onticket.concert.repository;
import com.onticket.concert.domain.Concert;
import com.onticket.concert.domain.ConcertTime;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface ConcertTimeRepository extends JpaRepository<ConcertTime,Long> {
    List<ConcertTime> findByConcert_ConcertId(String concertId);
    Optional<ConcertTime> findByDateAndStartTimeAndConcert(LocalDate date, LocalTime startTime, Concert concert);
}
