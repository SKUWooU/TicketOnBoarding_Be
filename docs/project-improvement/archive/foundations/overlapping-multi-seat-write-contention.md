# Overlapping multi-seat write contention

## Purpose

The existing reverse-order test proves the smallest lock-order case with two requests. This scenario adds partially overlapping four-seat requests so that several writers compete for shared inventory in one concert time.

This is a local MariaDB Testcontainers correctness measurement. It is not a KOPIS, payment-provider, or production-ticketing performance result.

## Fixture and workload

- One concert time with 24 virtual seats (`A1` through `C8`)
- Eight concurrent reservation requests
- Four seats per request
- Each bundle has a reverse-order counterpart
- Bundles overlap through `A3-A8` and `B1-B2`

```text
A1,A2,A3,A4     A4,A3,A2,A1
A5,A6,A7,A8     A8,A7,A6,A5
A3,A4,A5,A6     A6,A5,A4,A3
A7,A8,B1,B2     B2,B1,A8,A7
```

The expected business outcome is two successful bundles and six seat-conflict responses. A database deadlock, SQL state, error code, timeout, partial reservation, or broken inventory equation is an unexpected failure.

## Implementation under test

`SeatReservationService` canonicalizes every requested seat list before acquiring `PESSIMISTIC_WRITE` locks. It then updates reservation state and remaining inventory in the same transaction.

```text
request bundle
  -> canonical seat ordering
  -> PESSIMISTIC_WRITE for each seat in that order
  -> conditional inventory decrease
  -> reservation rows + commit
  -> full rollback when the transaction fails
```

## Observed result

Command:

```powershell
cd onticket
.\gradlew.bat test --tests "com.onticket.concert.service.SeatReservationConcurrencyIntegrationTest.overlappingMultiSeatRequestsKeepInventoryConsistentWithoutSqlDeadlock"
```

Observed test output:

```text
attempts=8
successes=2
conflicts=6
sqlDeadlocks=0
remaining=16
reserved=8
reservations=8
invariant=true
```

The test also verifies that every observed lock-query sequence is sorted, so reverse input order does not become reverse database lock order.

## Interpretation and limits

- This strengthens the multi-seat correctness evidence beyond a two-request case.
- The count is a bounded concurrency scenario, not an RPS capacity claim or p95 comparison.
- The result must not be presented as real venue traffic or a production deadlock rate.
- The separate 2,000-seat k6 index experiment remains the performance evidence for this project.

## Links

- Issue #154
- `SeatReservationConcurrencyIntegrationTest.overlappingMultiSeatRequestsKeepInventoryConsistentWithoutSqlDeadlock`
- [Multi-seat lock-order baseline](multi-seat-lock-order-deadlock-baseline.md)
