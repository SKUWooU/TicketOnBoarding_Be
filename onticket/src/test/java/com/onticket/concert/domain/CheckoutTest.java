package com.onticket.concert.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2030, 1, 1, 12, 0);

    @Test
    void readyCheckoutExpiresAtTheExactHoldBoundary() {
        Checkout checkout = checkout();

        assertThat(checkout.expireIfNeeded(CREATED_AT.plusMinutes(5).minusNanos(1))).isFalse();
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.READY);

        assertThat(checkout.expireIfNeeded(CREATED_AT.plusMinutes(5))).isTrue();
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.EXPIRED);
    }

    @Test
    void confirmedCheckoutCannotExpireOrBeConfirmedTwice() {
        Checkout checkout = checkout();
        Booking booking = new Booking();
        checkout.beginPaymentVerification(
                "payment-1",
                "reservation-key-1",
                "booking-fingerprint",
                CREATED_AT.plusMinutes(4),
                CREATED_AT.plusMinutes(5).plusSeconds(30)
        );
        checkout.confirmReservation(booking);

        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.RESERVATION_CONFIRMED);
        assertThat(checkout.expireIfNeeded(CREATED_AT.plusHours(1))).isFalse();
        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.RESERVATION_CONFIRMED);
        assertThatThrownBy(() -> checkout.confirmReservation(new Booking()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void knownVerificationFailureRestoresReadyBeforeOriginalExpiry() {
        Checkout checkout = checkout();
        checkout.beginPaymentVerification(
                "payment-1",
                "reservation-key-1",
                "booking-fingerprint",
                CREATED_AT.plusMinutes(4),
                CREATED_AT.plusMinutes(5).plusSeconds(30)
        );

        checkout.releasePaymentVerification(CREATED_AT.plusMinutes(4).plusSeconds(1));

        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.READY);
        assertThat(checkout.getVerificationPaymentId()).isNull();
        assertThat(checkout.getVerificationDeadline()).isNull();
    }

    @Test
    void ambiguousVerificationRetainsClaimMetadata() {
        Checkout checkout = checkout();
        LocalDateTime deadline = CREATED_AT.plusMinutes(5).plusSeconds(30);
        checkout.beginPaymentVerification(
                "payment-1",
                "reservation-key-1",
                "booking-fingerprint",
                CREATED_AT.plusMinutes(4),
                deadline
        );

        checkout.markPaymentVerificationUnknown();

        assertThat(checkout.getStatus()).isEqualTo(CheckoutStatus.PAYMENT_VERIFICATION_UNKNOWN);
        assertThat(checkout.getVerificationPaymentId()).isEqualTo("payment-1");
        assertThat(checkout.getVerificationDeadline()).isEqualTo(deadline);
    }

    @Test
    void expiryMustBeAfterCreation() {
        assertThatThrownBy(() -> Checkout.ready(
                "ticket-1",
                "user-a",
                "key-1",
                "concert-1",
                1L,
                "fingerprint",
                30_000,
                CREATED_AT,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private Checkout checkout() {
        return Checkout.ready(
                "ticket-1",
                "user-a",
                "key-1",
                "concert-1",
                1L,
                "fingerprint",
                30_000,
                CREATED_AT,
                CREATED_AT.plusMinutes(5)
        );
    }
}
