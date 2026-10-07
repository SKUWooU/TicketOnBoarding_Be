import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { preflightScenario, scenarios } from "../dist/preflight.js";

const here = path.dirname(fileURLToPath(import.meta.url));

const ready = (batchId = "next176", composeProject = "ticketon-controlled172-r3", rate = 50) => ({
  Status: "READY", Scenario: "weighted-hotspot-churn", BatchId: batchId,
  ComposeProject: composeProject, Rate: rate,
  DurationSeconds: 10, Repeats: 2, HoldDwellMilliseconds: 100,
  PlannedRuns: [20, 40, 200, 200, 40, 20].map((count, index) =>
    `${batchId}-r${index < 3 ? 1 : 2}-h${count}`),
  Checks: ["loopback", "dedicated_compose_label", "empty_application_tables",
    "backend_database_identity", "bounded_load", "unused_result_path"]
});

test("카탈로그는 고정된 50/100 RPS 실험만 제공한다", () => {
  assert.deepEqual(scenarios.map((item) => item.id), ["hold-churn-50", "hold-churn-100"]);
  assert.deepEqual(scenarios.map((item) => item.rate), [50, 100]);
});

test("고정 runner를 CheckOnly와 제한된 인자로만 호출한다", async () => {
  let invocation;
  const run = async (file, args, options) => {
    invocation = { file, args, options };
    return { stdout: JSON.stringify(ready()), stderr: "" };
  };
  const result = await preflightScenario("hold-churn-50", "next176", "ticketon-controlled172-r3", run);
  assert.equal(result.status, "READY");
  assert.equal(invocation.file, "powershell.exe");
  assert.ok(invocation.args.includes("-CheckOnly"));
  assert.equal(invocation.args[invocation.args.indexOf("-Rate") + 1], "50");
  assert.equal(invocation.args[invocation.args.indexOf("-DurationSeconds") + 1], "10");
  assert.equal(invocation.options.timeout, 30000);
  assert.equal(invocation.options.maxBuffer, 16384);
  assert.equal(invocation.args.includes("-BaseUrl"), false);
});

test("임의 시나리오·배치 경로·Compose 이름은 프로세스 호출 전에 차단한다", async () => {
  const never = async () => { throw Error("must not run"); };
  for (const args of [
    ["hold-churn-1000", "next176", "ticketon-controlled172-r3"],
    ["hold-churn-50", "../outside", "ticketon-controlled172-r3"],
    ["hold-churn-50", "next176", "production"]
  ]) {
    assert.equal((await preflightScenario(...args, never)).status, "NOT_READY");
  }
});

test("실패 stderr와 잘못된 JSON은 MCP 결과에 노출하지 않는다", async () => {
  const rejected = async () => { throw Error("SECRET_DB_PASSWORD"); };
  const failed = await preflightScenario("hold-churn-50", "next176", "ticketon-controlled172-r3", rejected);
  assert.deepEqual(failed, { status: "NOT_READY", reason: "LOCAL_PREFLIGHT_FAILED" });
  assert.equal(JSON.stringify(failed).includes("SECRET"), false);
  const invalid = async () => ({ stdout: "garbage", stderr: "SECRET" });
  assert.equal((await preflightScenario("hold-churn-50", "next176", "ticketon-controlled172-r3", invalid)).status, "NOT_READY");
});

test("runner 결과가 요청한 배치·속도와 다르면 READY로 반환하지 않는다", async () => {
  const mismatch = async () => ({ stdout: JSON.stringify(ready("other", "ticketon-controlled172-r3", 100)), stderr: "" });
  assert.deepEqual(await preflightScenario("hold-churn-50", "next176", "ticketon-controlled172-r3", mismatch),
    { status: "NOT_READY", reason: "INVALID_PREFLIGHT_RESULT" });
});

test("PowerShell CheckOnly 분기는 fixture·출력 생성보다 먼저 반환한다", async () => {
  const script = await readFile(path.resolve(here, "../../../load-test/scripts/Run-ControlledSeatHoldChurn.ps1"), "utf8");
  const branch = script.indexOf("if ($CheckOnly)");
  assert.ok(branch > script.indexOf("Assert-SeatHoldDedicatedDatabaseIdentity"));
  assert.ok(branch > script.indexOf("Test-Path -LiteralPath $output"));
  const returnPoint = script.slice(branch).search(/    return\r?\n}/) + branch;
  assert.ok(returnPoint > branch);
  assert.ok(script.indexOf("New-Item -ItemType Directory", branch) > returnPoint);
  assert.equal(/New-Item|Set-Content|& \$measure/.test(script.slice(0, branch)), false);
});
