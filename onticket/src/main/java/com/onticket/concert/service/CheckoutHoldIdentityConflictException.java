package com.onticket.concert.service;

import lombok.Getter;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

@Getter
class CheckoutHoldIdentityConflictException extends RuntimeException {

    private final String username;
    private final String requestFingerprint;
    private final LocalDateTime expiresAt;
    private final DataIntegrityViolationException dataIntegrityViolation;

    CheckoutHoldIdentityConflictException(
            String username,
            String requestFingerprint,
            LocalDateTime expiresAt,
            DataIntegrityViolationException dataIntegrityViolation
    ) {
        super(dataIntegrityViolation);
        this.username = username;
        this.requestFingerprint = requestFingerprint;
        this.expiresAt = expiresAt;
        this.dataIntegrityViolation = dataIntegrityViolation;
    }
}
