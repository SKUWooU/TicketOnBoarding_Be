package com.onticket.user.controller;

import com.onticket.concert.service.SeatReservationService;
import com.onticket.user.jwt.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MypageControllerTest {

    private static final String TOKEN = "valid-token";
    private static final String USERNAME = "reservation-owner";
    private static final Long RESERVATION_ID = 1L;

    @Mock
    private SeatReservationService seatReservationService;

    @Mock
    private JwtUtil jwtUtil;

    private MypageController mypageController;

    @BeforeEach
    void setUp() {
        mypageController = new MypageController(seatReservationService, jwtUtil);
    }

    @Test
    void cancellationRequestDelegatesAuthenticatedOwnerToService() {
        when(jwtUtil.validateToken(TOKEN)).thenReturn(true);
        when(jwtUtil.getUsernameFromToken(TOKEN)).thenReturn(USERNAME);

        ResponseEntity<?> response = mypageController.cancelReservation(TOKEN, RESERVATION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("취소신청이 완료되었습니다.");
        verify(seatReservationService).requestCancellation(USERNAME, RESERVATION_ID);
    }

    @Test
    void cancellationRequestReturnsServiceValidationMessageAsBadRequest() {
        when(jwtUtil.validateToken(TOKEN)).thenReturn(true);
        when(jwtUtil.getUsernameFromToken(TOKEN)).thenReturn(USERNAME);
        doThrow(new IllegalArgumentException("예약정보와 다른 사용자입니다."))
                .when(seatReservationService)
                .requestCancellation(USERNAME, RESERVATION_ID);

        ResponseEntity<?> response = mypageController.cancelReservation(TOKEN, RESERVATION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("예약정보와 다른 사용자입니다.");
    }

    @Test
    void cancellationRequestWithoutTokenRemainsUnauthorized() {
        ResponseEntity<?> response = mypageController.cancelReservation(null, RESERVATION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo("로그인이 필요한 서비스입니다.");
        verifyNoInteractions(seatReservationService);
    }
}
