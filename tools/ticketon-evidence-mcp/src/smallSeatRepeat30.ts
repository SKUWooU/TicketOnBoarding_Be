type Row = Record<string, unknown>;

const ORDER = [20, 30, 30, 20, 20, 30];
const SCOPE = "local virtual 20-seat fixture; not production performance";
const LIMITATIONS = [
  "Six short local runs are repeated observations, not production capacity or an SLA.",
  "Host CPU peak is a coarse system-wide sample; it does not attribute load to the Backend or MariaDB.",
  "MariaDB row-lock waits are global counters and do not identify a specific SQL statement.",
  "20-seat and 2,000-seat fixture results, or results from different hosts, are not before/after performance comparisons."
];

function row(value: unknown): Row {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Row : {};
}

function natural(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function finite(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

function noEvidence(reason: string) {
  return { status: "INSUFFICIENT_EVIDENCE" as const, nextAction: "DO_NOT_ESCALATE" as const,
    reasons: [reason], limitations: LIMITATIONS };
}

function stop(reason: string) {
  return { status: "STOP_ESCALATION" as const, nextAction: "INVESTIGATE_OR_REDUCE_LOAD" as const,
    reasons: [reason], limitations: LIMITATIONS };
}

export function assessSmallSeatRepeat30(batchId: string, value: unknown) {
  const manifest = row(value);
  const records = manifest.Records;
  if (manifest.SchemaVersion !== 4 || manifest.Scope !== SCOPE || manifest.RunId !== batchId ||
      manifest.Complete !== true || manifest.Repeats !== 3 || manifest.Probe30 !== false ||
      manifest.Repeat30 !== true || manifest.FixedHotSeatCount !== 1 ||
      manifest.FixedHoldDwellMilliseconds !== 500 || manifest.FixedHotRequestPercent !== 70 ||
      !Array.isArray(records) || records.length !== ORDER.length ||
      typeof manifest.ComposeProject !== "string" ||
      !/^ticketon-controlled172(?:-[a-z0-9]{1,12})?$/.test(manifest.ComposeProject)) {
    return noEvidence("The complete six-stage, fixed-condition 20/30 RPS manifest is missing or invalid.");
  }

  const observations = [];
  for (const [index, value] of records.entries()) {
    const item = row(value);
    const wait = row(item.WaitMetrics);
    const host = row(item.HostMetrics);
    const rate = ORDER[index];
    const integers = [item.Sequence, item.Round, item.TargetRps, item.DurationSeconds,
      item.HotSeatCount, item.HoldDwellMilliseconds, item.Iterations, item.HoldSuccess,
      item.ReleaseSuccess, item.ExpectedSeatConflicts, item.DroppedIterations,
      item.UnexpectedFailures, item.FinalHoldRows, item.DbDeadlocksDelta,
      wait.SampleCount, wait.MaxSampleGapMs, wait.HikariPendingPeak, wait.HikariActivePeak,
      wait.HikariMax, wait.HikariAcquireCount, wait.HikariTimeoutDelta,
      wait.DbRowLockCurrentWaitsPeak, wait.DbRowLockWaitsDelta,
      wait.DbRowLockTimeMsDelta, wait.DbDeadlocksDelta,
      host.SampleCount, host.HostFreeMemoryMinimumKb];
    const counts = item.DatabaseCounts;
    if (integers.some((number) => !natural(number)) ||
        !finite(item.HoldSuccessP95Ms) || !finite(item.SeatConflictP95Ms) ||
        !finite(wait.HikariAcquireWaitAverageMs) || !finite(host.HostCpuPeakPercent) ||
        Number(host.HostCpuPeakPercent) > 100 || !Array.isArray(counts) ||
        counts.length !== 5 || counts.some((number) => !natural(number))) {
      return noEvidence(`Stage ${index + 1} lacks a valid wait, host or final inventory measurement.`);
    }
    if (item.Sequence !== index + 1 || item.Round !== Math.floor(index / 2) + 1 ||
        item.TargetRps !== rate || item.DurationSeconds !== 10 || item.HotSeatCount !== 1 ||
        item.HoldDwellMilliseconds !== 500 || host.SampleCount !== wait.SampleCount) {
      return noEvidence(`Stage ${index + 1} does not match the fixed cross-order or sampling contract.`);
    }
    if (Number(item.Iterations) < rate * 10 * 0.95 || Number(item.Iterations) > rate * 10 * 1.02 ||
        Number(item.HoldSuccess) <= 0 || Number(item.ExpectedSeatConflicts) <= 0 ||
        Number(item.HoldSuccess) + Number(item.ExpectedSeatConflicts) !== item.Iterations ||
        item.HoldSuccess !== item.ReleaseSuccess || item.DroppedIterations !== 0 ||
        item.UnexpectedFailures !== 0 || item.FinalHoldRows !== 0 || item.DbDeadlocksDelta !== 0 ||
        wait.DbDeadlocksDelta !== 0 || wait.HikariTimeoutDelta !== 0 ||
        Number(wait.SampleCount) < 5 || Number(wait.MaxSampleGapMs) > 3000 ||
        Number(wait.HikariAcquireCount) <= 0 || Number(wait.HikariMax) <= 0 ||
        Number(wait.HikariActivePeak) >= Number(wait.HikariMax) ||
        Number(wait.HikariPendingPeak) > 0 ||
        Number(host.HostFreeMemoryMinimumKb) < 2097152 || counts.join(",") !== "20,0,0,20,0") {
      return stop(`Stage ${index + 1} failed completion, inventory, pool, sampling or memory gates.`);
    }
    observations.push({ sequence: index + 1, round: item.Round, ratePerSecond: rate,
      completed: item.Iterations, holdAndRelease: item.HoldSuccess,
      expectedSeatConflicts: item.ExpectedSeatConflicts,
      holdSuccessP95Ms: item.HoldSuccessP95Ms, seatConflictP95Ms: item.SeatConflictP95Ms,
      hikariPendingPeak: wait.HikariPendingPeak, globalDbRowLockWaits: wait.DbRowLockWaitsDelta,
      hostCpuPeakPercent: host.HostCpuPeakPercent,
      hostFreeMemoryMinimumKb: host.HostFreeMemoryMinimumKb });
  }

  const peakCpu = Math.max(...observations.map((item) => Number(item.hostCpuPeakPercent)));
  return { status: peakCpu >= 90 ? "RESOURCE_REVIEW_REQUIRED" as const : "REPEATED_OBSERVATION" as const,
    nextAction: peakCpu >= 90 ? "VERIFY_CPU_SOURCE_BEFORE_HIGHER_RATE" as const : "MANUAL_REVIEW_BEFORE_HIGHER_RATE" as const,
    batchId, controlledConditions: { totalSeats: 20, hotSeatCount: 1, hotRequestPercent: 70,
      holdDwellMilliseconds: 500, durationSecondsPerStage: 10, rateOrder: ORDER, repeatsPerRate: 3 },
    observations, note: "No higher-rate execution or stable-throughput claim is authorized by this report.",
    limitations: LIMITATIONS };
}

export function insufficientSmallSeatRepeat30(reason: string) {
  return noEvidence(reason);
}
