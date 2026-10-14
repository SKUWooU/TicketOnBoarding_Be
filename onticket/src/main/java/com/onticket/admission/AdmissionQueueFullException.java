package com.onticket.admission;

public class AdmissionQueueFullException extends RuntimeException {
    public AdmissionQueueFullException() { super("Admission waiting limit reached"); }
}
