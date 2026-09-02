package com.onticket.concert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
        name = "reservation_checkout_seat_assignment",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_checkout_seat_assignment_checkout_seat",
                        columnNames = {"checkout_id", "seat_id"}
                ),
                @UniqueConstraint(
                        name = "uk_checkout_seat_assignment_seat_active_until",
                        columnNames = {"seat_id", "active_until"}
                )
        }
)
public class CheckoutSeatAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checkout_id", nullable = false)
    private Checkout checkout;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "active_until", nullable = false)
    private LocalDateTime activeUntil;

    @Column(name = "original_hold_expires_at", nullable = false)
    private LocalDateTime originalHoldExpiresAt;

    @Column(name = "verification_lease_until")
    private LocalDateTime verificationLeaseUntil;

    public static CheckoutSeatAssignment assign(
            Checkout checkout,
            Seat seat,
            String requestFingerprint,
            LocalDateTime originalHoldExpiresAt,
            LocalDateTime activeUntil
    ) {
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException("Checkout 좌석 귀속 fingerprint가 필요합니다.");
        }

        CheckoutSeatAssignment assignment = new CheckoutSeatAssignment();
        assignment.checkout = Objects.requireNonNull(checkout, "귀속할 Checkout이 필요합니다.");
        assignment.seat = Objects.requireNonNull(seat, "귀속할 좌석이 필요합니다.");
        assignment.requestFingerprint = requestFingerprint;
        assignment.originalHoldExpiresAt = Objects.requireNonNull(
                originalHoldExpiresAt,
                "좌석의 원래 점유 만료 시각이 필요합니다."
        );
        assignment.activeUntil = Objects.requireNonNull(
                activeUntil,
                "Checkout 좌석 귀속의 활성 종료 시각이 필요합니다."
        );
        if (activeUntil.isAfter(originalHoldExpiresAt)) {
            throw new IllegalArgumentException("Checkout 좌석 귀속 기한은 원래 점유 기한 이후일 수 없습니다.");
        }
        return assignment;
    }

    public void beginVerificationLease(
            LocalDateTime expectedCurrentUntil,
            LocalDateTime verificationDeadline
    ) {
        if (!Objects.equals(activeUntil, expectedCurrentUntil)) {
            throw new IllegalStateException("Checkout 좌석 귀속의 현재 활성 기한이 일치하지 않습니다.");
        }
        if (verificationLeaseUntil != null) {
            throw new IllegalStateException("Checkout 좌석에 이미 결제 검증 lease가 존재합니다.");
        }
        Objects.requireNonNull(verificationDeadline, "결제 검증 lease 기한이 필요합니다.");
        if (!verificationDeadline.isAfter(activeUntil)) {
            throw new IllegalArgumentException("결제 검증 lease 기한은 Checkout 기한 이후여야 합니다.");
        }
        verificationLeaseUntil = verificationDeadline;
    }

    public void clearVerificationLease(LocalDateTime expectedDeadline) {
        if (!Objects.equals(verificationLeaseUntil, expectedDeadline)) {
            throw new IllegalStateException("Checkout 좌석 귀속의 결제 검증 lease가 일치하지 않습니다.");
        }
        verificationLeaseUntil = null;
    }

    public LocalDateTime effectiveActiveUntil() {
        return verificationLeaseUntil == null ? activeUntil : verificationLeaseUntil;
    }
}
