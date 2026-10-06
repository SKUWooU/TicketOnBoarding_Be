import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const results = new URL('../../results/hold170a/', import.meta.url);
const expectedOrder = [20, 40, 200, 200, 40, 20];
const expectedHeld = new Map([[20, 159], [40, 182], [200, 307]]);

function readJson(name) {
  const value = readFileSync(fileURLToPath(new URL(name, results)), 'utf8');
  return JSON.parse(value.charCodeAt(0) === 0xfeff ? value.slice(1) : value);
}

test('the published controlled Hold batch preserves its six-run comparison contract', () => {
  const manifest = readJson('controlled-seat-hold-manifest.json');
  assert.equal(manifest.Complete, true);
  assert.equal(manifest.ComposeProject, 'ticketon-controlled170');
  assert.equal(manifest.FixtureResetBeforeEachRun, true);
  assert.deepEqual(manifest.Records.map((record) => record.HotSeatCount), expectedOrder);

  for (const record of manifest.Records) {
    const summary = readJson(record.SummaryFile);
    const result = summary.K6.Result;
    const final = summary.K6.FinalSnapshot;
    assert.equal(summary.ValidMeasurement, true);
    assert.equal(summary.Run.Id, record.RunId);
    assert.equal(summary.Run.RatePerSecond, 50);
    assert.equal(summary.Run.DurationSeconds, 10);
    assert.equal(summary.FixturePreparation.ReusedAndReset, true);
    assert.equal(summary.FixturePreparation.TotalSeats, 2000);
    assert.equal(summary.K6.ExitCode, 0);
    assert.equal(summary.K6.StateInvariantSatisfied, true);
    assert.equal(result.Scenario, 'weighted-hotspot');
    assert.equal(result.WeightedHotspot.HotSeatCount, record.HotSeatCount);
    assert.equal(result.WeightedHotspot.HotRequestPercent, 70);
    assert.equal(result.WeightedHotspot.Seed, 17);
    assert.equal(result.WeightedHotspot.HotSelections + result.WeightedHotspot.ColdSelections, result.Iterations);
    assert.ok(result.Iterations >= 495 && result.Iterations <= 505);
    assert.equal(result.Iterations, result.HoldSuccess + result.ExpectedContention);
    assert.equal(result.DroppedIterations, 0);
    assert.equal(result.UnexpectedNonSuccessful, 0);
    assert.equal(result.HoldSuccess, expectedHeld.get(record.HotSeatCount));
    assert.equal(final.expectedTotalSeats, 2000);
    assert.equal(final.actualSeatCount, 2000);
    assert.equal(final.remainingSeats, 2000);
    assert.equal(final.reservedSeats, 0);
    assert.equal(final.activeHeldSeats, result.HoldSuccess);
    assert.equal(final.holdRows, result.HoldSuccess);
    assert.equal(final.partialHoldStates, 0);
    assert.equal(final.reservations + final.bookings + final.payments, 0);
    assert.equal(final.invariantSatisfied, true);
    assert.equal(summary.DomainMetrics.HoldAcquired, result.HoldSuccess);
    assert.equal(summary.DomainMetrics.HoldReused, 0);
    assert.equal(summary.DomainMetrics.HoldReclaimed, 0);
    assert.equal(summary.Metrics.Peaks.HikariPending, 0);
    assert.equal(summary.Metrics.Deltas.DbDeadlocks, 0);
  }
});
