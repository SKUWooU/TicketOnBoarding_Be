package com.onticket.concert.repository;

import com.onticket.concert.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByUsernameAndIdempotencyKey(String username, String idempotencyKey);
}
