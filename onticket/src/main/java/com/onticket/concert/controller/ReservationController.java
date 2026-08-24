package com.onticket.concert.controller;

import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.service.ReservationIdempotencyService;
import com.onticket.concert.service.VerifiedReservationService;
import com.onticket.user.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;


@RequiredArgsConstructor
@RestController
public class ReservationController {
    private final ReservationIdempotencyService reservationIdempotencyService;

    private final VerifiedReservationService verifiedReservationService;

    private final JwtUtil jwtUtil;

    //공연예약
    @PostMapping("/main/detail/{concertId}/reservation")
    public ResponseEntity<?> setReservation(
            @CookieValue(value = "accessToken", required = false) String token,
            @PathVariable("concertId") String concertId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReservRequest reservRequest
    ) throws Exception {
        if (token != null && jwtUtil.validateToken(token)) {
            String username=jwtUtil.getUsernameFromToken(token);
            LocalDateTime reservationCreatedAt = reservationIdempotencyService.reserve(
                    username,
                    concertId,
                    reservRequest,
                    idempotencyKey
            );
            return ResponseEntity.ok().body(reservationCreatedAt);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
        }

    }

    @PostMapping("/main/detail/{concertId}/verified-reservation")
    public ResponseEntity<?> setVerifiedReservation(
            @CookieValue(value = "accessToken", required = false) String token,
            @PathVariable("concertId") String concertId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody VerifiedReservRequest reservRequest
    ) throws Exception {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            LocalDateTime reservationCreatedAt = verifiedReservationService.reserve(
                    username,
                    concertId,
                    reservRequest,
                    idempotencyKey
            );
            return ResponseEntity.ok().body(reservationCreatedAt);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

}
