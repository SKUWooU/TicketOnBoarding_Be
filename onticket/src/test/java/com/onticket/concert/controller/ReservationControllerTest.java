package com.onticket.concert.controller;

import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.service.IdempotencyKeyConflictException;
import com.onticket.concert.service.InvalidIdempotencyKeyException;
import com.onticket.concert.service.ReservationIdempotencyService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private JwtUtil jwtUtil;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ReservationController(reservationIdempotencyService, jwtUtil)
        ).build();
        when(jwtUtil.validateToken(TOKEN)).thenReturn(true);
        when(jwtUtil.getUsernameFromToken(TOKEN)).thenReturn(USERNAME);
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
}
