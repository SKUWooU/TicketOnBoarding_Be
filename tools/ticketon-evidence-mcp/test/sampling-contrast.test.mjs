import assert from 'node:assert/strict';
import { readFile, mkdtemp, mkdir, writeFile, symlink, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { EvidenceRepository, EvidenceError } from '../dist/evidence.js';
import { assessSamplingContrast } from '../dist/samplingContrast.js';

const root = path.resolve(import.meta.dirname, '../../../load-test/results');
const repository = new EvidenceRepository({ resultsRoot: root });
const original = JSON.parse((await readFile(path.join(root, 'sampling202c/small-seat-sampling-contrast.json'), 'utf8')).replace(/^\uFEFF/, ''));

test('실측 schema 6은 warm-up 제외 관측만 제공하고 자원 재검토를 유지한다', async () => {
  const result = await repository.assessSamplingContrast('sampling202c');
  assert.equal(result.status, 'RESOURCE_REVIEW_REQUIRED');
  assert.equal(result.nextAction, 'MANUAL_REVIEW_ONLY');
  assert.equal(result.observations.length, 6);
  assert.equal(result.observations.reduce((sum, r) => sum + r.completed, 0), 1804);
  assert.deepEqual(result.observations.map(r => r.samplingIntervalMilliseconds), [1000, 2000, 2000, 1000, 1000, 2000]);
  assert.equal('observedDifference' in result, false);
  assert.equal('hostCpuPeakPercent' in result.observations[0], false);
  const listed = await repository.listRuns(50);
  assert.equal(listed.find(r => r.runId === 'sampling202c').samplingContrastAvailable, true);
  const extra = structuredClone(original); extra.jwt = 'SECRET_SENTINEL'; extra.Records[0].RawLog = 'SECRET_SENTINEL';
  assert.equal(JSON.stringify(assessSamplingContrast('sampling202c', extra)).includes('SECRET_SENTINEL'), false);
});

test('미완료 실측 두 배치는 부분 관측값도 노출하지 않는다', async () => {
  for (const id of ['sampling202a', 'sampling202b']) {
    const result = await repository.assessSamplingContrast(id);
    assert.equal(result.status, 'INSUFFICIENT_EVIDENCE');
    assert.equal('observations' in result, false);
  }
});

test('누락·조건 변경·계측 모순은 fail-closed 처리한다', () => {
  const mutations = [
    d => d.SchemaVersion = 5, d => d.SchemaVersion = 7, d => d.RunId = 'other', d => d.Failure = {},
    d => d.FixedSelectionSeed = 18, d => d.FixedTargetIterationsPerSecond = 30,
    d => d.FixedHotSeatCount = 10, d => d.FixedMaxVus = 21,
    d => d.WarmupRecords.pop(), d => d.Records.reverse(),
    d => d.Records[0].Measured = false, d => d.Records[0].SamplingIntervalMilliseconds = 2000,
    d => delete d.Records[0].CollectionMetrics, d => d.Records[0].CollectionMetrics.CollectionAverageMs += 1,
    d => d.Records[0].CollectionMetrics.CollectionMaxMs = 0,
    d => d.Records[0].WaitMetrics.HikariAcquireCount = undefined,
    d => d.Records[0].HostMetrics.HostCpuPeakPercent = NaN,
    d => d.Records[0].CollectionMetrics.CollectionTotalMs = Infinity,
    d => d.Environment.HostTotalPhysicalMemoryBytes = 0,
    d => d.ResourceReviewRequired = false
  ];
  for (const mutate of mutations) {
    const altered = structuredClone(original); mutate(altered);
    const result = assessSamplingContrast('sampling202c', altered);
    assert.equal(result.status, 'INSUFFICIENT_EVIDENCE');
    assert.equal('observations' in result, false);
  }
});

test('재고·pool·표본·메모리 게이트 위반은 증량 차단 판정만 반환한다', () => {
  for (const mutate of [
    d => d.Records[0].DroppedIterations = 1,
    d => d.Records[0].ReleaseSuccess -= 1,
    d => d.Records[0].DatabaseCounts[1] = 1,
    d => d.Records[0].WaitMetrics.MaxSampleGapMs = 3420,
    d => d.Records[0].WaitMetrics.HikariPendingPeak = 1,
    d => d.Records[0].WaitMetrics.DbDeadlocksDelta = 1,
    d => d.Records[0].HostMetrics.HostFreeMemoryMinimumKb = 2097151,
    d => d.WarmupRecords[0].UnexpectedFailures = 1,
    d => { d.Records[0].Iterations = 280; d.Records[0].ExpectedSeatConflicts = 172; d.Records[0].HotSelections = 186; }
  ]) {
    const altered = structuredClone(original); mutate(altered);
    const result = assessSamplingContrast('sampling202c', altered);
    assert.equal(result.status, 'STOP_ESCALATION');
    assert.equal('observations' in result, false);
  }
  const low = structuredClone(original);
  low.ResourceReviewRequired = false;
  for (const item of [...low.WarmupRecords, ...low.Records]) {
    item.HostMetrics.HostCpuPeakPercent = 60; item.ResourceReviewRequired = false;
  }
  assert.equal(assessSamplingContrast('sampling202c', low).status, 'SAMPLING_OBSERVATION');
});

test('누락·손상된 artifact와 디렉터리/파일 경로 이탈을 차단한다', async () => {
  await assert.rejects(() => repository.assessSamplingContrast('../sampling202c'), e => e instanceof EvidenceError && e.code === 'INVALID_RUN_ID');
  const temp = await mkdtemp(path.join(os.tmpdir(), 'ticketon-sampling-'));
  const outside = await mkdtemp(path.join(os.tmpdir(), 'ticketon-sampling-outside-'));
  try {
    const folder = path.join(temp, 'sample'); await mkdir(folder);
    const isolated = new EvidenceRepository({ resultsRoot: temp });
    assert.equal((await isolated.assessSamplingContrast('sample')).status, 'INSUFFICIENT_EVIDENCE');
    const artifact = path.join(folder, 'small-seat-sampling-contrast.json');
    await writeFile(artifact, '{bad');
    assert.equal((await isolated.assessSamplingContrast('sample')).status, 'INSUFFICIENT_EVIDENCE');
    await symlink(outside, path.join(temp, 'escaped'), 'junction');
    await assert.rejects(() => isolated.assessSamplingContrast('escaped'), e => e.code === 'RUN_NOT_FOUND');
    const external = path.join(outside, 'external.json'); await writeFile(external, '{}');
    await rm(artifact);
    // Windows file symlinks require extra privileges. An artifact junction also
    // exercises the canonical-path boundary before any file content is read.
    await symlink(process.platform === 'win32' ? outside : external, artifact,
      process.platform === 'win32' ? 'junction' : 'file');
    await assert.rejects(() => isolated.assessSamplingContrast('sample'), e => e.code === 'UNSUPPORTED_ARTIFACT');
  } finally {
    await rm(temp, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});

test('stdio 도구는 read-only로 실제 완료와 실패 배치를 판정한다', async () => {
  const client = new Client({ name: 'sampling-contract', version: '1' });
  const transport = new StdioClientTransport({ command: process.execPath, args: ['dist/index.js'], cwd: path.resolve(import.meta.dirname, '..') });
  try {
    await client.connect(transport);
    const listing = await client.listTools();
    const tool = listing.tools.find(t => t.name === 'assess_sampling_contrast');
    assert.equal(tool.annotations.readOnlyHint, true);
    assert.equal(tool.annotations.openWorldHint, false);
    for (const [id, status] of [['sampling202c', 'RESOURCE_REVIEW_REQUIRED'], ['sampling202b', 'INSUFFICIENT_EVIDENCE']]) {
      const result = await client.callTool({ name: tool.name, arguments: { batchId: id } });
      assert.equal(JSON.parse(result.content[0].text).status, status);
    }
  } finally { await client.close(); }
});
