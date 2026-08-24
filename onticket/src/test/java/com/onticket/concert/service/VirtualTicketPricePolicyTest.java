package com.onticket.concert.service;

import com.onticket.concert.dto.ReservRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualTicketPricePolicyTest {

    @Test
    void calculatesExpectedAmountFromServerUnitPriceAndSeatCount() {
        VirtualTicketPricePolicy policy = new VirtualTicketPricePolicy(30_000);

        assertThat(policy.expectedAmount(request("A1", "A2"))).isEqualTo(60_000);
    }

    @Test
    void rejectsInvalidUnitPrice() {
        assertThatThrownBy(() -> new VirtualTicketPricePolicy(0))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("가상 좌석 단가는 0보다 커야 합니다.");
    }

    @Test
    void rejectsAmountOverflow() {
        VirtualTicketPricePolicy policy = new VirtualTicketPricePolicy(Long.MAX_VALUE);

        assertThatThrownBy(() -> policy.expectedAmount(request("A1", "A2")))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("결제 금액을 계산할 수 없습니다.");
    }

    private ReservRequest request(String... seats) {
        ReservRequest request = new ReservRequest();
        request.setSeatNumberList(List.of(seats));
        return request;
    }
}
