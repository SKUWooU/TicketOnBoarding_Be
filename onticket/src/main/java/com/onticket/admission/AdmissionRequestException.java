package com.onticket.admission;

import org.springframework.http.HttpStatus;

public final class AdmissionRequestException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public AdmissionRequestException(HttpStatus status, String code) {
        super(code); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
