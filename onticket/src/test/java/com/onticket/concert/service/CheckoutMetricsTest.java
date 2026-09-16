package com.onticket.concert.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutMetricsTest {

    @Test
    void recordsTransitionOnlyAfterTheEnclosingTransactionCommits() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransactionSynchronizationManager.initSynchronization();
        try {
            CheckoutMetrics.Tracker tracker = new CheckoutMetrics(registry)
                    .start(CheckoutMetrics.Operation.CANCEL);
            tracker.succeed(CheckoutMetrics.Transition.CANCELED);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            assertThat(registry.find(CheckoutMetrics.TRANSITION_METRIC).counter()).isNull();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recordsCommittedUnknownTransitionEvenWhenTheCallerReceivesAnException() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransactionSynchronizationManager.initSynchronization();
        try {
            CheckoutMetrics.Tracker tracker = new CheckoutMetrics(registry)
                    .start(CheckoutMetrics.Operation.VERIFY_CLAIM);
            tracker.succeed(CheckoutMetrics.Transition.VERIFICATION_UNKNOWN);
            tracker.fail(new PaymentVerificationUnknownException());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

            assertThat(registry.get(CheckoutMetrics.TRANSITION_METRIC)
                    .tag("operation", "verify_claim")
                    .tag("transition", "verification_unknown")
                    .counter().count()).isEqualTo(1.0);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
