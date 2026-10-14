import test from 'node:test';
import assert from 'node:assert/strict';
import { createMixedSeatPlan } from '../mixed-seat-plan.mjs';

test('separate and overlap use equal-sized unique canonical bundles', () => {
  for (const size of [20, 100, 200]) {
    const separate = createMixedSeatPlan(2000, 40, size, false);
    const overlap = createMixedSeatPlan(2000, 40, size, true);
    assert.deepEqual(separate.small, overlap.small);
    assert.equal(new Set(separate.large).size, size);
    assert.equal(new Set(overlap.large).size, size);
    assert.equal(separate.large.filter(s => separate.small.includes(s)).length, 0);
    assert.equal(overlap.large.filter(s => overlap.small.includes(s)).length, Math.min(40, size));
    assert.deepEqual(overlap.large, [...overlap.large].sort());
    assert.equal(separate.large[0], 'R002-S001');
  }
});

test('invalid or unbounded dimensions fail before requests', () => {
  for (const args of [[2000, 40, 201, true], [100, 40, 100, false], [2000, 0, 20, true], [2000, 40, 1.5, true], [2000, 40, 20, 'true']]) {
    assert.throws(() => createMixedSeatPlan(...args));
  }
});
