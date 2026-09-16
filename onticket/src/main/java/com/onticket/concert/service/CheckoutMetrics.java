package com.onticket.concert.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class CheckoutMetrics {

    static final String REQUEST_METRIC = "onticket.checkout.transaction";
    static final String TRANSITION_METRIC = "onticket.checkout.transitions";

    private final MeterRegistry registry;

    CheckoutMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    Tracker start(Operation operation) {
        return registry == null ? Tracker.noop() : new Tracker(registry, operation);
    }

    enum Operation {
        CANCEL("cancel"),
        VERIFY_CLAIM("verify_claim"),
        VERIFY_FINALIZE("verify_finalize");

        private final String tag;

        Operation(String tag) {
            this.tag = tag;
        }
    }

    enum Transition {
        CANCELED("canceled"),
        CANCEL_REUSED("cancel_reused"),
        VERIFICATION_CLAIMED("verification_claimed"),
        RESERVATION_CONFIRMED("reservation_confirmed"),
        VERIFICATION_UNKNOWN("verification_unknown");

        private final String tag;

        Transition(String tag) {
            this.tag = tag;
        }
    }

    static final class Tracker {
        private final MeterRegistry registry;
        private final Operation operation;
        private final long startedAtNanos;
        private final AtomicBoolean recorded;
        private final boolean transactionSynchronized;
        private Transition transition;
        private Outcome outcome = Outcome.ERROR;

        private Tracker(MeterRegistry registry, Operation operation) {
            this.registry = registry;
            this.operation = operation;
            this.startedAtNanos = System.nanoTime();
            this.recorded = new AtomicBoolean();
            this.transactionSynchronized = TransactionSynchronizationManager.isSynchronizationActive();
            if (transactionSynchronized) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        record(status == STATUS_COMMITTED);
                    }
                });
            }
        }

        private Tracker() {
            registry = null;
            operation = null;
            startedAtNanos = 0L;
            recorded = new AtomicBoolean(true);
            transactionSynchronized = false;
        }

        static Tracker noop() {
            return new Tracker();
        }

        void succeed(Transition transition) {
            this.outcome = Outcome.SUCCESS;
            this.transition = transition;
            if (!transactionSynchronized) record(true);
        }

        void fail(RuntimeException exception) {
            outcome = Outcome.from(exception);
            if (!transactionSynchronized) record(false);
        }

        private void record(boolean committed) {
            if (registry == null || !recorded.compareAndSet(false, true)) return;
            Outcome finalOutcome = committed && outcome == Outcome.SUCCESS ? Outcome.SUCCESS
                    : outcome == Outcome.SUCCESS ? Outcome.ERROR : outcome;
            Timer.builder(REQUEST_METRIC)
                    .description("Checkout transaction latency by transaction-completed outcome")
                    .tag("operation", operation.tag)
                    .tag("outcome", finalOutcome.tag)
                    .serviceLevelObjectives(Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100),
                            Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1))
                    .register(registry)
                    .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
            if (committed && transition != null) {
                registry.counter(TRANSITION_METRIC, "operation", operation.tag, "transition", transition.tag).increment();
            }
        }
    }

    private enum Outcome {
        SUCCESS("success"), CONFLICT("conflict"), INVALID("invalid"), EXPIRED("expired"), UNKNOWN("unknown"), ERROR("error");
        private final String tag;
        Outcome(String tag) { this.tag = tag; }
        static Outcome from(RuntimeException exception) {
            if (exception instanceof CheckoutConflictException) return CONFLICT;
            if (exception instanceof InvalidCheckoutRequestException || exception instanceof InvalidPaymentException) return INVALID;
            if (exception instanceof CheckoutExpiredException) return EXPIRED;
            if (exception instanceof PaymentVerificationUnknownException) return UNKNOWN;
            return ERROR;
        }
    }
}
