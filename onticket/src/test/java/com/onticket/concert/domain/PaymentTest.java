package com.onticket.concert.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    void approvedPaymentCanConfirmReservationOnce() {
        Payment payment = approvedPayment();

        payment.confirmReservation();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.RESERVATION_CONFIRMED);
        assertThatThrownBy(payment::confirmReservation)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("승인된 결제만 예약을 확정할 수 있습니다.");
    }

    @Test
    void approvedPaymentRequiresPositiveAmount() {
        assertThatThrownBy(() -> Payment.approved(
                "payment-1",
                "user-1",
                0,
                LocalDateTime.of(2030, 1, 1, 12, 0),
                new Booking()
        )).isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("승인 금액은 0보다 커야 합니다.");
    }

    private Payment approvedPayment() {
        return Payment.approved(
                "payment-1",
                "user-1",
                30_000,
                LocalDateTime.of(2030, 1, 1, 12, 0),
                new Booking()
        );
    }
}
