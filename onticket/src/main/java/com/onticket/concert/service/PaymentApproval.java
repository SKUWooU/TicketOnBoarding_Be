package com.onticket.concert.service;

import java.time.LocalDateTime;

public record PaymentApproval(
        String paymentId,
        String merchantUid,
        String username,
        long approvedAmount,
        boolean approved,
        LocalDateTime approvedAt
) {
}
