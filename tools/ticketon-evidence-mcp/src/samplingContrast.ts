type Row = Record<string, unknown>;
const ORDER = [1000, 2000, 1000, 2000, 2000, 1000, 1000, 2000];
const LIMITATIONS = [
  "Collection duration is collector wall-clock including I/O, not CPU time or request-path delay.",
  "Peaks from different sampling intervals cannot be compared directly; zero sampled pending does not exclude transient waits.",
  "Global DB row-lock counters do not identify a SQL cause. Host CPU does not attribute load to the Backend or DB.",
  "This is a local virtual fixture observation, not a performance improvement, capacity or production guarantee.",
  "No new load, fixture, external API or AI model execution is authorized by this assessment."
];
const row = (value: unknown): Row => value !== null && typeof value === "object" && !Array.isArray(value) ? value as Row : {};
const natural = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
const finite = (value: unknown): value is number => typeof value === "number" && Number.isFinite(value) && value >= 0;

export function insufficientSamplingContrast(reason: string) {
  return { status: "INSUFFICIENT_EVIDENCE" as const, nextAction: "DO_NOT_ESCALATE" as const,
    reasons: [reason], limitations: LIMITATIONS };
}
function stop(reason: string) {
  return { status: "STOP_ESCALATION" as const, nextAction: "INVESTIGATE_EVIDENCE_OR_RESOURCE_GATE" as const,
    reasons: [reason], limitations: LIMITATIONS };
}

export function assessSamplingContrast(batchId: string, value: unknown) {
  const manifest = row(value);
  const env = row(manifest.Environment);
  if (manifest.SchemaVersion !== 6 || manifest.Scope !== "local virtual 20-seat fixture; not production performance" ||
      manifest.RunId !== batchId || manifest.Complete !== true || manifest.Failure !== null ||
      manifest.CompareSampling !== true || manifest.ContrastHotspots !== false ||
      manifest.Probe30 !== false || manifest.Repeat30 !== false || manifest.Repeats !== 3 ||
      manifest.FixedHotSeatCount !== 1 || manifest.FixedTargetIterationsPerSecond !== 20 ||
      manifest.FixedHoldDwellMilliseconds !== 500 || manifest.FixedHotRequestPercent !== 70 ||
      manifest.FixedSelectionSeed !== 17 || manifest.FixedPreAllocatedVus !== 20 || manifest.FixedMaxVus !== 20 ||
      typeof manifest.ComposeProject !== "string" || !/^ticketon-controlled172(?:-[a-z0-9]{1,12})?$/.test(manifest.ComposeProject) ||
      !natural(env.LogicalProcessors) || env.LogicalProcessors === 0 ||
      !natural(env.HostTotalPhysicalMemoryBytes) || env.HostTotalPhysicalMemoryBytes === 0 ||
      typeof manifest.ResourceReviewRequired !== "boolean" ||
      !Array.isArray(manifest.WarmupRecords) || manifest.WarmupRecords.length !== 2 ||
      !Array.isArray(manifest.Records) || manifest.Records.length !== 6) {
    return insufficientSamplingContrast("A complete schema 6 fixed-workload sampling crossover with environment and warm-ups is required.");
  }
  const all = [...manifest.WarmupRecords, ...manifest.Records];
  const observations = [];
  let resourceReview = false;
  for (const [index, value] of all.entries()) {
    const item = row(value), wait = row(item.WaitMetrics), host = row(item.HostMetrics), cost = row(item.CollectionMetrics);
    const integers = [item.Sequence, item.Round, item.TargetRps, item.TargetIterationsPerSecond,
      item.DurationSeconds, item.HotSeatCount, item.HoldDwellMilliseconds, item.SamplingIntervalMilliseconds,
      item.Iterations, item.HoldSuccess, item.ReleaseSuccess, item.ExpectedSeatConflicts,
      item.HotSelections, item.ColdSelections, item.DroppedIterations, item.UnexpectedFailures,
      item.FinalHoldRows, item.DbDeadlocksDelta, wait.SampleCount, wait.MaxSampleGapMs,
      wait.HikariPendingPeak, wait.HikariActivePeak, wait.HikariMax, wait.HikariAcquireCount,
      wait.HikariTimeoutDelta, wait.DbDeadlocksDelta, wait.DbRowLockCurrentWaitsPeak,
      wait.DbRowLockWaitsDelta, wait.DbRowLockTimeMsDelta, host.SampleCount,
      host.HostFreeMemoryMinimumKb, cost.SampleCount];
    const counts = item.DatabaseCounts;
    if (integers.some(v => !natural(v)) ||
        [item.HoldSuccessP95Ms, item.SeatConflictP95Ms, item.HoldSuccessPerScheduledSecond,
          wait.HikariAcquireWaitAverageMs, host.HostCpuPeakPercent,
          cost.CollectionTotalMs, cost.CollectionMaxMs, cost.CollectionAverageMs].some(v => !finite(v)) ||
        Number(host.HostCpuPeakPercent) > 100 || typeof item.ResourceReviewRequired !== "boolean" ||
        !Array.isArray(counts) || counts.length !== 5 || counts.some(v => !natural(v))) {
      return insufficientSamplingContrast(`Stage ${index + 1} has missing or invalid numeric evidence.`);
    }
    if (item.Sequence !== index + 1 || item.Round !== (index < 2 ? 0 : Math.floor(index / 2)) ||
        item.Measured !== (index >= 2) || item.TargetRps !== 20 || item.TargetIterationsPerSecond !== 20 ||
        item.DurationSeconds !== 15 || item.HotSeatCount !== 1 || item.HoldDwellMilliseconds !== 500 ||
        item.SamplingIntervalMilliseconds !== ORDER[index] || host.SampleCount !== wait.SampleCount ||
        cost.SampleCount !== wait.SampleCount ||
        item.HoldSuccessPerScheduledSecond !== Number(item.HoldSuccess) / 15 ||
        Math.abs(Number(cost.CollectionTotalMs) / Number(cost.SampleCount) - Number(cost.CollectionAverageMs)) > 1e-6 ||
        Number(cost.CollectionMaxMs) < Number(cost.CollectionAverageMs) ||
        Number(cost.CollectionTotalMs) < Number(cost.CollectionMaxMs) ||
        Number(cost.CollectionTotalMs) > Number(cost.CollectionMaxMs) * Number(cost.SampleCount) + 1e-6 ||
        item.ResourceReviewRequired !== (Number(host.HostCpuPeakPercent) >= 90)) {
      return insufficientSamplingContrast(`Stage ${index + 1} contradicts the crossover or collection-cost contract.`);
    }
    if (Number(item.Iterations) < 285 || Number(item.Iterations) > 306 || Number(item.HoldSuccess) <= 0 ||
        Number(item.HoldSuccess) + Number(item.ExpectedSeatConflicts) !== item.Iterations ||
        Number(item.HotSelections) + Number(item.ColdSelections) !== item.Iterations ||
        item.HoldSuccess !== item.ReleaseSuccess || item.DroppedIterations !== 0 || item.UnexpectedFailures !== 0 ||
        item.FinalHoldRows !== 0 || item.DbDeadlocksDelta !== 0 || counts.join(",") !== "20,0,0,20,0" ||
        wait.DbDeadlocksDelta !== 0 || wait.HikariTimeoutDelta !== 0 || Number(wait.SampleCount) < 5 ||
        Number(wait.MaxSampleGapMs) <= 0 || Number(wait.MaxSampleGapMs) > 3000 ||
        Number(wait.HikariAcquireCount) <= 0 || Number(wait.HikariMax) <= 0 ||
        Number(wait.HikariActivePeak) >= Number(wait.HikariMax) || Number(wait.HikariPendingPeak) > 0 ||
        Number(host.HostFreeMemoryMinimumKb) < 2097152) {
      return stop(`Stage ${index + 1} failed completion, inventory, pool, sampling or memory gates.`);
    }
    resourceReview ||= item.ResourceReviewRequired;
    if (index >= 2) observations.push({ sequence: item.Sequence, round: item.Round,
      samplingIntervalMilliseconds: item.SamplingIntervalMilliseconds,
      completed: item.Iterations, holdAndRelease: item.HoldSuccess, expectedSeatConflicts: item.ExpectedSeatConflicts,
      holdSuccessP95Ms: item.HoldSuccessP95Ms, seatConflictP95Ms: item.SeatConflictP95Ms,
      globalDbRowLockWaits: wait.DbRowLockWaitsDelta, globalDbRowLockTimeMs: wait.DbRowLockTimeMsDelta,
      collectionSamples: cost.SampleCount, collectionTotalWallClockMs: cost.CollectionTotalMs,
      collectionMaxWallClockMs: cost.CollectionMaxMs, resourceReviewRequired: item.ResourceReviewRequired });
  }
  if (manifest.ResourceReviewRequired !== resourceReview) {
    return insufficientSamplingContrast("The aggregate resource-review flag contradicts its warm-up or measured stages.");
  }
  return { status: resourceReview ? "RESOURCE_REVIEW_REQUIRED" as const : "SAMPLING_OBSERVATION" as const,
    nextAction: "MANUAL_REVIEW_ONLY" as const, batchId,
    controlledConditions: { totalSeats: 20, hotSeatCount: 1, iterationsPerSecond: 20, hotRequestPercent: 70,
      holdDwellMilliseconds: 500, durationSecondsPerStage: 15, warmupExcluded: 2,
      samplingIntervalOrderMs: ORDER.slice(2), repeatsPerInterval: 3 },
    observations, limitations: LIMITATIONS };
}
