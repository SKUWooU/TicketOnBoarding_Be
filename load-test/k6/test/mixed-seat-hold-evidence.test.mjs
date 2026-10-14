import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('resource-cleared retry stops before large requests without implying a completed comparison', () => {
  const evidence = JSON.parse(readFileSync(new URL('../../results/mixed226a-comparison.json', import.meta.url), 'utf8').replace(/^\uFEFF/, ''));
  assert.equal(evidence.Complete, false);
  assert.equal(evidence.FixtureRunId, 'mixed226-fixture');
  assert.match(evidence.Failure, /host CPU peak >=90%/);
  assert.equal(evidence.Records.length, 1);
  const record = evidence.Records[0]; const summary = record.Summary;
  assert.equal(record.Warmup, true);
  assert.equal(summary.FixtureRunId, evidence.FixtureRunId);
  assert.ok(summary.AvailableHostMemoryBytesBeforeRun >= 2 * 1024 ** 3);
  assert.ok(summary.Metrics.Peaks.SystemCpuUsage >= 0.9);
  assert.equal(summary.K6.mode, 'small-only');
  assert.equal(summary.K6.duration, '5s');
  assert.equal(summary.K6.classes.small.attempts, 50);
  assert.equal(summary.K6.classes.small.success, 50);
  assert.equal(summary.K6.classes.small.released, 50);
  assert.equal(summary.K6.classes.small.failures, 0);
  assert.equal(summary.K6.classes.small.conflicts, 0);
  assert.equal(summary.K6.classes.large.attempts, 0);
  assert.equal(summary.K6.droppedIterations, 0);
  for (const key of ['DbRowLockWaits','DbRowLockTimeMs','DbDeadlocks']) assert.equal(summary.Metrics.Deltas[key], 0);
  assert.equal(summary.Snapshot.invariantSatisfied, true);
  assert.equal(summary.Snapshot.holdRows, 0);
});

test('resource-stopped mixed batches retain warm-ups without claiming a completed comparison', () => {
  let attempts = 0; let success = 0; let conflicts = 0;
  for (const [batch, recordCount] of [['mixed224a', 4], ['mixed224b', 3]]) {
    const evidence = JSON.parse(readFileSync(new URL(`../../results/${batch}-comparison.json`, import.meta.url), 'utf8').replace(/^\uFEFF/, ''));
    assert.equal(evidence.Complete, false);
    assert.match(evidence.Failure, /CPU peak >=90%/);
    assert.equal(evidence.Records.length, recordCount);
    assert.equal(evidence.Records.filter(r => !r.Warmup).length, 0);
    assert.equal(evidence.SmallIterationsPerSecond, 10);
    assert.equal(evidence.LargeIterationsPerSecond, 2);
    for (const record of evidence.Records) {
      const summary = record.Summary;
      assert.equal(summary.ValidMeasurement, true);
      assert.equal(summary.K6.duration, '5s');
      assert.equal(summary.K6.droppedIterations, 0);
      assert.equal(summary.Metrics.Deltas.DbDeadlocks, 0);
      assert.equal(summary.Snapshot.invariantSatisfied, true);
      assert.equal(summary.Snapshot.holdRows, 0);
      for (const kind of ['small', 'large']) {
        const result = summary.K6.classes[kind];
        assert.equal(result.attempts, result.success + result.conflicts);
        assert.equal(result.success, result.released);
        assert.equal(result.failures, 0);
        attempts += result.attempts; success += result.success; conflicts += result.conflicts;
      }
    }
    assert.ok(evidence.Records.at(-1).Summary.Metrics.Peaks.SystemCpuUsage >= 0.9);
  }
  assert.equal(attempts, 410); assert.equal(success, 385); assert.equal(conflicts, 25);
});
