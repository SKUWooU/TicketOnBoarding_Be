package com.onticket.concert.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationStatusTest {

    private final ReservationStatusConverter converter = new ReservationStatusConverter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void databaseAndJsonValuesKeepLegacyKoreanContract() throws Exception {
        assertThat(converter.convertToDatabaseColumn(ReservationStatus.PAYMENT_COMPLETED))
                .isEqualTo("결제완료");
        assertThat(converter.convertToEntityAttribute("취소신청"))
                .isEqualTo(ReservationStatus.CANCELLATION_REQUESTED);
        assertThat(objectMapper.writeValueAsString(ReservationStatus.CANCELLATION_COMPLETED))
                .isEqualTo("\"취소완료\"");

        Reservation reservation = paymentCompletedReservation();
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(reservation)).get("status").asText())
                .isEqualTo("결제완료");
    }

    @Test
    void unknownDatabaseValueIsRejected() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("알수없음"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("알 수 없는 예약 상태입니다: 알수없음");
    }

    @Test
    void cancellationRequestAndCompletionFollowAllowedTransition() {
        Reservation reservation = paymentCompletedReservation();

        reservation.requestCancellation();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_REQUESTED);
        assertThat(reservation.completeCancellation()).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_COMPLETED);
    }

    @Test
    void cancellationRequestRetriesAreIdempotent() {
        Reservation reservation = paymentCompletedReservation();
        reservation.requestCancellation();

        reservation.requestCancellation();
        reservation.completeCancellation();
        reservation.requestCancellation();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_COMPLETED);
        assertThat(reservation.completeCancellation()).isFalse();
    }

    @Test
    void paymentCompletedReservationCannotSkipCancellationRequest() {
        Reservation reservation = paymentCompletedReservation();

        assertThatThrownBy(reservation::completeCancellation)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("취소 신청 상태의 예약만 취소할 수 있습니다.");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_COMPLETED);
    }

    @Test
    void reservationStatusCanOnlyBeInitializedOnce() {
        Reservation reservation = paymentCompletedReservation();

        assertThatThrownBy(reservation::markPaymentCompleted)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("예약 상태가 이미 초기화되었습니다.");
    }

    private Reservation paymentCompletedReservation() {
        Reservation reservation = new Reservation();
        reservation.markPaymentCompleted();
        return reservation;
    }
}
