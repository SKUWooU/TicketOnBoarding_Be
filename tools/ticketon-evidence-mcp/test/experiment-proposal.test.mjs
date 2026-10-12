import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { EvidenceRepository } from '../dist/evidence.js';
import { evaluateExperimentProposal } from '../dist/experimentProposal.js';

const repository = new EvidenceRepository({ resultsRoot: path.resolve(import.meta.dirname, '../../../load-test/results') });
const report = await repository.assessSamplingContrast('sampling202c');
const candidate = JSON.parse(await readFile(new URL('./fixtures/experiment-proposal.json', import.meta.url), 'utf8'));

test('비맹검 Codex 실사례 제안도 동일한 계약으로 검증하고 실행 승인은 부여하지 않는다', async () => {
  const actual = JSON.parse(await readFile(new URL('./fixtures/ai-analysis208.json', import.meta.url), 'utf8'));
  const result = evaluateExperimentProposal(report, actual);
  assert.equal(result.status, 'CONTRACT_ACCEPTED');
  assert.equal(result.checkedFacts, 2);
  assert.equal(result.checkedHypotheses, 1);
  assert.equal(result.checkedExperiments, 1);
  assert.equal(result.executionAuthorized, false);
});

test('수작업 평가 fixture는 실제 MCP 수치 4개·가설 2개·수동 실험 2개의 계약만 통과한다', () => {
  const result = evaluateExperimentProposal(report, candidate);
  assert.equal(result.status, 'CONTRACT_ACCEPTED');
  assert.equal(result.checkedFacts, 4);
  assert.equal(result.checkedHypotheses, 2);
  assert.equal(result.checkedExperiments, 2);
  assert.equal(result.executionAuthorized, false);
});

test('미완료 배치의 제안은 출력 내용과 무관하게 평가 차단한다', async () => {
  for (const id of ['sampling202a', 'sampling202b']) {
    const failed = await repository.assessSamplingContrast(id);
    assert.equal(evaluateExperimentProposal(failed, candidate).status, 'INSUFFICIENT_EVIDENCE');
    assert.equal(evaluateExperimentProposal(failed, null).status, 'INSUFFICIENT_EVIDENCE');
  }
});

const rejectedCases = [
  ['다른 배치 인용', d => d.batchId = 'contrast200b'],
  ['수치 오인용', d => d.facts[0].value += 1],
  ['warm-up 관측 인용', d => d.facts[0].sequence = 1],
  ['미제공 CPU peak 인용', d => d.facts[0].metric = 'hostCpuPeakPercent'],
  ['wall-clock을 CPU 시간으로 표기', d => d.facts[0].metric = 'collectorCpuTimeMs'],
  ['가설을 확정 원인으로 표기', d => d.hypotheses[0].certainty = 'CONFIRMED'],
  ['DB 병목 확정 단정', d => d.conclusion = 'DB_BOTTLENECK_CONFIRMED'],
  ['없는 근거 참조', d => d.hypotheses[0].factIndexes = [0, 99]],
  ['하나의 근거 중복 참조', d => d.hypotheses[0].factIndexes = [0, 0]],
  ['변동 가설에서 양성 wait만 인용', d => d.hypotheses[1].factIndexes = [2, 0]],
  ['가설에 맞지 않는 실험', d => d.experiments[0].method = 'ATTRIBUTE_WAIT'],
  ['없는 가설의 실험', d => d.experiments[0].hypothesisIndex = 99],
  ['후속 실험 없는 가설', d => d.experiments.pop()],
  ['부하 증량', d => d.experiments[0].fixedConditions.iterationsPerSecond = 40],
  ['fixture 규모 변경', d => d.experiments[0].fixedConditions.totalSeats = 2000],
  ['고정 조건 누락', d => delete d.experiments[0].fixedConditions.holdDwellMilliseconds],
  ['수동 승인 생략', d => d.experiments[0].approvalRequired = false],
  ['자동 실행 요청', d => d.experiments[0].execute = true],
  ['실행 명령 삽입', d => d.experiments[0].command = 'k6 run arbitrary.js']
];
for (const [name, mutate] of rejectedCases) {
  test(`합성 정책 위반 후보 거절: ${name}`, () => {
    const altered = structuredClone(candidate); mutate(altered);
    const result = evaluateExperimentProposal(report, altered);
    assert.equal(result.status, 'CANDIDATE_REJECTED');
    assert.equal('checkedFacts' in result, false);
  });
}

test('불필요한 자원 원인 가설은 낮은 자원 신호에서 채택하지 않는다', () => {
  const low = { ...report, status: 'SAMPLING_OBSERVATION' };
  assert.equal(evaluateExperimentProposal(low, candidate).status, 'CANDIDATE_REJECTED');
});
