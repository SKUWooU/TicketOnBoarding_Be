package com.onticket.admission;

import java.time.Instant;

/** serverNow is Redis TIME from the same Lua read as the owner snapshot. */
public record AdmissionObservation(AdmissionSnapshot admission, Instant serverNow) {
    @Override public String toString() {
        return "AdmissionObservation[admission=" + admission + ", serverNow=" + serverNow + "]";
    }
}
