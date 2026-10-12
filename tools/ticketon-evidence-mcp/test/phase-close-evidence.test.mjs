import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { EvidenceRepository } from '../dist/evidence.js';

const root = path.resolve(import.meta.dirname, '../../../load-test/results');
const read = async (id, name) => JSON.parse((await readFile(path.join(root, id, name), 'utf8')).replace(/^\uFEFF/, ''));

test('직렬/병렬 probe의 commit 표시와 독립 DB 값은 SQL 집계 부족과 구분한다', async () => {
  const probe = await read('sqlprobe210a', 'sql-counter-probe.json');
  assert.equal(probe.Complete, true);
  assert.equal(probe.Failure, null);
  assert.equal(probe.SqlTextIncluded, false);
  assert.equal(probe.Records.length, 6);
  assert.deepEqual(probe.Records.map(r => r.Mode), ['serial', 'parallel', 'parallel', 'serial', 'serial', 'parallel']);
  assert.equal(probe.Records.reduce((sum, r) => sum + r.CompletedTransactions, 0), 600);
  for (const [index, row] of probe.Records.entries()) {
    assert.equal(row.Sequence, index + 1);
    assert.equal(row.Workers, 4);
    assert.equal(row.CyclesPerWorker, 25);
    assert.equal(row.CompletedTransactions, 100);
    assert.deepEqual(row.DurableCounters, Array(4).fill(25 * (index + 1)));
    assert.equal(row.ExpectedLockSelects, 100);
    assert.equal(row.ExpectedSeatUpdates, 100);
    assert.deepEqual(row.SqlOperationDeltas.map(r => r.Operation), ['seat-lock-select', 'seat-update']);
    assert.ok(row.SqlOperationDeltas.every(r => r.SqlErrors === 0));
    const matches = row.SqlOperationDeltas.every(r => r.Executions === 100);
    assert.equal(row.Coverage, matches ? 'COUNTERS_MATCH_WORKLOAD' : 'SQL_COUNTER_COVERAGE_MISMATCH');
  }
  assert.deepEqual(probe.Records.map(r => r.SqlOperationDeltas[0].Executions), [100, 97, 95, 100, 100, 94]);
  assert.deepEqual(probe.Records.map(r => r.SqlOperationDeltas[1].Executions), [100, 100, 98, 100, 100, 100]);
});

test('예매 경합 복귀는 기존 schema 6 판정으로 읽고 probe를 성능 근거로 섞지 않는다', async () => {
  const manifest = await read('return210a', 'small-seat-sampling-contrast.json');
  const report = await new EvidenceRepository({ resultsRoot: root }).assessSamplingContrast('return210a');
  assert.equal(manifest.SchemaVersion, 6);
  assert.equal('SqlDiagnostics' in manifest, false);
  assert.equal(manifest.FixedTargetIterationsPerSecond, 20);
  assert.equal(manifest.Complete, true);
  assert.equal(manifest.Failure, null);
  assert.equal(report.status, 'RESOURCE_REVIEW_REQUIRED');
  assert.equal(report.nextAction, 'MANUAL_REVIEW_ONLY');
  assert.equal(report.observations.length, 6);
  assert.equal(report.observations.reduce((sum, r) => sum + r.completed, 0), 1804);
  assert.equal(report.observations.reduce((sum, r) => sum + r.holdAndRelease, 0), 647);
  assert.equal(report.observations.reduce((sum, r) => sum + r.expectedSeatConflicts, 0), 1157);
  assert.equal('observedDifference' in report, false);
  assert.equal(JSON.stringify(report).includes('sqlprobe210a'), false);
});
