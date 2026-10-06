import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const resultsRoot = new URL('../../results/', import.meta.url);
const expectedOrder = [20, 40, 200, 200, 40, 20];

function readJson(relativePath) {
  const value = readFileSync(fileURLToPath(new URL(relativePath, resultsRoot)), 'utf8');
  return JSON.parse(value.charCodeAt(0) === 0xfeff ? value.slice(1) : value);
}

for (const [batchId, rate, project] of [
  ['churn172a', 50, 'ticketon-controlled172'],
  ['churn172b', 100, 'ticketon-controlled172-r100'],
]) {
  test(`${batchId} retains six valid weighted Hold→Release measurements`, () => {
    const manifest = readJson(`${batchId}/controlled-seat-hold-churn-manifest.json`);
    assert.equal(manifest.Complete, true);
    assert.equal(manifest.BatchId, batchId);
    assert.equal(manifest.ComposeProject, project);
    assert.equal(manifest.FixtureResetBeforeEachRun, true);
    assert.equal(manifest.HoldDwellMilliseconds, 100);
    assert.deepEqual(manifest.Records.map((record) => record.HotSeatCount), expectedOrder);

    for (const record of manifest.Records) {
      const summary = readJson(`${batchId}/${record.SummaryFile}`);
      const result = summary.K6.Result;
      const final = summary.K6.FinalSnapshot;
      assert.equal(summary.ValidMeasurement, true);
      assert.equal(summary.Run.Id, record.RunId);
      assert.equal(summary.Run.Scenario, 'weighted-hotspot-churn');
      assert.equal(summary.Run.RatePerSecond, rate);
      assert.equal(summary.Run.DurationSeconds, 10);
      assert.equal(summary.Run.HoldDwellMilliseconds, 100);
      assert.equal(summary.FixturePreparation.ReusedAndReset, true);
      assert.equal(summary.FixturePreparation.TotalSeats, 2000);
      assert.equal(summary.K6.ExitCode, 0);
      assert.equal(result.Scenario, 'weighted-hotspot-churn');
      assert.equal(result.HoldDwellMilliseconds, 100);
      assert.equal(result.WeightedHotspot.HotSeatCount, record.HotSeatCount);
      assert.equal(result.WeightedHotspot.HotRequestPercent, 70);
      assert.equal(result.WeightedHotspot.Seed, 17);
      assert.equal(result.WeightedHotspot.HotSelections + result.WeightedHotspot.ColdSelections, result.Iterations);
      assert.ok(result.WeightedHotspot.HotSelections / result.Iterations >= 0.65);
      assert.ok(result.WeightedHotspot.HotSelections / result.Iterations <= 0.75);
      assert.ok(result.Iterations >= rate * 10 * 0.99);
      assert.ok(result.Iterations <= rate * 10 * 1.01);
      assert.equal(result.Iterations, result.HoldSuccess + result.ExpectedContention);
      assert.equal(result.HoldSuccess, result.ReleaseSuccess);
      assert.equal(result.HoldSuccess, record.HoldSuccess);
      assert.equal(result.ReleaseSuccess, record.ReleaseSuccess);
      assert.equal(result.ExpectedContention, record.ExpectedContention);
      assert.ok(result.HoldSuccessDurationMs.P95 > 0);
      if (result.ExpectedContention > 0) assert.ok(result.HoldSeatConflictDurationMs.P95 > 0);
      assert.equal(result.HoldSuccessDurationMs.P95, record.SuccessP95Ms);
      assert.equal(result.HoldSeatConflictDurationMs.P95, record.SeatConflictP95Ms);
      assert.equal(result.DroppedIterations, 0);
      assert.equal(result.UnexpectedNonSuccessful, 0);
      assert.equal(result.UnexpectedRelease, 0);
      assert.equal(summary.DomainMetrics.HoldAcquired, result.HoldSuccess);
      assert.equal(summary.DomainMetrics.HoldReused, 0);
      assert.equal(summary.DomainMetrics.HoldReclaimed, 0);
      assert.equal(summary.DomainMetrics.Released, result.ReleaseSuccess);
      assert.equal(final.expectedTotalSeats, 2000);
      assert.equal(final.actualSeatCount, 2000);
      assert.equal(final.remainingSeats, 2000);
      assert.equal(final.reservedSeats, 0);
      assert.equal(final.activeHeldSeats, 0);
      assert.equal(final.holdRows, 0);
      assert.equal(final.partialHoldStates, 0);
      assert.equal(final.reservations + final.bookings + final.payments, 0);
      assert.equal(final.invariantSatisfied, true);
      assert.equal(summary.Metrics.Peaks.HikariPending, 0);
      assert.equal(summary.Metrics.Deltas.DbDeadlocks, 0);
    }
  });
}
