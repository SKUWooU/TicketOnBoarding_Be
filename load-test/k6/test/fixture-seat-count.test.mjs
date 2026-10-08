import assert from 'node:assert/strict';
import test from 'node:test';
import { expectedFixtureSeatCount } from '../fixture-seat-count.mjs';

test('2,000-seat behavior remains the default and 20 seats require explicit opt-in', () => {
  assert.equal(expectedFixtureSeatCount(undefined), 2000);
  assert.equal(expectedFixtureSeatCount(''), 2000);
  assert.equal(expectedFixtureSeatCount('2000'), 2000);
  assert.equal(expectedFixtureSeatCount('20'), 20);
});

test('unsupported or malformed fixture sizes fail closed', () => {
  for (const value of ['0', '19', '21', '2001', '-20', '20.5', 'x', '  ', ' 20', '20.0']) {
    assert.throws(() => expectedFixtureSeatCount(value), value);
  }
});
