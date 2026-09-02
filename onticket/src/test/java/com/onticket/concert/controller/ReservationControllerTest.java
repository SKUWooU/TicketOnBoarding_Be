package com.onticket.concert.controller;

import com.onticket.concert.dto.ReservRequest;
import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.dto.CheckoutResponse;
import com.onticket.concert.dto.SeatHoldRequest;
import com.onticket.concert.dto.SeatHoldResponse;
import com.onticket.concert.dto.VerifiedReservRequest;
import com.onticket.concert.service.IdempotencyKeyConflictException;
import com.onticket.concert.service.CheckoutService;
import com.onticket.concert.service.CheckoutVerifiedReservationService;
import com.onticket.concert.service.CheckoutConflictException;
import com.onticket.concert.service.CheckoutExpiredException;
import com.onticket.concert.domain.CheckoutStatus;
import com.onticket.concert.service.InvalidCheckoutRequestException;
import com.onticket.concert.service.InvalidIdempotencyKeyException;
import com.onticket.concert.service.InvalidPaymentException;
import com.onticket.concert.service.InvalidSeatHoldRequestException;
import com.onticket.concert.service.PaymentVerificationUnavailableException;
import com.onticket.concert.service.ReservationIdempotencyService;
import com.onticket.concert.service.SeatHoldConflictException;
import com.onticket.concert.service.SeatHoldService;
import com.onticket.concert.service.SeatReservationConflictException;
import com.onticket.concert.service.VerifiedReservationService;
import com.onticket.concert.repository.BookingRepository;
import com.onticket.concert.repository.CheckoutRepository;
import com.onticket.concert.repository.PaymentRepository;
import com.onticket.concert.service.CheckoutExpirationService;
import com.onticket.concert.service.PaymentVerificationPort;
import com.onticket.concert.service.VerifiedReservationTransactionService;
import com.onticket.user.jwt.JwtUtil;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
    private CheckoutService checkoutService;

    @Mock
    private CheckoutVerifiedReservationService checkoutVerifiedReservationService;

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
                        checkoutService,
                        checkoutVerifiedReservationService,
                        jwtUtil
                )
        )
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .modulesToInstall(new JavaTimeModule())
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()
                ))
                .build();
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
    void authenticatedUserCanPrepareServerOwnedCheckout() throws Exception {
        when(checkoutService.prepare(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(CheckoutRequest.class),
                eq("checkout-key")
        )).thenReturn(new CheckoutResponse(
                "ticket-checkout-1",
                30_000,
                LocalDateTime.of(2030, 1, 1, 12, 5),
                CheckoutStatus.READY
        ));

        mockMvc.perform(post("/main/detail/{concertId}/checkouts", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", "checkout-key")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantUid").value("ticket-checkout-1"))
                .andExpect(jsonPath("$.amount").value(30_000))
                .andExpect(jsonPath("$.expiresAt").value("2030-01-01T12:05:00"))
                .andExpect(jsonPath("$.status").value("READY"));

        verify(checkoutService).prepare(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(CheckoutRequest.class),
                eq("checkout-key")
        );
    }

    @Test
    void unauthenticatedCheckoutPreparationReturnsHttp401() throws Exception {
        mockMvc.perform(post("/main/detail/{concertId}/checkouts", CONCERT_ID)
                        .header("Idempotency-Key", "checkout-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutVerifiedReservationUsesPathMerchantUid() throws Exception {
        when(checkoutVerifiedReservationService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(VerifiedReservRequest.class),
                eq("checkout-reservation-key")
        )).thenReturn(LocalDateTime.of(2030, 1, 1, 12, 1));

        mockMvc.perform(post(
                        "/main/detail/{concertId}/checkouts/{merchantUid}/verified-reservation",
                        CONCERT_ID,
                        "ticket-checkout-1"
                )
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", "checkout-reservation-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "concertTimeId": 1,
                                  "seatNumberList": ["A1"],
                                  "paymentId": "payment-1",
                                  "merchantUid": "body-value-must-not-win"
                                }
                                """))
                .andExpect(status().isOk());

        verify(checkoutVerifiedReservationService).reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                argThat(request -> "ticket-checkout-1".equals(request.getMerchantUid())),
                eq("checkout-reservation-key")
        );
    }

    @Test
    void invalidCheckoutPreparationReturnsHttp400() throws Exception {
        when(checkoutService.prepare(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(CheckoutRequest.class),
                eq("invalid-checkout-key")
        )).thenThrow(new InvalidCheckoutRequestException("유효한 좌석 점유가 필요합니다."));

        mockMvc.perform(post("/main/detail/{concertId}/checkouts", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", "invalid-checkout-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkoutPayloadConflictReturnsHttp409() throws Exception {
        when(checkoutService.prepare(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(CheckoutRequest.class),
                eq("checkout-conflict-key")
        )).thenThrow(new CheckoutConflictException("같은 멱등 키의 payload가 다릅니다."));

        mockMvc.perform(post("/main/detail/{concertId}/checkouts", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", "checkout-conflict-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void expiredCheckoutReservationReturnsHttp410() throws Exception {
        when(checkoutVerifiedReservationService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(VerifiedReservRequest.class),
                eq("expired-checkout-key")
        )).thenThrow(new CheckoutExpiredException());

        performCheckoutVerifiedReservation("expired-checkout-key")
                .andExpect(status().isGone());
    }

    @Test
    void invalidCheckoutPaymentReturnsHttp422() throws Exception {
        when(checkoutVerifiedReservationService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(VerifiedReservRequest.class),
                eq("invalid-payment-key")
        )).thenThrow(new InvalidPaymentException("승인 금액이 일치하지 않습니다."));

        performCheckoutVerifiedReservation("invalid-payment-key")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void checkoutReservationWithoutPaymentAdapterReturnsHttp503() throws Exception {
        when(checkoutVerifiedReservationService.reserve(
                eq(USERNAME),
                eq(CONCERT_ID),
                any(VerifiedReservRequest.class),
                eq("checkout-unavailable-key")
        )).thenThrow(new PaymentVerificationUnavailableException());

        performCheckoutVerifiedReservation("checkout-unavailable-key")
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void malformedCheckoutReservationPayloadsUseRealServiceAndReturnHttp400() throws Exception {
        CheckoutVerifiedReservationService realValidationService = new CheckoutVerifiedReservationService(
                mock(BookingRepository.class),
                mock(CheckoutRepository.class),
                mock(PaymentRepository.class),
                mock(PaymentVerificationPort.class),
                mock(VerifiedReservationTransactionService.class),
                mock(CheckoutExpirationService.class),
                Clock.systemUTC()
        );
        MockMvc validationMockMvc = MockMvcBuilders.standaloneSetup(
                new ReservationController(
                        reservationIdempotencyService,
                        verifiedReservationService,
                        seatHoldService,
                        checkoutService,
                        realValidationService,
                        jwtUtil
                )
        ).build();

        List<String> malformedBodies = List.of(
                "{}",
                """
                        {
                          "concertTimeId": 1,
                          "paymentId": "payment-1"
                        }
                        """,
                """
                        {
                          "concertTimeId": 1,
                          "seatNumberList": ["A1", "A1"],
                          "paymentId": "payment-1"
                        }
                        """
        );

        for (int index = 0; index < malformedBodies.size(); index++) {
            validationMockMvc.perform(post(
                            "/main/detail/{concertId}/checkouts/{merchantUid}/verified-reservation",
                            CONCERT_ID,
                            "ticket-checkout-1"
                    )
                            .cookie(new Cookie("accessToken", TOKEN))
                            .header("Idempotency-Key", "malformed-key-" + index)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformedBodies.get(index)))
                    .andExpect(status().isBadRequest());
        }
    }

    private org.springframework.test.web.servlet.ResultActions performCheckoutVerifiedReservation(
            String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(post(
                        "/main/detail/{concertId}/checkouts/{merchantUid}/verified-reservation",
                        CONCERT_ID,
                        "ticket-checkout-1"
                )
                        .cookie(new Cookie("accessToken", TOKEN))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "concertTimeId": 1,
                                  "seatNumberList": ["A1"],
                                  "paymentId": "payment-1"
                                }
                                """));
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
    void activeCheckoutSeatReleaseReturnsHttp409() throws Exception {
        doThrow(new SeatHoldConflictException("결제 준비 중인 좌석은 임시 점유를 해제할 수 없습니다."))
                .when(seatHoldService)
                .release(eq(USERNAME), eq(CONCERT_ID), any(SeatHoldRequest.class));

        mockMvc.perform(delete("/main/detail/{concertId}/seat-holds", CONCERT_ID)
                        .cookie(new Cookie("accessToken", TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void seatHoldRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/main/detail/{concertId}/seat-holds", CONCERT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
    }
}
