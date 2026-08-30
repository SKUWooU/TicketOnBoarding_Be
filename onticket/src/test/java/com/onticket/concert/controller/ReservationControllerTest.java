package com.onticket.concert.controller;

import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.dto.SeatHoldRequest;
import com.onticket.concert.dto.SeatHoldResponse;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.service.IdempotencyKeyConflictException;
import com.onticket.concert.service.InvalidIdempotencyKeyException;
import com.onticket.concert.service.InvalidSeatHoldRequestException;
import com.onticket.concert.service.PaymentVerificationUnavailableException;
import com.onticket.concert.service.ReservationIdempotencyService;
import com.onticket.concert.service.SeatHoldConflictException;
import com.onticket.concert.service.SeatHoldService;
import com.onticket.concert.service.SeatReservationConflictException;
import com.onticket.concert.service.VerifiedReservationService;
import com.onticket.user.jwt.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReservationControllerTest {

    private static final String TOKEN = "valid-token";
    private static final String USERNAME = "reservation-user";
    private static final String CONCERT_ID = "CONCERT-1";
    private static final String REQUEST_BODY = """
            {
              "concertTimeId": 1,
              "seatNumberList": ["A1"]
            }
            """;

    @Mock
    private ReservationIdempotencyService reservationIdempotencyService;

    @Mock
    private VerifiedReservationService verifiedReservationService;

    @Mock
    private SeatHoldService seatHoldService;

    @Mock
    private JwtUtil jwtUtil;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ReservationController(
                        reservationIdempotencyService,
                        verifiedReservationService,
                        seatHoldService,
                        jwtUtil
                )
        ).build();
        lenient().when(jwtUtil.validateToken(TOKEN)).thenReturn(true);
        lenient().when(jwtUtil.getUsernameFromToken(TOKEN)).thenReturn(USERNAME);
    }

    @Test
    void forwardsIdempotencyKeyWithoutChangingSuccessStatus() throws Exception {
        String idempotencyKey = "booking-key";
        when(reservationIdempotencyService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(ReservRequest.class),
                eq(idempotencyKey)
        )).thenReturn(LocalDateTime.of(2030, 1, 1, 12, 0));

        mockMvc.perform(post("/main/detail/{concertId}/reservation", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk());

        verify(reservationIdempotencyService).reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(ReservRequest.class),
                eq(idempotencyKey)
        );
    }

    @Test
    void payloadConflictReturnsHttp409() throws Exception {
        when(reservationIdempotencyService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(ReservRequest.class),
                eq("reused-key")
        )).thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(post("/main/detail/{concertId}/reservation", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", "reused-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidIdempotencyKeyReturnsHttp400() throws Exception {
        when(reservationIdempotencyService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(ReservRequest.class),
                eq(" ")
        )).thenThrow(new InvalidIdempotencyKeyException("멱등 키는 비어 있을 수 없습니다."));

        mockMvc.perform(post("/main/detail/{concertId}/reservation", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", " ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void seatContentionReturnsHttp409() throws Exception {
        when(reservationIdempotencyService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(ReservRequest.class),
                eq("contention-key")
        )).thenThrow(new SeatReservationConflictException("이미 예약된 좌석입니다."));

        mockMvc.perform(post("/main/detail/{concertId}/reservation", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", "contention-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void verifiedReservationForwardsPaymentAndRequiredIdempotencyContract() throws Exception {
        String requestBody = """
                {
                  "concertTimeId": 1,
                  "seatNumberList": ["A1"],
                  "paymentId": "payment-1"
                }
                """;
        when(verifiedReservationService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(VerifiedReservRequest.class),
                eq("verified-key")
        )).thenReturn(LocalDateTime.of(2030, 1, 1, 12, 0));

        mockMvc.perform(post("/main/detail/{concertId}/verified-reservation", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", "verified-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        verify(verifiedReservationService).reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(VerifiedReservRequest.class),
                eq("verified-key")
        );
    }

    @Test
    void verifiedReservationWithoutIdempotencyKeyReturnsHttp400() throws Exception {
        when(verifiedReservationService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(VerifiedReservRequest.class),
                eq(null)
        )).thenThrow(new InvalidIdempotencyKeyException("검증 예약에는 멱등 키가 필요합니다."));

        mockMvc.perform(post("/main/detail/{concertId}/verified-reservation", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "concertTimeId": 1,
                                  "seatNumberList": ["A1"],
                                  "paymentId": "payment-1"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifiedSeatContentionReturnsHttp409() throws Exception {
        when(verifiedReservationService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(VerifiedReservRequest.class),
                eq("verified-contention-key")
        )).thenThrow(new SeatReservationConflictException("잔여 좌석이 부족합니다."));

        mockMvc.perform(post("/main/detail/{concertId}/verified-reservation", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", "verified-contention-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "concertTimeId": 1,
                                  "seatNumberList": ["A1"],
                                  "paymentId": "payment-1"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void verifiedReservationWithoutPaymentAdapterReturnsHttp503() throws Exception {
        when(verifiedReservationService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(VerifiedReservRequest.class),
                eq("unavailable-key")
        )).thenThrow(new PaymentVerificationUnavailableException());

        mockMvc.perform(post("/main/detail/{concertId}/verified-reservation", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", "unavailable-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "concertTimeId": 1,
                                  "seatNumberList": ["A1"],
                                  "paymentId": "payment-1"
                                }
                                """))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void authenticatedUserCanHoldSeats() throws Exception {
        LocalDateTime expiresAt = LocalDateTime.of(2030, 1, 1, 12, 5);
        when(seatHoldService.hold(eq(USERNAME), eq(CONCERT_ID), any(SeatHoldRequest.class)))
                .thenReturn(new SeatHoldResponse(List.of(
                        new SeatHoldResponse.HeldSeat("A1", expiresAt)
                )));

        mockMvc.perform(post("/main/detail/{concertId}/seat-holds", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[0].seatNumber").value("A1"))
                .andExpect(jsonPath("$.seats[0].expiresAt").exists());

        verify(seatHoldService).hold(eq(USERNAME), eq(CONCERT_ID), any(SeatHoldRequest.class));
    }

    @Test
    void seatHeldByAnotherUserReturnsHttp409() throws Exception {
        when(seatHoldService.hold(eq(USERNAME), eq(CONCERT_ID), any(SeatHoldRequest.class)))
                .thenThrow(new SeatHoldConflictException("다른 사용자가 임시 점유한 좌석입니다."));

        mockMvc.perform(post("/main/detail/{concertId}/seat-holds", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidSeatHoldRequestReturnsHttp400() throws Exception {
        when(seatHoldService.hold(eq(USERNAME), eq(CONCERT_ID), any(SeatHoldRequest.class)))
                .thenThrow(new InvalidSeatHoldRequestException("좌석을 한 개 이상 선택해야 합니다."));

        mockMvc.perform(post("/main/detail/{concertId}/seat-holds", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authenticatedOwnerCanReleaseSeats() throws Exception {
        mockMvc.perform(delete("/main/detail/{concertId}/seat-holds", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isNoContent());

        verify(seatHoldService).release(eq(USERNAME), eq(CONCERT_ID), any(SeatHoldRequest.class));
    }

    @Test
    void seatHoldRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/main/detail/{concertId}/seat-holds", CONCERT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
    }
}
