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

    public static CheckoutSeatAssignment assign(
            Checkout checkout,
            Seat seat,
            String requestFingerprint,
            LocalDateTime activeUntil
    ) {
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException("Checkout 좌석 귀속 fingerprint가 필요합니다.");
        }

        CheckoutSeatAssignment assignment = new CheckoutSeatAssignment();
        assignment.checkout = Objects.requireNonNull(checkout, "귀속할 Checkout이 필요합니다.");
        assignment.seat = Objects.requireNonNull(seat, "귀속할 좌석이 필요합니다.");
        assignment.requestFingerprint = requestFingerprint;
        assignment.activeUntil = Objects.requireNonNull(
                activeUntil,
                "Checkout 좌석 귀속의 활성 종료 시각이 필요합니다."
        );
        return assignment;
    }
}
