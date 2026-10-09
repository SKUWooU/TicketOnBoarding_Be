type Row = Record<string, unknown>;

const SCOPE = "local virtual 20-seat fixture; not production performance";
const LIMITATIONS = [
  "One sequential 20→30 RPS probe cannot establish a stable capacity range or a latency improvement.",
  "The MariaDB row-lock wait counter is global and does not identify a specific SQL statement.",
  "Local virtual seats and a Mock environment do not represent production ticketing throughput."
];

function record(value: unknown): Row {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Row : {};
}

function natural(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function finite(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

function insufficient(reason: string) {
  return { status: "INSUFFICIENT_EVIDENCE" as const, nextAction: "DO_NOT_ESCALATE" as const,
    reasons: [reason], limitations: LIMITATIONS };
}

function stop(reason: string) {
  return { status: "STOP_ESCALATION" as const, nextAction: "INVESTIGATE_OR_REDUCE_LOAD" as const,
    reasons: [reason], limitations: LIMITATIONS };
}

export function assessSmallSeatProbe(batchId: string, value: unknown) {
  const manifest = record(value);
  const records = manifest.Records;
  if (manifest.SchemaVersion !== 3 || manifest.Scope !== SCOPE || manifest.RunId !== batchId ||
      manifest.Complete !== true || manifest.Repeats !== 1 || manifest.Probe30 !== true ||
      manifest.FixedHotSeatCount !== 1 || manifest.FixedHoldDwellMilliseconds !== 500 ||
      manifest.FixedHotRequestPercent !== 70 || !Array.isArray(records) || records.length !== 2 ||
      typeof manifest.ComposeProject !== "string" ||
      !/^ticketon-controlled172(?:-[a-z0-9]{1,12})?$/.test(manifest.ComposeProject)) {
    return insufficient("The complete fixed-condition 20→30 RPS probe manifest is missing or invalid.");
  }

  const observations = [];
  for (const [index, value] of records.entries()) {
    const item = record(value);
    const wait = record(item.WaitMetrics);
    const rate = index === 0 ? 20 : 30;
    const integers = [item.Sequence, item.Round, item.TargetRps, item.DurationSeconds,
      item.HotSeatCount, item.HoldDwellMilliseconds, item.Iterations, item.HoldSuccess,
      item.ReleaseSuccess, item.ExpectedSeatConflicts, item.DroppedIterations,
      item.UnexpectedFailures, item.FinalHoldRows, item.DbDeadlocksDelta,
      wait.SampleCount, wait.MaxSampleGapMs, wait.HikariPendingPeak, wait.HikariActivePeak,
      wait.HikariMax, wait.HikariAcquireCount, wait.HikariTimeoutDelta,
      wait.DbRowLockCurrentWaitsPeak, wait.DbRowLockWaitsDelta,
      wait.DbRowLockTimeMsDelta, wait.DbDeadlocksDelta];
    const counts = item.DatabaseCounts;
    if (integers.some((number) => !natural(number)) ||
        !finite(item.HoldSuccessP95Ms) || !finite(item.SeatConflictP95Ms) ||
        !finite(wait.HikariAcquireWaitAverageMs) ||
        !Array.isArray(counts) || counts.length !== 5 || counts.some((number) => !natural(number))) {
      return insufficient(`Stage ${rate} RPS is missing a valid measurement or final inventory count.`);
    }
    if (item.Sequence !== index + 1 || item.Round !== 1 || item.TargetRps !== rate ||
        item.DurationSeconds !== 10 || item.HotSeatCount !== 1 || item.HoldDwellMilliseconds !== 500) {
      return insufficient(`Stage ${rate} RPS does not match the fixed probe order and conditions.`);
    }
    if (Number(item.Iterations) < rate * 10 * 0.95 || Number(item.Iterations) > rate * 10 * 1.02 ||
        Number(item.HoldSuccess) <= 0 || Number(item.ExpectedSeatConflicts) <= 0 ||
        Number(item.HoldSuccess) + Number(item.ExpectedSeatConflicts) !== item.Iterations ||
        item.HoldSuccess !== item.ReleaseSuccess || item.DroppedIterations !== 0 ||
        item.UnexpectedFailures !== 0 || item.FinalHoldRows !== 0 || item.DbDeadlocksDelta !== 0 ||
        wait.DbDeadlocksDelta !== 0 || wait.HikariTimeoutDelta !== 0 ||
        Number(wait.SampleCount) < 5 || Number(wait.MaxSampleGapMs) > 3000 ||
        Number(wait.HikariAcquireCount) <= 0 || Number(wait.HikariActivePeak) >= Number(wait.HikariMax) ||
        Number(wait.HikariPendingPeak) > 0 || counts.join(",") !== "20,0,0,20,0") {
      return stop(`Stage ${rate} RPS failed completion, Hold/Release, sampling, pool or inventory gates.`);
    }
    observations.push({ ratePerSecond: rate, completed: item.Iterations,
      holdAndRelease: item.HoldSuccess, expectedSeatConflicts: item.ExpectedSeatConflicts,
      hikariPendingPeak: wait.HikariPendingPeak, globalDbRowLockWaits: wait.DbRowLockWaitsDelta });
  }
  return { status: "REPEAT_REQUIRED" as const, nextAction: "REPEAT_20_30_WITH_HOST_TELEMETRY" as const,
    batchId, controlledConditions: { totalSeats: 20, hotSeatCount: 1, hotRequestPercent: 70,
      holdDwellMilliseconds: 500, durationSecondsPerStage: 10, rateOrder: [20, 30] },
    observations, missingEvidence: ["cross-order repetitions", "host CPU and free-memory time series"],
    note: "The existing probe passed safety gates, but 30 RPS stability and p95 improvement are unproven.",
    limitations: LIMITATIONS };
}

export function insufficientSmallSeatProbe(reason: string) {
  return insufficient(reason);
}
