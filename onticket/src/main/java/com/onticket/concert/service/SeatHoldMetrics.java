package com.onticket.concert.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class SeatHoldMetrics {

    static final String REQUEST_METRIC = "onticket.seat.hold.request";
    static final String TRANSITION_METRIC = "onticket.seat.hold.transitions";

    private final MeterRegistry registry;

    SeatHoldMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    Tracker start(Operation operation) {
        return registry == null ? Tracker.noop() : new Tracker(registry, operation);
    }

    enum Operation {
        HOLD("hold"),
        RELEASE("release");

        private final String tag;

        Operation(String tag) {
            this.tag = tag;
        }
    }

    enum Transition {
        ACQUIRED("acquired"),
        REUSED("reused"),
        RECLAIMED("reclaimed"),
        RELEASED("released"),
        EXPIRED_CLEARED("expired_cleared");

        private final String tag;

        Transition(String tag) {
            this.tag = tag;
        }
    }

    private enum RequestOutcome {
        SUCCESS("success"),
        CONFLICT("conflict"),
        INVALID("invalid"),
        ERROR("error");

        private final String tag;

        RequestOutcome(String tag) {
            this.tag = tag;
        }
    }

    static final class Tracker {

        private final MeterRegistry registry;
        private final Operation operation;
        private final long startedAtNanos;
        private final Map<Transition, Integer> transitions;
        private final AtomicBoolean recorded;
        private final boolean transactionSynchronized;
        private RequestOutcome intendedOutcome = RequestOutcome.ERROR;

        private Tracker(MeterRegistry registry, Operation operation) {
            this.registry = registry;
            this.operation = operation;
            this.startedAtNanos = System.nanoTime();
            this.transitions = new EnumMap<>(Transition.class);
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
            this.registry = null;
            this.operation = null;
            this.startedAtNanos = 0L;
            this.transitions = Map.of();
            this.recorded = new AtomicBoolean(true);
            this.transactionSynchronized = false;
        }

        static Tracker noop() {
            return new Tracker();
        }

        void transition(Transition transition) {
            if (registry != null) {
                transitions.merge(transition, 1, Integer::sum);
            }
        }

        void succeed() {
            intendedOutcome = RequestOutcome.SUCCESS;
            if (!transactionSynchronized) {
                record(true);
            }
        }

        void fail(RuntimeException exception) {
            if (exception instanceof SeatHoldConflictException) {
                intendedOutcome = RequestOutcome.CONFLICT;
            } else if (exception instanceof InvalidSeatHoldRequestException) {
                intendedOutcome = RequestOutcome.INVALID;
            } else {
                intendedOutcome = RequestOutcome.ERROR;
            }
            if (!transactionSynchronized) {
                record(false);
            }
        }

        private void record(boolean committed) {
            if (registry == null || !recorded.compareAndSet(false, true)) {
                return;
            }

            RequestOutcome finalOutcome = committed && intendedOutcome == RequestOutcome.SUCCESS
                    ? RequestOutcome.SUCCESS
                    : intendedOutcome == RequestOutcome.SUCCESS ? RequestOutcome.ERROR : intendedOutcome;
            long durationNanos = System.nanoTime() - startedAtNanos;

            Timer.builder(REQUEST_METRIC)
                    .description("Seat hold service request latency by transaction-completed outcome")
                    .tag("operation", operation.tag)
                    .tag("outcome", finalOutcome.tag)
                    .serviceLevelObjectives(
                            Duration.ofMillis(5),
                            Duration.ofMillis(10),
                            Duration.ofMillis(25),
                            Duration.ofMillis(50),
                            Duration.ofMillis(100),
                            Duration.ofMillis(250),
                            Duration.ofMillis(500),
                            Duration.ofSeconds(1)
                    )
                    .register(registry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);

            if (committed && finalOutcome == RequestOutcome.SUCCESS) {
                transitions.forEach((transition, count) -> registry.counter(
                        TRANSITION_METRIC,
                        "operation", operation.tag,
                        "transition", transition.tag
                ).increment(count));
            }
        }
    }
}
