import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

async function readManifest(runId) {
  const text = await readFile(new URL(`../../results/${runId}/small-seat-sampling-contrast.json`, import.meta.url), 'utf8');
  return JSON.parse(text.replace(/^\uFEFF/, ''));
}

test('sampling crossover preserves the fixed workload, warm-ups and collection cost', async () => {
  const manifest = await readManifest('sampling202c');
  assert.equal(manifest.SchemaVersion, 6);
  assert.equal(manifest.Complete, true);
  assert.equal(manifest.CompareSampling, true);
  assert.equal(manifest.ContrastHotspots, false);
  assert.equal(manifest.FixedHotSeatCount, 1);
  assert.equal(manifest.FixedTargetIterationsPerSecond, 20);
  assert.equal(manifest.FixedHoldDwellMilliseconds, 500);
  assert.equal(manifest.FixedHotRequestPercent, 70);
  assert.equal(manifest.FixedSelectionSeed, 17);
  assert.equal(manifest.Failure, null);
  assert.deepEqual(manifest.WarmupRecords.map(r => r.SamplingIntervalMilliseconds), [1000, 2000]);
  assert.deepEqual(manifest.Records.map(r => r.SamplingIntervalMilliseconds), [1000, 2000, 2000, 1000, 1000, 2000]);
  assert.deepEqual(manifest.Records.map(r => r.Round), [1, 1, 2, 2, 3, 3]);
  const all = [...manifest.WarmupRecords, ...manifest.Records];
  assert.deepEqual(all.map(r => r.Sequence), [1, 2, 3, 4, 5, 6, 7, 8]);
  for (const record of all) {
    assert.equal(record.Measured, record.Round > 0);
    assert.equal(record.TargetIterationsPerSecond, 20);
    assert.equal(record.DurationSeconds, 15);
    assert.equal(record.HotSeatCount, 1);
    assert.ok(record.Iterations >= 285);
    assert.equal(record.Iterations, record.HoldSuccess + record.ExpectedSeatConflicts);
    assert.equal(record.Iterations, record.HotSelections + record.ColdSelections);
    assert.equal(record.HoldSuccess, record.ReleaseSuccess);
    assert.deepEqual(record.DatabaseCounts, [20, 0, 0, 20, 0]);
    for (const key of ['DroppedIterations', 'UnexpectedFailures', 'FinalHoldRows', 'DbDeadlocksDelta']) assert.equal(record[key], 0);
    const wait = record.WaitMetrics;
    assert.ok(wait.SampleCount >= 5 && wait.MaxSampleGapMs > 0 && wait.MaxSampleGapMs <= 3000);
    assert.equal(wait.HikariTimeoutDelta, 0);
    assert.equal(wait.HikariPendingPeak, 0);
    assert.ok(wait.HikariActivePeak < wait.HikariMax);
    assert.equal(wait.DbDeadlocksDelta, 0);
    assert.ok(wait.DbRowLockWaitsDelta >= 0 && wait.DbRowLockTimeMsDelta >= 0);
    const cost = record.CollectionMetrics;
    assert.equal(cost.SampleCount, wait.SampleCount);
    assert.ok(Number.isFinite(cost.CollectionTotalMs) && cost.CollectionTotalMs >= 0);
    assert.ok(cost.CollectionMaxMs >= cost.CollectionAverageMs);
    assert.ok(Math.abs(cost.CollectionTotalMs / cost.SampleCount - cost.CollectionAverageMs) < 1e-6);
    assert.ok(record.HostMetrics.HostFreeMemoryMinimumKb >= 2097152);
    assert.equal(record.ResourceReviewRequired, record.HostMetrics.HostCpuPeakPercent >= 90);
  }
  assert.equal(manifest.ResourceReviewRequired, all.some(r => r.ResourceReviewRequired));
});

test('the first warm-up gate failure is retained, not counted as a measurement', async () => {
  const manifest = await readManifest('sampling202a');
  assert.equal(manifest.Complete, false);
  assert.deepEqual(manifest.Records, []);
  assert.deepEqual(manifest.WarmupRecords, []);
  assert.equal(manifest.Failure.Sequence, 1);
  assert.equal(manifest.Failure.Reason, 'EXECUTION_OR_GATE_FAILED');
});

test('a sampling-gap failure retains partial stages without declaring a complete comparison', async () => {
  const manifest = await readManifest('sampling202b');
  assert.equal(manifest.Complete, false);
  assert.equal(manifest.WarmupRecords.length, 2);
  assert.equal(manifest.Records.length, 2);
  assert.equal(manifest.Failure.Sequence, 5);
  assert.equal(manifest.Failure.Reason, 'SAMPLING_GAP_EXCEEDED');
  assert.ok(manifest.Failure.MaxSampleGapMs > 3000);
});
