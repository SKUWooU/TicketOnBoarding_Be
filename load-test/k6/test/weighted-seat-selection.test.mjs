import assert from 'node:assert/strict';
import test from 'node:test';
import { createWeightedSeatPlan, selectWeightedSeat } from '../weighted-seat-selection.mjs';

test('the same fixture, seed, and iteration reproduce the same selection', () => {
  const plan = createWeightedSeatPlan(2000, 40, 70, 17);
  const first = Array.from({ length: 1000 }, (_, iteration) => selectWeightedSeat(iteration, plan));
  const repeated = Array.from({ length: 1000 }, (_, iteration) => selectWeightedSeat(iteration, plan));
  const anotherSeed = createWeightedSeatPlan(2000, 40, 70, 18);

  assert.deepEqual(repeated, first);
  assert.ok(first.some((selection, iteration) =>
    selection.seatIndex !== selectWeightedSeat(iteration, anotherSeed).seatIndex));
  assert.ok(first.every(({ seatIndex, isHot }) =>
    seatIndex >= 0 && seatIndex < 2000 && (seatIndex < 40) === isHot));
});

test('requested hot share is reflected in selections without treating it as an exact quota', () => {
  const plan = createWeightedSeatPlan(2000, 40, 70, 17);
  const selected = Array.from({ length: 10000 }, (_, iteration) => selectWeightedSeat(iteration, plan));
  const hot = selected.filter(({ isHot }) => isHot).length;
  assert.ok(hot >= 6800 && hot <= 7200, `hot selections=${hot}`);
});

test('zero and full hot ratios stay within the requested seat sets', () => {
  const coldOnly = createWeightedSeatPlan(2000, 40, 0, 1);
  const hotOnly = createWeightedSeatPlan(2000, 40, 100, 1);
  const allSeats = createWeightedSeatPlan(2000, 2000, 100, 1);
  for (let iteration = 0; iteration < 100; iteration++) {
    assert.equal(selectWeightedSeat(iteration, coldOnly).isHot, false);
    assert.equal(selectWeightedSeat(iteration, hotOnly).isHot, true);
    assert.ok(selectWeightedSeat(iteration, allSeats).seatIndex < 2000);
  }
});

test('invalid fixture and workload settings are rejected', () => {
  for (const settings of [
    [2000, 0, 70, 1], [2000, 2001, 70, 1], [2000, 40, -1, 1],
    [2000, 40, 101, 1], [2000, 40, 70, -1], [2000, 40, 70, 4294967296],
    [2000, 2000, 70, 1], [2000, 40.5, 70, 1],
  ]) {
    assert.throws(() => createWeightedSeatPlan(...settings), `settings=${settings}`);
  }
  assert.throws(() => selectWeightedSeat(-1, createWeightedSeatPlan(2000, 40, 70, 1)));
});
