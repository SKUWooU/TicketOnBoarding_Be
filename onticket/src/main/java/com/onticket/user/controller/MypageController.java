package com.onticket.user.controller;

import com.onticket.concert.domain.Reservation;
import com.onticket.concert.repository.ReservationRepository;
import com.onticket.concert.service.ReviewService;
import com.onticket.concert.service.SeatReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class MypageController {
    private final SeatReservationService seatReservationService;
    private final ReviewService reviewService;


    //예약조회페이지
    @GetMapping("/mypage/reservationlist")
    public ResponseEntity<List<Reservation>> getReservation() throws Exception {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        List<Reservation> reservationList=seatReservationService.getPersonalReservation(username);
        return ResponseEntity.ok(reservationList);
    }
}
