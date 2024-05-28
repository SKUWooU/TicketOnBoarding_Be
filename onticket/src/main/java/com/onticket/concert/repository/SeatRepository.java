package com.onticket.concert.repository;

import com.onticket.concert.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByConcertTimeId(Long concertTimeId);
}
