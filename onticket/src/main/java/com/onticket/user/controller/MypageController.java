package com.onticket.user.controller;

import com.onticket.concert.domain.Reservation;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.service.ReviewService;
import com.onticket.concert.service.SeatReservationService;
import com.onticket.user.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@RestController
public class MypageController {
    private final SeatReservationService seatReservationService;
    private final ReservationRepository reservationRepository;
    private final JwtUtil jwtUtil;

    //예약조회페이지
    @GetMapping("/mypage/reservationlist")
    public ResponseEntity<?> getReservation(@CookieValue(value = "accessToken", required = false) String token) throws Exception {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            List<Reservation> reservationList = seatReservationService.getPersonalReservation(username);
            return ResponseEntity.ok(reservationList);
        } else return ResponseEntity.badRequest().body("로그인이 필요한 서비스입니다.");
    }
    //공연취소신청
    @PostMapping("/mypage/cancel/reservation/{reservationId}")
    public ResponseEntity<?> cancelReservation(@CookieValue(value = "accessToken", required = false) String token, @PathVariable("reservationId") Long reservationId) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            Optional<Reservation> reservation = reservationRepository.findById(reservationId);
            if (reservation.isPresent()) {
                Reservation reservation1 = reservation.get();
                if(reservation1.getUsername().equals(username)){
                    return ResponseEntity.badRequest().body("예약정보와 다른 사용자입니다.");
                }
                reservation1.setStatus("취소신청");
                reservationRepository.save(reservation1);
                return ResponseEntity.ok().body("취소신청이 완료되었습니다.");
            } else return ResponseEntity.badRequest().body("해당하는 예약이 없습니다.");

        } else return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }
}
