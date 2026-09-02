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

import java.util.Objects;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "reservation_checkout_request_key",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_checkout_request_key_username_idempotency",
                columnNames = {"username", "idempotency_key"}
        )
)
public class CheckoutRequestKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "checkout_id", nullable = false)
    private Checkout checkout;

    public static CheckoutRequestKey bind(
            String username,
            String idempotencyKey,
            String requestFingerprint,
            Checkout checkout
    ) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("결제 요청 사용자가 필요합니다.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("결제 요청 멱등 키가 필요합니다.");
        }
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException("결제 요청 fingerprint가 필요합니다.");
        }

        CheckoutRequestKey requestKey = new CheckoutRequestKey();
        requestKey.username = username;
        requestKey.idempotencyKey = idempotencyKey;
        requestKey.requestFingerprint = requestFingerprint;
        requestKey.checkout = Objects.requireNonNull(checkout, "연결할 Checkout이 필요합니다.");
        return requestKey;
    }
}
