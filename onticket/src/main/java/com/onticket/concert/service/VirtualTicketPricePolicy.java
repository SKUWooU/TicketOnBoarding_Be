package com.onticket.concert.service;

import com.onticket.concert.dto.ReservRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VirtualTicketPricePolicy {

    private final long unitPrice;

    public VirtualTicketPricePolicy(
            @Value("${onticket.ticket.virtual-seat-unit-price:30000}") long unitPrice
    ) {
        if (unitPrice <= 0) {
            throw new IllegalArgumentException("가상 좌석 단가는 0보다 커야 합니다.");
        }
        this.unitPrice = unitPrice;
    }

    public long expectedAmount(ReservRequest request) {
        int seatCount = ReservationRequestCanonicalizer.canonicalSeatNumbers(request).size();
        try {
            return Math.multiplyExact(unitPrice, seatCount);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("결제 금액을 계산할 수 없습니다.", exception);
        }
    }

    public long getUnitPrice() {
        return unitPrice;
    }
}
