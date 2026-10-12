import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

async function readManifest(runId) {
  const text = await readFile(new URL(`../../results/${runId}/small-seat-hold-contrast.json`, import.meta.url), 'utf8');
  return JSON.parse(text.replace(/^\uFEFF/, ''));
}

test('same-arrival hotspot contrast retains warm-ups and six measured inventory-safe stages', async () => {
  const manifest = await readManifest('contrast200b');
  assert.equal(manifest.SchemaVersion, 5);
  assert.equal(manifest.Complete, true);
  assert.equal(manifest.ContrastHotspots, true);
  assert.equal(manifest.FixedTargetIterationsPerSecond, 20);
  assert.equal(manifest.FixedHotRequestPercent, 70);
  assert.equal(manifest.FixedHoldDwellMilliseconds, 500);
  assert.equal(manifest.FixedSelectionSeed, 17);
  assert.equal(manifest.FixedPreAllocatedVus, 20);
  assert.equal(manifest.FixedMaxVus, 20);
  assert.equal(manifest.Failure, null);
  assert.deepEqual(manifest.WarmupRecords.map((record) => record.HotSeatCount), [10, 1]);
  assert.deepEqual(manifest.Records.map((record) => record.HotSeatCount), [1, 10, 10, 1, 1, 10]);
  assert.deepEqual(manifest.Records.map((record) => record.Round), [1, 1, 2, 2, 3, 3]);
  const all = [...manifest.WarmupRecords, ...manifest.Records];
  assert.deepEqual(all.map((record) => record.Sequence), [1, 2, 3, 4, 5, 6, 7, 8]);
  for (const record of all) {
    assert.equal(record.Measured, record.Round > 0);
    assert.equal(record.TargetIterationsPerSecond, 20);
    assert.equal(record.DurationSeconds, 10);
    assert.ok(record.Iterations >= 190);
    assert.equal(record.Iterations, record.HoldSuccess + record.ExpectedSeatConflicts);
    assert.equal(record.Iterations, record.HotSelections + record.ColdSelections);
    assert.equal(record.HoldSuccess, record.ReleaseSuccess);
    assert.equal(record.HoldSuccessPerScheduledSecond, record.HoldSuccess / 10);
    assert.ok(record.HoldSuccessP95Ms > 0);
    if (record.ExpectedSeatConflicts > 0) assert.ok(record.SeatConflictP95Ms > 0);
    for (const key of ['DroppedIterations', 'UnexpectedFailures', 'FinalHoldRows', 'DbDeadlocksDelta']) {
      assert.equal(record[key], 0);
    }
    assert.deepEqual(record.DatabaseCounts, [20, 0, 0, 20, 0]);
    const wait = record.WaitMetrics;
    assert.ok(wait.SampleCount >= 5 && wait.MaxSampleGapMs > 0 && wait.MaxSampleGapMs <= 3000);
    assert.equal(wait.HikariPendingPeak, 0);
    assert.ok(wait.HikariActivePeak < wait.HikariMax);
    assert.equal(wait.HikariTimeoutDelta, 0);
    assert.equal(wait.DbDeadlocksDelta, 0);
    assert.ok(record.HostMetrics.HostFreeMemoryMinimumKb >= 2097152);
    assert.ok(record.HostMetrics.HostCpuPeakPercent >= 0 && record.HostMetrics.HostCpuPeakPercent <= 100);
    assert.equal(record.ResourceReviewRequired, record.HostMetrics.HostCpuPeakPercent >= 90);
  }
  assert.equal(manifest.ResourceReviewRequired, all.some((record) => record.ResourceReviewRequired));
});

test('the failed first attempt is preserved without presenting completed measurements', async () => {
  const manifest = await readManifest('contrast200a');
  assert.equal(manifest.Complete, false);
  assert.deepEqual(manifest.Records, []);
  assert.deepEqual(manifest.WarmupRecords, []);
  assert.equal(manifest.Failure.Sequence, 1);
  assert.equal(manifest.Failure.Reason, 'EXECUTION_OR_GATE_FAILED');
});
