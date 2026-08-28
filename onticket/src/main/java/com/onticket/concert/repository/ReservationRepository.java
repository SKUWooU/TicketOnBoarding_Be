package com.onticket.concert.repository;

import com.onticket.concert.domain.Reservation;
import com.onticket.concert.domain.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    //유저아이디로 예약리스트 찾기
    Optional<List<Reservation>> findByUsername(String username);
    //상태값으로 조회
    List<Reservation> findByStatus(ReservationStatus status);

    long countByConcertTimeId(Long concertTimeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :reservationId")
    Optional<Reservation> findByIdWithLock(@Param("reservationId") Long reservationId);
}
