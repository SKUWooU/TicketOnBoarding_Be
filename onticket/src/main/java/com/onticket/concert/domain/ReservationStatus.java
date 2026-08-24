package com.onticket.concert.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ReservationStatus {
    PAYMENT_COMPLETED("결제완료"),
    CANCELLATION_REQUESTED("취소신청"),
    CANCELLATION_COMPLETED("취소완료");

    private final String value;

    ReservationStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ReservationStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 예약 상태입니다: " + value));
    }
}
