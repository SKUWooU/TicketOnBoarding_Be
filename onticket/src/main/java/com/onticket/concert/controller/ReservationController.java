package com.onticket.concert.controller;

import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.service.SeatReservationService;
import com.onticket.user.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;


@RequiredArgsConstructor
@RestController
public class ReservationController {
    private final SeatReservationService seatReservationService;

    private final JwtUtil jwtUtil;

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

}
