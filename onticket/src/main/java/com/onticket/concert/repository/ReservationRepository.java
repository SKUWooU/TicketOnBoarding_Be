package com.onticket.concert.repository;

import com.onticket.concert.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    //유저아이디로 예약리스트 찾기
    Optional<List<Reservation>> findByUsername(String username);
    //상태값으로 조회
    List<Reservation> findByStatus(String status);
}