# Payment verification I/O boundary

## Scenario

- A Checkout callback acquires the `PAYMENT_VERIFYING` claim.
- The Mock `PaymentVerificationPort` pauses before returning approval; no real PG API is called.
- A second callback for the same Checkout arrives while the provider response is paused.

## Verified behavior

- The second callback returns `CheckoutConflictException` within one second while the first Mock PG response is still blocked.
- Only one provider verification call occurs.
- After the first response is released, exactly one booking and reservation are confirmed.

This demonstrates that the claim transaction commits before external I/O and that the provider wait does not retain the Checkout database lock. It is a bounded integration test, not a provider latency or production throughput claim.
