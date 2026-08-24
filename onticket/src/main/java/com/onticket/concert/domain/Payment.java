package com.onticket.concert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "reservation_payment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_provider_payment_id",
                columnNames = "provider_payment_id"
        )
)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_payment_id", nullable = false, length = 100)
    private String providerPaymentId;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "approved_amount", nullable = false)
    private long approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "approved_at", nullable = false)
    private LocalDateTime approvedAt;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    public static Payment approved(
            String providerPaymentId,
            String username,
            long approvedAmount,
            LocalDateTime approvedAt,
            Booking booking
    ) {
        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            throw new IllegalArgumentException("결제 식별자가 필요합니다.");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("결제자가 필요합니다.");
        }
        if (approvedAmount <= 0) {
            throw new IllegalArgumentException("승인 금액은 0보다 커야 합니다.");
        }
        Payment payment = new Payment();
        payment.providerPaymentId = providerPaymentId;
        payment.username = username;
        payment.approvedAmount = approvedAmount;
        payment.approvedAt = Objects.requireNonNull(approvedAt, "결제 승인 시각이 필요합니다.");
        payment.booking = Objects.requireNonNull(booking, "연결할 예약 요청이 필요합니다.");
        payment.status = PaymentStatus.APPROVED;
        return payment;
    }

    public void confirmReservation() {
        if (status != PaymentStatus.APPROVED) {
            throw new IllegalStateException("승인된 결제만 예약을 확정할 수 있습니다.");
        }
        status = PaymentStatus.RESERVATION_CONFIRMED;
    }
}
