package com.onticket.concert.service;

import com.onticket.concert.domain.Checkout;
import com.onticket.concert.dto.CheckoutRequest;
import com.onticket.concert.repository.*;
import com.onticket.admission.AdmissionUnavailableException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CheckoutAdmissionBoundaryTest {
    final CheckoutRepository checkouts = mock(CheckoutRepository.class);
    final CheckoutRequestKeyRepository keys = mock(CheckoutRequestKeyRepository.class);
    final CheckoutPreparationTransactionService transactions = mock(CheckoutPreparationTransactionService.class);
    final Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    final CheckoutService service = new CheckoutService(checkouts, keys, mock(CheckoutSeatAssignmentRepository.class),
            transactions, mock(CheckoutExpirationService.class), new VirtualTicketPricePolicy(30000), clock);

    CheckoutRequest request() {
        var request = new CheckoutRequest(); request.setConcertTimeId(7L); request.setSeatNumberList(List.of("A1")); return request;
    }
    @Test void existingMatchingCheckoutReplayDoesNotInvokeRedisGate() {
        var request = request(); var now = LocalDateTime.now(clock);
        var existing = Checkout.ready("merchant", "alice", "key", "LOAD-TEST-gate", 7L,
                ReservationRequestCanonicalizer.fingerprint("LOAD-TEST-gate", request), 30000, now, now.plusMinutes(5));
        when(checkouts.findByUsernameAndIdempotencyKey("alice", "key")).thenReturn(Optional.of(existing));
        var result = service.prepare("alice", "LOAD-TEST-gate", request, "key", () -> { throw new AdmissionUnavailableException(); });
        assertThat(result.getMerchantUid()).isEqualTo("merchant"); verifyNoInteractions(transactions);
    }
    @Test void newCheckoutIsNotCreatedWhenAdmissionFails() {
        assertThatThrownBy(() -> service.prepare("alice", "LOAD-TEST-gate", request(), "new",
                () -> { throw new AdmissionUnavailableException(); })).isInstanceOf(AdmissionUnavailableException.class);
        verifyNoInteractions(transactions);
    }
    @Test void newCheckoutGateRunsBeforeCreationTransaction() {
        Runnable gate = mock(Runnable.class);
        when(transactions.create(anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyString(), anyLong()))
                .thenThrow(new InvalidCheckoutRequestException("controlled stop"));
        assertThatThrownBy(() -> service.prepare("alice", "LOAD-TEST-gate", request(), "new", gate))
                .isInstanceOf(InvalidCheckoutRequestException.class);
        var order = inOrder(gate, transactions); order.verify(gate).run();
        order.verify(transactions).create(anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyString(), anyLong());
    }
}
