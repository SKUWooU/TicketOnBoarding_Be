package com.onticket.admission;

public class AdmissionUnavailableException extends RuntimeException {
    public AdmissionUnavailableException() { super("Admission state unavailable; no fallback permitted"); }
}
