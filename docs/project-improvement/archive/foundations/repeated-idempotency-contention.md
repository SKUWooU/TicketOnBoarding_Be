# Repeated idempotency contention

## Workload

- Same user, same reservation payload, and same idempotency key per round
- Eight requests released concurrently with a lookup barrier
- Fifty isolated rounds with the MariaDB Testcontainers fixture recreated between rounds

## Observed aggregate

```text
rounds=50
attempts=400
createdBookings=50
reusedResults=350
duplicateBookings=0
unexpectedFailures=0
```

Each round asserts one booking, one reservation, one reserved seat, and the inventory equation. A separate test verifies that the same key with a different payload is rejected rather than replaying the original result.

## Limits

This is a local correctness measurement, not an RPS, TPS, or production traffic claim.
