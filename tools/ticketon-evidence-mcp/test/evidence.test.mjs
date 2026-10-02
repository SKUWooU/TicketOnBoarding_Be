import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import test from "node:test";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createInvariantReport, EvidenceError, EvidenceRepository } from "../dist/evidence.js";
import { compareControlledHotspotEvidence } from "../dist/comparison.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const repository = new EvidenceRepository({ resultsRoot: path.join(here, "fixtures") });
const measuredRoot = path.resolve(here, "../../../load-test/results");
const measuredRepository = new EvidenceRepository({ resultsRoot: measuredRoot });
const batchId = "c166-repeat";
const firstArtifact = "c166-repeat-r1-h20-summary.json";
const repeatArtifact = "c166-repeat-r2-h20-summary.json";
const variantArtifact = "c166-repeat-r1-h200-summary.json";

test("허용된 summary artifact만 목록과 함께 읽는다", async () => {
  const runs = await repository.listRuns();
  assert.deepEqual(runs.map((run) => run.runId), ["valid-run", "bom-run"]);
  assert.deepEqual(runs[0].summaryArtifacts, ["valid-run-r1-summary.json"]);

  const result = await repository.readSummary("valid-run");
  assert.equal(result.artifact, "valid-run-r1-summary.json");
  assert.equal(result.summary.K6.Result.jwt, undefined);
  assert.equal(result.summary.nested.authorization, undefined);
});

test("경로 이탈 runId와 원시 로그 artifact를 거절한다", async () => {
  await assert.rejects(() => repository.readSummary("../valid-run"), (error) => error instanceof EvidenceError && error.code === "INVALID_RUN_ID");
  await assert.rejects(() => repository.readSummary("valid-run", "raw-k6.stdout.log"), (error) => error instanceof EvidenceError && error.code === "UNSUPPORTED_ARTIFACT");
});

test("Windows junction이 결과 루트 밖을 가리키면 목록과 조회에서 제외한다", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "ticketon-evidence-root-"));
  const outside = await mkdtemp(path.join(os.tmpdir(), "ticketon-evidence-outside-"));
  const junction = path.join(root, "junction-run");
  try {
    await writeFile(path.join(outside, "junction-run-r1-summary.json"), '{"ValidMeasurement":true}');
    await symlink(outside, junction, "junction");
    const isolated = new EvidenceRepository({ resultsRoot: root });
    assert.deepEqual(await isolated.listRuns(), []);
    await assert.rejects(() => isolated.readSummary("junction-run"), (error) => error instanceof EvidenceError && error.code === "RUN_NOT_FOUND");
  } finally {
    await rm(junction, { force: true, recursive: true });
    await rm(root, { force: true, recursive: true });
    await rm(outside, { force: true, recursive: true });
  }
});

test("구성된 결과 루트 자체가 Windows junction이면 읽지 않는다", async () => {
  const parent = await mkdtemp(path.join(os.tmpdir(), "ticketon-evidence-parent-"));
  const outside = await mkdtemp(path.join(os.tmpdir(), "ticketon-evidence-outside-"));
  const junction = path.join(parent, "results");
  try {
    await mkdir(path.join(outside, "valid-run"));
    await writeFile(path.join(outside, "valid-run", "valid-run-r1-summary.json"), '{"ValidMeasurement":true}');
    await symlink(outside, junction, "junction");
    const isolated = new EvidenceRepository({ resultsRoot: junction });
    assert.deepEqual(await isolated.listRuns(), []);
    await assert.rejects(() => isolated.readSummary("valid-run"), (error) => error instanceof EvidenceError && error.code === "RUN_NOT_FOUND");
  } finally {
    await rm(junction, { force: true, recursive: true });
    await rm(parent, { force: true, recursive: true });
    await rm(outside, { force: true, recursive: true });
  }
});

test("요약의 불변식이 모두 충족될 때 PASS를 반환한다", async () => {
  const { summary } = await repository.readSummary("valid-run");
  assert.deepEqual(createInvariantReport(summary).status, "PASS");
});

test("PowerShell 결과의 UTF-8 BOM도 안전하게 읽는다", async () => {
  const result = await repository.readSummary("bom-run");
  assert.equal(result.summary.ValidMeasurement, true);
});

test("불완전한 요약은 PASS로 과장하지 않는다", () => {
  assert.equal(createInvariantReport({ ValidMeasurement: true }).status, "INSUFFICIENT_EVIDENCE");
});

test("실제 통제 배치의 같은 조건 반복은 관측 차이만 제공한다", async () => {
  const result = await measuredRepository.compareControlledHotspot(batchId, firstArtifact, repeatArtifact);
  assert.equal(result.status, "COMPARABLE");
  assert.equal(result.comparisonType, "SAME_CONDITION_REPEAT");
  assert.equal(result.controlledConditions.totalSeats, 2000);
  assert.equal(result.observations[0].reservationSuccess, 159);
  assert.equal(result.observations[1].reservationSuccess, 159);
  assert.equal(result.observedDifferenceSecondMinusFirst.reservationSuccess, 0);
  assert.match(result.limitations.join(" "), /인과적 성능 개선/);
});

test("인기 좌석 수만 다른 실제 배치는 단일 변수 비교로 표시한다", async () => {
  const result = await measuredRepository.compareControlledHotspot(batchId, firstArtifact, variantArtifact);
  assert.equal(result.status, "COMPARABLE");
  assert.equal(result.comparisonType, "HOT_SEAT_COUNT_ONLY");
  assert.deepEqual(result.observations.map((item) => item.hotSeatCount), [20, 200]);
  assert.equal(result.observedDifferenceSecondMinusFirst.reservationSuccess, 148);
});

test("manifest 불일치·dropped iteration·조건 변경은 수치 비교를 차단한다", async () => {
  const { manifest, first, second } = await controlledFixture();
  const missing = structuredClone(manifest);
  missing.Complete = false;
  assert.equal(compareControlledHotspotEvidence(batchId, missing, firstArtifact, first, repeatArtifact, second).status, "INSUFFICIENT_EVIDENCE");
  const unlisted = structuredClone(manifest);
  unlisted.Records = unlisted.Records.filter((record) => record.SummaryFile !== repeatArtifact);
  assert.equal(compareControlledHotspotEvidence(batchId, unlisted, firstArtifact, first, repeatArtifact, second).status, "INSUFFICIENT_EVIDENCE");
  const dropped = structuredClone(second);
  dropped.K6.Result.DroppedIterations = 1;
  const droppedResult = compareControlledHotspotEvidence(batchId, manifest, firstArtifact, first, repeatArtifact, dropped);
  assert.equal(droppedResult.status, "NOT_COMPARABLE");
  assert.equal("observations" in droppedResult, false);
  const changed = structuredClone(second);
  changed.Run.RatePerSecond = 75;
  changed.K6.Result.TargetRatePerSecond = 75;
  const changedResult = compareControlledHotspotEvidence(batchId, manifest, firstArtifact, first, repeatArtifact, changed);
  assert.equal(changedResult.status, "NOT_COMPARABLE");
  assert.equal("observedDifferenceSecondMinusFirst" in changedResult, false);
  const mismatchedP95 = structuredClone(second);
  mismatchedP95.K6.Result.ReservationSuccessDurationMs.P95 += 10;
  assert.equal(compareControlledHotspotEvidence(batchId, manifest, firstArtifact, first, repeatArtifact, mismatchedP95).status, "INSUFFICIENT_EVIDENCE");
  const changedObserver = structuredClone(second);
  changedObserver.Run.SampleIntervalMilliseconds = 500;
  assert.equal(compareControlledHotspotEvidence(batchId, manifest, firstArtifact, first, repeatArtifact, changedObserver).status, "NOT_COMPARABLE");
  assert.equal(compareControlledHotspotEvidence(batchId, manifest, firstArtifact, first, firstArtifact, first).status, "NOT_COMPARABLE");
});

test("정리 전 초기 재고·digest 관측이 불완전하면 근거 부족으로 차단한다", async () => {
  const { manifest, first, second } = await controlledFixture();
  const noFresh = structuredClone(second);
  delete noFresh.FixturePreparation.FreshFixture;
  assert.equal(compareControlledHotspotEvidence(batchId, manifest, firstArtifact, first, repeatArtifact, noFresh).status, "INSUFFICIENT_EVIDENCE");
  const lostDigest = structuredClone(second);
  lostDigest.DatabaseStatementDigests.InstrumentationHealth.PerformanceSchemaDigestLost = 1;
  assert.equal(compareControlledHotspotEvidence(batchId, manifest, firstArtifact, first, repeatArtifact, lostDigest).status, "NOT_COMPARABLE");
});

test("raw SQL counts must agree with coverage and completed bookings", async () => {
  const { manifest, first, second } = await controlledFixture();
  for (const field of ["SeatLockSelect", "ConcertTimeDecrement"]) {
    const altered = structuredClone(second);
    altered.DatabaseStatementDigests[field].Count = 0;
    const result = compareControlledHotspotEvidence(batchId, manifest, firstArtifact, first, repeatArtifact, altered);
    assert.equal(result.status, "NOT_COMPARABLE");
    assert.equal("observations" in result, false);
  }
});

test("a run missing half its scheduled arrivals cannot be compared", async () => {
  const { manifest, first, second } = await controlledFixture();
  const altered = structuredClone(second);
  const alteredManifest = structuredClone(manifest);
  altered.K6.Result.Iterations = 250;
  altered.K6.Result.ExpectedContention = 250 - altered.K6.Result.ReservationSuccess;
  altered.K6.Result.ScheduledIterationAttainmentRate = 0.5;
  altered.K6.Result.CompletedIterationsPerScheduledSecond = 25;
  alteredManifest.Records.find((entry) => entry.SummaryFile === repeatArtifact).SeatConflicts = altered.K6.Result.ExpectedContention;
  const result = compareControlledHotspotEvidence(batchId, alteredManifest, firstArtifact, first, repeatArtifact, altered);
  assert.equal(result.status, "NOT_COMPARABLE");
  assert.equal("observedDifferenceSecondMinusFirst" in result, false);
});

test("a zero-rate run with internally matching zero counts cannot expose p95 differences", async () => {
  const { manifest, first, second } = await controlledFixture();
  const altered = structuredClone(second);
  const alteredManifest = structuredClone(manifest);
  const result = altered.K6.Result;
  const digest = altered.DatabaseStatementDigests;
  const final = altered.K6.FinalSnapshot;
  const entry = alteredManifest.Records.find((record) => record.SummaryFile === repeatArtifact);
  altered.Run.RatePerSecond = 0;
  result.TargetRatePerSecond = 0;
  result.Iterations = 0;
  result.ScheduledIterationAttainmentRate = 1;
  result.CompletedIterationsPerScheduledSecond = 0;
  result.ReservationSuccess = 0;
  result.ExpectedContention = 0;
  result.ReservationSuccessDurationMs.P95 = 0;
  result.ReservationSeatContentionDurationMs.P95 = 0;
  Object.assign(final, { remainingSeats: 2000, reservedSeats: 0, reservations: 0, bookings: 0, payments: 0 });
  Object.assign(digest.SeatLockSelect, { Count: 0 });
  Object.assign(digest.ConcertTimeDecrement, { Count: 0, RowsAffected: 0 });
  Object.assign(digest.Coverage, {
    ExpectedSeatLockSelects: 0, SeatLockSelectCount: 0, SeatLockSelectRate: 1,
    ExpectedSuccessfulReservations: 0, ConcertTimeDecrementCount: 0, ConcertTimeDecrementRate: 1
  });
  Object.assign(entry, { Success: 0, SeatConflicts: 0, SuccessP95Ms: 0, ConflictP95Ms: 0 });
  const comparison = compareControlledHotspotEvidence(batchId, alteredManifest, firstArtifact, first, repeatArtifact, altered);
  assert.equal(comparison.status, "NOT_COMPARABLE");
  assert.equal("observedDifferenceSecondMinusFirst" in comparison, false);
});

test("MCP 비교도 임의 경로와 허용 밖 artifact를 읽지 않는다", async () => {
  await assert.rejects(() => measuredRepository.compareControlledHotspot("../c166-repeat", firstArtifact, repeatArtifact),
    (error) => error instanceof EvidenceError && error.code === "INVALID_RUN_ID");
  await assert.rejects(() => measuredRepository.compareControlledHotspot(batchId, "raw-k6.stdout.log", repeatArtifact),
    (error) => error instanceof EvidenceError && error.code === "UNSUPPORTED_ARTIFACT");
});

test("stdio 초기화는 읽기 전용 분석 지침과 네 도구를 광고한다", async () => {
  const output = await runServerHandshake();
  assert.match(output, /가상 좌석 고경합 fixture/);
  assert.match(output, /"name":"list_evidence_runs"/);
  assert.match(output, /"name":"compare_controlled_hotspot_runs"/);
  assert.match(output, /"readOnlyHint":true/);
  assert.match(output, /"openWorldHint":false/);
});

test("stdio MCP 호출에서도 실제 통제 배치의 비교 판정을 반환한다", async () => {
  const output = await runServerCall("compare_controlled_hotspot_runs", {
    batchId,
    firstArtifact,
    secondArtifact: repeatArtifact
  });
  const response = output.split("\n").filter(Boolean).map((line) => JSON.parse(line)).find((line) => line.id === 3);
  assert.equal(response?.result?.isError, undefined);
  const report = JSON.parse(response.result.content[0].text);
  assert.equal(report.status, "COMPARABLE");
  assert.equal(report.comparisonType, "SAME_CONDITION_REPEAT");
  assert.equal(report.observations.length, 2);
});

async function controlledFixture() {
  const folder = path.join(measuredRoot, batchId);
  const [manifest, first, second] = await Promise.all([
    "controlled-hotspot-manifest.json", firstArtifact, repeatArtifact
  ].map(async (name) => JSON.parse((await readFile(path.join(folder, name), "utf8")).replace(/^\uFEFF/, ""))));
  return { manifest, first, second };
}

function runServerHandshake() {
  return new Promise((resolve, reject) => {
    const server = spawn(process.execPath, ["dist/index.js"], { cwd: path.resolve(here, "..") });
    let output = "";
    server.stdout.on("data", (chunk) => { output += chunk; });
    server.on("error", reject);
    server.on("close", () => resolve(output));
    server.stdin.end([
      JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "contract-test", version: "1" } } }),
      JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized", params: {} }),
      JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} })
    ].join("\n") + "\n");
  });
}

function runServerCall(name, args) {
  return new Promise((resolve, reject) => {
    const server = spawn(process.execPath, ["dist/index.js"], { cwd: path.resolve(here, "..") });
    let output = "";
    server.stdout.on("data", (chunk) => { output += chunk; });
    server.on("error", reject);
    server.on("close", () => resolve(output));
    server.stdin.end([
      JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "contract-test", version: "1" } } }),
      JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized", params: {} }),
      JSON.stringify({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name, arguments: args } })
    ].join("\n") + "\n");
  });
}
