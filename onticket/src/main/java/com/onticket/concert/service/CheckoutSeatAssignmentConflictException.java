package com.onticket.concert.service;

import lombok.Getter;

@Getter
class CheckoutSeatAssignmentConflictException extends RuntimeException {

    private final Long reusableCheckoutId;

    CheckoutSeatAssignmentConflictException(Long reusableCheckoutId) {
        super("활성 결제 요청에 이미 귀속된 좌석입니다.");
        this.reusableCheckoutId = reusableCheckoutId;
    }
}
