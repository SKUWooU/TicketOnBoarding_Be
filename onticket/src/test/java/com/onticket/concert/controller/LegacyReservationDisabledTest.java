package com.onticket.concert.controller;

import com.onticket.concert.service.VerifiedReservationService;
import com.onticket.concert.service.SeatHoldService;
import com.onticket.concert.service.CheckoutService;
import com.onticket.concert.service.CheckoutCancellationService;
import com.onticket.concert.service.CheckoutVerifiedReservationService;
import com.onticket.user.jwt.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class LegacyReservationDisabledTest {
    private final VerifiedReservationService verified = mock(VerifiedReservationService.class);
    private final SeatHoldService holds = mock(SeatHoldService.class);
    private final CheckoutService checkouts = mock(CheckoutService.class);
    private final CheckoutCancellationService cancellations = mock(CheckoutCancellationService.class);
    private final CheckoutVerifiedReservationService checkoutVerified = mock(CheckoutVerifiedReservationService.class);
    private final JwtUtil jwt = mock(JwtUtil.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ReservationController(
                verified, holds, checkouts, cancellations, checkoutVerified, jwt
        )).build();
        when(jwt.validateToken("valid")).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"concertTimeId\":1,\"seatNumberList\":[\"A1\"]}", "{}", "broken", ""})
    void authenticatedRequestsCannotCreateReservations(String body) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/main/detail/CONCERT-1/reservation")
                            .cookie(new Cookie("accessToken", "valid"))
                            .header("Idempotency-Key", "retry-key")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("LEGACY_RESERVATION_DISABLED"));
        }
        verifyNoInteractions(verified, holds, checkouts, cancellations, checkoutVerified);
        verify(jwt, never()).getUsernameFromToken(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid"})
    void unauthenticatedRequestsRemainUnauthorized(String token) throws Exception {
        var request = post("/main/detail/CONCERT-1/reservation")
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        if (!token.equals("missing")) request.cookie(new Cookie("accessToken", token));
        mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(verified, holds, checkouts, cancellations, checkoutVerified);
    }
}
