package com.onticket.admission;

import com.onticket.concert.controller.ReservationController;
import com.onticket.loadtest.LoadTestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {AdmissionController.class, ReservationController.class, LoadTestController.class})
public class AdmissionErrorAdvice {
    @ExceptionHandler(AdmissionRequestException.class)
    ResponseEntity<?> request(AdmissionRequestException error) { return error(error.status(), error.code()); }
    @ExceptionHandler(AdmissionUnavailableException.class)
    ResponseEntity<?> unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "ADMISSION_UNAVAILABLE"); }
    @ExceptionHandler(AdmissionQueueFullException.class)
    ResponseEntity<?> full() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Cache-Control", "no-store")
                .header("Retry-After", "5").body(Map.of("code", "ADMISSION_QUEUE_FULL"));
    }
    private ResponseEntity<?> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("code", code));
    }
}
