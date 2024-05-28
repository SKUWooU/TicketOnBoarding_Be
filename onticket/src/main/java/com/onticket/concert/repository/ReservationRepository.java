package com.onticket.concert.repository;

import com.onticket.concert.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByUserId(String userId);
    List<Reservation> findByConcertTimeId(Long concertTimeId);
}