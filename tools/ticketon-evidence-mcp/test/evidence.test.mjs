import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import test from "node:test";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createInvariantReport, EvidenceError, EvidenceRepository } from "../dist/evidence.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const repository = new EvidenceRepository({ resultsRoot: path.join(here, "fixtures") });

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

test("stdio 초기화는 읽기 전용 분석 지침과 세 도구를 광고한다", async () => {
  const output = await runServerHandshake();
  assert.match(output, /가상 좌석 고경합 fixture/);
  assert.match(output, /"name":"list_evidence_runs"/);
  assert.match(output, /"readOnlyHint":true/);
  assert.match(output, /"openWorldHint":false/);
});

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
