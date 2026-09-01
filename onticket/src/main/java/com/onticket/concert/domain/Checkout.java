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
        name = "reservation_checkout",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_checkout_merchant_uid",
                        columnNames = "merchant_uid"
                ),
                @UniqueConstraint(
                        name = "uk_checkout_username_idempotency_key",
                        columnNames = {"username", "idempotency_key"}
                )
        }
)
public class Checkout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_uid", nullable = false, length = 100)
    private String merchantUid;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "concert_id", nullable = false, length = 100)
    private String concertId;

    @Column(name = "concert_time_id", nullable = false)
    private Long concertTimeId;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "expected_amount", nullable = false)
    private long expectedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CheckoutStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", unique = true)
    private Booking booking;

    public static Checkout ready(
            String merchantUid,
            String username,
            String idempotencyKey,
            String concertId,
            Long concertTimeId,
            String requestFingerprint,
            long expectedAmount,
            LocalDateTime createdAt,
            LocalDateTime expiresAt
    ) {
        if (merchantUid == null || merchantUid.isBlank()) {
            throw new IllegalArgumentException("고객사 주문 식별자가 필요합니다.");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("결제 요청 사용자가 필요합니다.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("결제 요청 멱등 키가 필요합니다.");
        }
        if (concertId == null || concertId.isBlank() || concertTimeId == null) {
            throw new IllegalArgumentException("공연과 회차가 필요합니다.");
        }
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException("결제 요청 fingerprint가 필요합니다.");
        }
        if (expectedAmount <= 0) {
            throw new IllegalArgumentException("서버 주문 금액은 0보다 커야 합니다.");
        }
        Objects.requireNonNull(createdAt, "결제 요청 생성 시각이 필요합니다.");
        Objects.requireNonNull(expiresAt, "결제 요청 만료 시각이 필요합니다.");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("결제 요청 만료 시각은 생성 시각 이후여야 합니다.");
        }

        Checkout checkout = new Checkout();
        checkout.merchantUid = merchantUid;
        checkout.username = username;
        checkout.idempotencyKey = idempotencyKey;
        checkout.concertId = concertId;
        checkout.concertTimeId = concertTimeId;
        checkout.requestFingerprint = requestFingerprint;
        checkout.expectedAmount = expectedAmount;
        checkout.createdAt = createdAt;
        checkout.expiresAt = expiresAt;
        checkout.status = CheckoutStatus.READY;
        return checkout;
    }

    public boolean expireIfNeeded(LocalDateTime now) {
        Objects.requireNonNull(now, "결제 요청 상태 확인 시각이 필요합니다.");
        if (status == CheckoutStatus.READY && !now.isBefore(expiresAt)) {
            status = CheckoutStatus.EXPIRED;
            return true;
        }
        return status == CheckoutStatus.EXPIRED;
    }

    public void confirmReservation(Booking confirmedBooking) {
        if (status != CheckoutStatus.READY) {
            throw new IllegalStateException("준비된 결제 요청만 예약을 확정할 수 있습니다.");
        }
        booking = Objects.requireNonNull(confirmedBooking, "확정된 예약 요청이 필요합니다.");
        status = CheckoutStatus.RESERVATION_CONFIRMED;
    }
}
