package com.onticket.user.controller;

import com.onticket.concert.domain.Reservation;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.service.ReviewService;
import com.onticket.concert.service.SeatReservationService;
import com.onticket.user.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class MypageController {
    private final SeatReservationService seatReservationService;
    private final ReviewService reviewService;
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
}
