type Row = Record<string, unknown>;

const ORDER = [5, 10, 20, 20, 10, 5, 5, 10, 20];
const LIMITATIONS = [
  "Local virtual 20-seat fixture with one hot seat; not production throughput.",
  "DB row-lock wait is a global counter, not proof of a specific SQL wait.",
  "The 20-seat and 2,000-seat scenarios have different conditions and must not be compared as a before/after improvement."
];

export function insufficientSmallSeatHold(reason: string) {
  return { status: "INSUFFICIENT_EVIDENCE" as const, reasons: [reason], limitations: LIMITATIONS };
}

export function assessSmallSeatHold(batchId: string, value: unknown) {
  const manifest = row(value);
  const records = Array.isArray(manifest.Records) ? manifest.Records : [];
  if (manifest.SchemaVersion !== 2 || manifest.RunId !== batchId ||
      manifest.Scope !== "local virtual 20-seat fixture; not production performance" ||
      manifest.Complete !== true || manifest.Repeats !== 3 ||
      manifest.FixedHotSeatCount !== 1 || manifest.FixedHoldDwellMilliseconds !== 500 ||
      manifest.FixedHotRequestPercent !== 70 || records.length !== 9 ||
      typeof manifest.ComposeProject !== "string" ||
      !/^ticketon-controlled172(?:-[a-z0-9]{1,12})?$/.test(manifest.ComposeProject)) {
    return insufficientSmallSeatHold("The complete, fixed-condition nine-run manifest is missing or invalid.");
  }

  const observations = [];
  for (let index = 0; index < ORDER.length; index++) {
    const item = row(records[index]);
    const waits = row(item.WaitMetrics);
    const counts = item.DatabaseCounts;
    const required = [item.TargetRps, item.DurationSeconds, item.HotSeatCount,
      item.HoldDwellMilliseconds, item.Sequence, item.Round, item.Iterations,
      item.HoldSuccess, item.ReleaseSuccess, item.ExpectedSeatConflicts,
      item.DroppedIterations, item.UnexpectedFailures, item.HoldSuccessP95Ms,
      item.SeatConflictP95Ms, item.FinalHoldRows, item.DbDeadlocksDelta,
      waits.SampleCount, waits.MaxSampleGapMs, waits.HikariPendingPeak,
      waits.HikariActivePeak, waits.HikariMax, waits.HikariAcquireCount,
      waits.HikariAcquireWaitAverageMs, waits.HikariTimeoutDelta,
      waits.DbRowLockCurrentWaitsPeak, waits.DbRowLockWaitsDelta,
      waits.DbRowLockTimeMsDelta, waits.DbDeadlocksDelta];
    if (required.some((number) => typeof number !== "number" || !Number.isFinite(number) || number < 0) ||
        !Array.isArray(counts) || counts.length !== 5 ||
        counts.some((number) => typeof number !== "number" || !Number.isInteger(number) || number < 0)) {
      return insufficientSmallSeatHold(`Run ${index + 1} is missing a finite measurement or final inventory count.`);
    }
    const integerFields = [item.TargetRps, item.DurationSeconds, item.HotSeatCount,
      item.HoldDwellMilliseconds, item.Sequence, item.Round, item.Iterations,
      item.HoldSuccess, item.ReleaseSuccess, item.ExpectedSeatConflicts,
      item.DroppedIterations, item.UnexpectedFailures, item.FinalHoldRows,
      item.DbDeadlocksDelta, waits.SampleCount, waits.MaxSampleGapMs,
      waits.HikariPendingPeak, waits.HikariActivePeak, waits.HikariMax,
      waits.HikariAcquireCount, waits.HikariTimeoutDelta,
      waits.DbRowLockCurrentWaitsPeak, waits.DbRowLockWaitsDelta,
      waits.DbRowLockTimeMsDelta, waits.DbDeadlocksDelta];
    if (integerFields.some((number) => !Number.isInteger(number)) ||
        item.Sequence !== index + 1 || item.Round !== Math.floor(index / 3) + 1 ||
        item.TargetRps !== ORDER[index] || item.DurationSeconds !== 10 ||
        item.HotSeatCount !== 1 || item.HoldDwellMilliseconds !== 500) {
      return insufficientSmallSeatHold(`Run ${index + 1} does not match the fixed rate, order or measurement contract.`);
    }
    if (Number(item.Iterations) < Number(item.TargetRps) * 10 * 0.99 ||
        Number(item.Iterations) > Number(item.TargetRps) * 10 * 1.02 ||
        Number(item.HoldSuccess) <= 0 || Number(item.ExpectedSeatConflicts) <= 0 ||
        Number(item.HoldSuccess) + Number(item.ExpectedSeatConflicts) !== item.Iterations ||
        item.HoldSuccess !== item.ReleaseSuccess ||
        item.DroppedIterations !== 0 || item.UnexpectedFailures !== 0 ||
        item.FinalHoldRows !== 0 || item.DbDeadlocksDelta !== 0 ||
        waits.DbDeadlocksDelta !== 0 || waits.HikariTimeoutDelta !== 0 ||
        Number(waits.SampleCount) < 5 || Number(waits.MaxSampleGapMs) > 3000 ||
        Number(waits.HikariAcquireCount) <= 0 || Number(waits.HikariActivePeak) > Number(waits.HikariMax) ||
        counts.join(",") !== "20,0,0,20,0") {
      return {
        status: "NOT_COMPARABLE" as const,
        reasons: [`Run ${index + 1} failed the completion, Hold/Release, sampling or inventory contract.`],
        limitations: LIMITATIONS
      };
    }
    observations.push({
      sequence: item.Sequence, round: item.Round, ratePerSecond: item.TargetRps,
      iterations: item.Iterations, holdSuccess: item.HoldSuccess,
      releaseSuccess: item.ReleaseSuccess, expectedSeatConflicts: item.ExpectedSeatConflicts,
      holdSuccessP95Ms: item.HoldSuccessP95Ms, seatConflictP95Ms: item.SeatConflictP95Ms,
      hikariPendingPeak: waits.HikariPendingPeak,
      hikariAcquireWaitAverageMs: waits.HikariAcquireWaitAverageMs,
      globalDbRowLockWaits: waits.DbRowLockWaitsDelta
    });
  }

  return {
    status: "COMPARABLE" as const,
    batchId,
    controlledConditions: {
      totalSeats: 20, hotSeatCount: 1, hotRequestPercent: 70,
      holdDwellMilliseconds: 500, durationSeconds: 10,
      rateOrder: ORDER, repeatsPerRate: 3
    },
    observations,
    limitations: LIMITATIONS
  };
}

function row(value: unknown): Row {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Row : {};
}
