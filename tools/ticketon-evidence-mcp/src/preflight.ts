import { execFile as nodeExecFile } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const execFile = promisify(nodeExecFile);
const here = path.dirname(fileURLToPath(import.meta.url));
const runner = path.resolve(here, "../../../load-test/scripts/Run-ControlledSeatHoldChurn.ps1");
const batchIdPattern = /^[A-Za-z0-9-]{1,16}$/;
const projectPattern = /^ticketon-controlled172(-[a-z0-9]{1,12})?$/;

export const scenarios = Object.freeze([
  Object.freeze({ id: "hold-churn-50", rate: 50, durationSeconds: 10, repeats: 2, holdDwellMilliseconds: 100, hotSeatCounts: [20, 40, 200] }),
  Object.freeze({ id: "hold-churn-100", rate: 100, durationSeconds: 10, repeats: 2, holdDwellMilliseconds: 100, hotSeatCounts: [20, 40, 200] })
]);

type Runner = (file: string, args: string[], options: { cwd: string; timeout: number; maxBuffer: number; windowsHide: boolean }) => Promise<{ stdout: string; stderr: string }>;

export async function preflightScenario(scenarioId: string, batchId: string, composeProject: string, run: Runner = execFile) {
  const scenario = scenarios.find((item) => item.id === scenarioId);
  if (!scenario) return { status: "NOT_READY", reason: "UNKNOWN_SCENARIO" };
  if (!batchIdPattern.test(batchId) || !projectPattern.test(composeProject)) {
    return { status: "NOT_READY", reason: "INVALID_IDENTIFIER" };
  }
  if (process.platform !== "win32" && run === execFile) {
    return { status: "NOT_READY", reason: "WINDOWS_POWERSHELL_REQUIRED" };
  }
  const args = ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", runner,
    "-CheckOnly", "-BatchId", batchId, "-ComposeProject", composeProject,
    "-Rate", String(scenario.rate), "-DurationSeconds", String(scenario.durationSeconds),
    "-Repeats", String(scenario.repeats), "-HoldDwellMilliseconds", String(scenario.holdDwellMilliseconds)];
  try {
    const { stdout } = await run("powershell.exe", args, {
      cwd: path.resolve(here, "../../.."), timeout: 30000, maxBuffer: 16384, windowsHide: true
    });
    const response = JSON.parse(stdout.replace(/^\uFEFF/, ""));
    const expectedRuns = [20, 40, 200, 200, 40, 20].map((count, index) =>
      `${batchId}-r${index < 3 ? 1 : 2}-h${count}`);
    if (response.Status !== "READY" || response.Scenario !== "weighted-hotspot-churn" ||
        response.BatchId !== batchId || response.ComposeProject !== composeProject ||
        response.Rate !== scenario.rate || response.DurationSeconds !== scenario.durationSeconds ||
        response.Repeats !== scenario.repeats || response.HoldDwellMilliseconds !== scenario.holdDwellMilliseconds ||
        JSON.stringify(response.PlannedRuns) !== JSON.stringify(expectedRuns)) {
      return { status: "NOT_READY", reason: "INVALID_PREFLIGHT_RESULT" };
    }
    return { status: "READY", scenarioId, batchId, composeProject,
      rate: scenario.rate, durationSeconds: scenario.durationSeconds,
      plannedRuns: expectedRuns,
      checks: ["loopback", "dedicated_compose_label", "empty_application_tables",
        "backend_database_identity", "bounded_load", "unused_result_path"],
      note: "준비 상태만 확인했습니다. 부하·fixture 실행은 수행하지 않았습니다." };
  } catch {
    return { status: "NOT_READY", reason: "LOCAL_PREFLIGHT_FAILED" };
  }
}
