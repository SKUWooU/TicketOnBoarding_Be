package com.onticket.concert.controller;

import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.dto.CheckoutResponse;
import com.onticket.concert.dto.SeatHoldRequest;
import com.onticket.concert.dto.SeatHoldResponse;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.service.ReservationIdempotencyService;
import com.onticket.concert.service.CheckoutService;
import com.onticket.concert.service.CheckoutVerifiedReservationService;
import com.onticket.concert.service.SeatHoldService;
import com.onticket.concert.service.VerifiedReservationService;
import com.onticket.user.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;


@RequiredArgsConstructor
@RestController
public class ReservationController {
    private final ReservationIdempotencyService reservationIdempotencyService;

    private final VerifiedReservationService verifiedReservationService;

    private final SeatHoldService seatHoldService;

    private final CheckoutService checkoutService;

    private final CheckoutVerifiedReservationService checkoutVerifiedReservationService;

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

    @PostMapping(
            value = "/main/detail/{concertId}/checkouts",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> prepareCheckout(
            @CookieValue(value = "accessToken", required = false) String token,
            @PathVariable("concertId") String concertId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CheckoutRequest checkoutRequest
    ) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            CheckoutResponse response = checkoutService.prepare(
                    username,
                    concertId,
                    checkoutRequest,
                    idempotencyKey
            );
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    @PostMapping("/main/detail/{concertId}/checkouts/{merchantUid}/verified-reservation")
    public ResponseEntity<?> setCheckoutVerifiedReservation(
            @CookieValue(value = "accessToken", required = false) String token,
            @PathVariable("concertId") String concertId,
            @PathVariable("merchantUid") String merchantUid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody VerifiedReservRequest reservRequest
    ) throws Exception {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            reservRequest.setMerchantUid(merchantUid);
            LocalDateTime reservationCreatedAt = checkoutVerifiedReservationService.reserve(
                    username,
                    concertId,
                    reservRequest,
                    idempotencyKey
            );
            return ResponseEntity.ok().body(reservationCreatedAt);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    @PostMapping("/main/detail/{concertId}/seat-holds")
    public ResponseEntity<?> holdSeats(
            @CookieValue(value = "accessToken", required = false) String token,
            @PathVariable("concertId") String concertId,
            @RequestBody SeatHoldRequest request
    ) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            SeatHoldResponse response = seatHoldService.hold(username, concertId, request);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    @DeleteMapping("/main/detail/{concertId}/seat-holds")
    public ResponseEntity<?> releaseSeats(
            @CookieValue(value = "accessToken", required = false) String token,
            @PathVariable("concertId") String concertId,
            @RequestBody SeatHoldRequest request
    ) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            seatHoldService.release(username, concertId, request);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

}
