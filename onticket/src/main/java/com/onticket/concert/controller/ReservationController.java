package com.onticket.concert.controller;

import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.dto.CheckoutResponse;
import com.onticket.concert.dto.SeatHoldRequest;
import com.onticket.concert.dto.SeatHoldResponse;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.service.CheckoutService;
import com.onticket.concert.service.CheckoutCancellationService;
import com.onticket.concert.service.CheckoutVerifiedReservationService;
import com.onticket.concert.service.SeatHoldService;
import com.onticket.concert.service.SeatReservationConflictException;
import com.onticket.concert.service.VerifiedReservationService;
import com.onticket.user.jwt.JwtUtil;
import com.onticket.admission.AdmissionAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;


@RequiredArgsConstructor
@RestController
public class ReservationController {
    public static final String SEAT_CONFLICT_HEADER = "X-Reservation-Conflict";
    public static final String SEAT_UNAVAILABLE = "SEAT_UNAVAILABLE";

    @ExceptionHandler(SeatReservationConflictException.class)
    public ResponseEntity<Void> seatReservationConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(SEAT_CONFLICT_HEADER, SEAT_UNAVAILABLE)
                .build();
    }

    private final VerifiedReservationService verifiedReservationService;

    private final SeatHoldService seatHoldService;

    private final CheckoutService checkoutService;

    private final CheckoutCancellationService checkoutCancellationService;

    private final CheckoutVerifiedReservationService checkoutVerifiedReservationService;

    private final JwtUtil jwtUtil;

    private final AdmissionAccess admissionAccess;

    // 결제 검증 없는 기존 예약 경로는 본문을 해석하지 않고 폐쇄한다.
    @PostMapping(value = "/main/detail/{concertId}/reservation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setReservation(
            @CookieValue(value = "accessToken", required = false) String token,
            @PathVariable("concertId") String concertId
    ) {
        if (token != null && jwtUtil.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "code", "LEGACY_RESERVATION_DISABLED",
                    "message", "기존 예약 API는 종료되었습니다. Checkout 결제 검증 경로를 이용해주세요."
            ));
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
            admissionAccess.rejectDirectReservation(reservRequest.getConcertTimeId());
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
            @RequestHeader(value = AdmissionAccess.TOKEN_HEADER, required = false) String admissionToken,
            @RequestBody CheckoutRequest checkoutRequest
    ) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            CheckoutResponse response;
            if (admissionAccess.protectedTime(checkoutRequest.getConcertTimeId())) {
                // Replaying an existing Checkout must not depend on a fresh Redis lease.
                response = checkoutService.prepare(username, concertId, checkoutRequest, idempotencyKey,
                        () -> admissionAccess.requireAdmission(username, concertId,
                                checkoutRequest.getConcertTimeId(), admissionToken));
            } else {
                response = checkoutService.prepare(username, concertId, checkoutRequest, idempotencyKey);
            }
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요한 서비스입니다.");
    }

    @DeleteMapping(
            value = "/main/detail/{concertId}/checkouts/{merchantUid}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> cancelCheckout(
            @CookieValue(value = "accessToken", required = false) String token,
            @PathVariable("concertId") String concertId,
            @PathVariable("merchantUid") String merchantUid
    ) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            return ResponseEntity.ok(checkoutCancellationService.cancel(
                    username,
                    concertId,
                    merchantUid
            ));
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
            @RequestHeader(value = AdmissionAccess.TOKEN_HEADER, required = false) String admissionToken,
            @RequestBody SeatHoldRequest request
    ) {
        if (token != null && jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            admissionAccess.requireAdmission(username, concertId, request.getConcertTimeId(), admissionToken);
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
