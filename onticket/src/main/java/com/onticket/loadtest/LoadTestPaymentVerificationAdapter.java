package com.onticket.loadtest;

import com.onticket.concert.service.PaymentApproval;
import com.onticket.concert.service.PaymentVerificationPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Profile("loadtest")
public class LoadTestPaymentVerificationAdapter implements PaymentVerificationPort {

    private static final String PREFIX = "LT";

    @Override
    public PaymentApproval verify(String paymentId) {
        if (paymentId == null) {
            return rejected(null);
        }

        String[] parts = paymentId.split(":", 4);
        if (parts.length != 4
                || !PREFIX.equals(parts[0])
                || parts[1].isBlank()
                || parts[3].isBlank()) {
            return rejected(paymentId);
        }

        try {
            long amount = Long.parseLong(parts[2]);
            if (amount <= 0) {
                return rejected(paymentId);
            }
            return new PaymentApproval(
                    paymentId,
                    parts[3],
                    parts[1],
                    amount,
                    true,
                    LocalDateTime.now()
            );
        } catch (NumberFormatException exception) {
            return rejected(paymentId);
        }
    }

    private PaymentApproval rejected(String paymentId) {
        return new PaymentApproval(paymentId, null, null, 0, false, null);
    }
}
