package com.onticket.admission;

import java.time.Instant;

public record AdmissionSnapshot(String queueEntryId, State state, Instant expiresAt,
                                long sequence, long position, String admissionId, String token) {
    public enum State { WAITING, ADMITTED, EXPIRED, LEFT }
}
