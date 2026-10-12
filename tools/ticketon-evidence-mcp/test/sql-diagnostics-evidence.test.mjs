import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { assessSamplingContrast } from '../dist/samplingContrast.js';

test('SQL 진단의 미완료 실측은 완료 배치나 기존 schema 6 근거로 사용하지 않는다', async () => {
  for (const id of ['sql208a', 'sql208b']) {
    const file = new URL(`../../../load-test/results/${id}/small-seat-sql-diagnostics.json`, import.meta.url);
    const actual = JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''));
    assert.equal(actual.SchemaVersion, 7);
    assert.equal(actual.Complete, false);
    assert.equal(actual.Records.length, 0);
    assert.equal(actual.WarmupRecords.length, 0);
    assert.equal(actual.Failure.Sequence, 1);
    assert.equal(actual.SqlDiagnostics.SqlTextIncluded, false);
    const result = assessSamplingContrast(id, actual);
    assert.equal(result.status, 'INSUFFICIENT_EVIDENCE');
    assert.equal('observations' in result, false);
    if (id === 'sql208b') {
      assert.deepEqual(actual.Failure.SqlCoverage, {
        ExpectedLockSelects: 408, ObservedLockSelects: 404,
        ExpectedSeatUpdates: 214, ObservedSeatUpdates: 213,
      });
    }
  }
});
