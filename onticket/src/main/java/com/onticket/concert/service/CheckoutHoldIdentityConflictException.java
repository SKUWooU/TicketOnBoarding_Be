package com.onticket.concert.service;

import lombok.Getter;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

@Getter
class CheckoutHoldIdentityConflictException extends RuntimeException {

    private final String username;
    private final String requestFingerprint;
    private final LocalDateTime expiresAt;
    private final List<Long> seatIds;
    private final LocalDateTime checkedAt;
    private final DataIntegrityViolationException dataIntegrityViolation;

    CheckoutHoldIdentityConflictException(
            String username,
            String requestFingerprint,
            LocalDateTime expiresAt,
            List<Long> seatIds,
            LocalDateTime checkedAt,
            DataIntegrityViolationException dataIntegrityViolation
    ) {
        super(dataIntegrityViolation);
        this.username = username;
        this.requestFingerprint = requestFingerprint;
        this.expiresAt = expiresAt;
        this.seatIds = List.copyOf(seatIds);
        this.checkedAt = checkedAt;
        this.dataIntegrityViolation = dataIntegrityViolation;
    }
}
