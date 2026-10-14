package com.onticket.admission;

import java.time.Duration;
import java.time.Instant;

public record AdmissionPolicy(long concertTimeId, String generation, Instant opensAt,
                              int maxActive, int maxWaiting, Duration waitingTtl,
                              Duration admissionTtl, int batchSize) {
    public AdmissionPolicy {
        if (concertTimeId <= 0 || generation == null || !generation.matches("[A-Za-z0-9-]{1,32}")
                || opensAt == null || opensAt.toEpochMilli() < 0 || opensAt.toEpochMilli() > 9_000_000_000_000L
                || !opensAt.equals(Instant.ofEpochMilli(opensAt.toEpochMilli()))
                || maxActive < 1 || maxActive > 1000 || maxWaiting < 1 || maxWaiting > 10000
                || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Invalid bounded admission policy");
        }
        validateTtl(waitingTtl); validateTtl(admissionTtl);
    }

    private static void validateTtl(Duration ttl) {
        if (ttl == null || ttl.compareTo(Duration.ofMillis(1)) < 0 || ttl.compareTo(Duration.ofHours(24)) > 0
                || !ttl.equals(Duration.ofMillis(ttl.toMillis()))) {
            throw new IllegalArgumentException("Admission TTL must be 1ms..24h");
        }
    }
}
