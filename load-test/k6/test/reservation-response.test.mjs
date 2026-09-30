import assert from 'node:assert/strict';
import test from 'node:test';
import { isExpectedSeatContention } from '../reservation-response.mjs';

test('only a typed seat 409 is expected contention', () => {
  assert.equal(isExpectedSeatContention({
    status: 409,
    headers: { 'X-Reservation-Conflict': 'SEAT_UNAVAILABLE' },
  }, true), true);
  assert.equal(isExpectedSeatContention({ status: 409, headers: {} }, true), false);
  assert.equal(isExpectedSeatContention({
    status: 409,
    headers: { 'X-Reservation-Conflict': 'PAYMENT_ALREADY_USED' },
  }, true), false);
  assert.equal(isExpectedSeatContention({
    status: 200,
    headers: { 'X-Reservation-Conflict': 'SEAT_UNAVAILABLE' },
  }, true), false);
  assert.equal(isExpectedSeatContention({
    status: 409,
    headers: { 'X-Reservation-Conflict': 'SEAT_UNAVAILABLE' },
  }, false), false);
});
