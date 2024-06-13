package com.onticket.concert.controller;

import com.onticket.concert.domain.Reservation;
import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.service.SeatReservationService;
import com.onticket.user.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Optional;

@RequiredArgsConstructor
@RestController
public class ReservationController {
    private SeatReservationService seatReservationService;
    private ReservationRepository reservationRepository;
    private JwtUtil jwtUtil;

    //공연예약
    @PostMapping("/main/detail/{concertId}/reservation")
    public ResponseEntity<?> setReservation(@CookieValue(value = "accessToken", required = false) String token,@PathVariable("concertId") String concertId, @RequestBody ReservRequest reservRequest) throws Exception {
        if (token != null && jwtUtil.validateToken(token)) {
            String username=jwtUtil.getUsernameFromToken(token);
            seatReservationService.reserveSeat(username,concertId,reservRequest);
            LocalDateTime now = LocalDateTime.now();
            return ResponseEntity.ok().body(now);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
        }

    }

    //공연취소신청
    @PostMapping("/cancel/reservation/{reservationId}")
    public ResponseEntity<?> cancelReservation(@CookieValue(value = "accessToken", required = false) String token, @PathVariable("reservationId") Long reservationId) {
        if (token != null && jwtUtil.validateToken(token)) {
            Optional<Reservation> reservation = reservationRepository.findById(reservationId);
            if (reservation.isPresent()) {
                Reservation reservation1 = reservation.get();
                reservation1.setStatus("취소신청");
                reservationRepository.save(reservation1);
                return ResponseEntity.ok().body("취소신청이 완료되었습니다.");
            } else return ResponseEntity.badRequest().body("해당하는 예약이 없습니다.");

        } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }


}
