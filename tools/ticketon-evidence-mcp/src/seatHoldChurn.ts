type JsonRecord = Record<string, unknown>;

const HOT_SEAT_ORDER = [20, 40, 200, 200, 40, 20];
export const SEAT_HOLD_CHURN_LIMITATIONS = [
  "로컬 가상 좌석 2,000개와 100ms Hold→Release 측정이며 실제 공연장·운영 처리량이 아닙니다.",
  "DB row-lock wait는 전역 counter로, 특정 SQL 또는 잠금 원인에 귀속할 수 없습니다.",
  "각 조건은 2회이고 별도 배치의 cache·호스트 부하와 warm-up이 통제되지 않아 p95 차이를 개선 효과로 판단하지 않습니다."
];

export function assessSeatHoldChurnBatch(batchId: string, manifestValue: unknown, summaryValues: unknown[]) {
  const missing: string[] = [];
  const invalid: string[] = [];
  const manifest = record(manifestValue);
  const entries = Array.isArray(manifest.Records) ? manifest.Records.map(record) : [];
  if (manifest.SchemaVersion !== 1 || manifest.BatchId !== batchId ||
      manifest.Scenario !== "weighted-hotspot-churn" || manifest.Complete !== true ||
      manifest.FixtureResetBeforeEachRun !== true || manifest.HoldDwellMilliseconds !== 100 ||
      !/^ticketon-controlled172(?:-[a-z0-9]{1,12})?$/.test(String(manifest.ComposeProject ?? "")) ||
      manifest.FixtureRunId !== `${batchId}-fixture` || entries.length !== 6 || summaryValues.length !== 6) {
    missing.push("완료된 전용 Hold→Release 6-run manifest와 summary 계약이 확인되지 않았습니다.");
  }

  const observations: NonNullable<ReturnType<typeof parseRun>>[] = [];
  for (let index = 0; index < 6; index++) {
    const repeat = index < 3 ? 1 : 2;
    const hotSeatCount = HOT_SEAT_ORDER[index];
    const expectedRunId = `${batchId}-r${repeat}-h${hotSeatCount}`;
    const expectedFile = `${expectedRunId}-summary.json`;
    const entry = entries[index] ?? {};
    if (entry.Sequence !== index + 1 || entry.Repeat !== repeat ||
        entry.HotSeatCount !== hotSeatCount || entry.RunId !== expectedRunId ||
        entry.SummaryFile !== expectedFile ||
        entries.filter((candidate) => candidate.SummaryFile === expectedFile).length !== 1) {
      missing.push(`${expectedFile}: manifest 순서·멤버십이 일치하지 않습니다.`);
    }
    const observation = parseRun(expectedFile, summaryValues[index], entry, missing, invalid);
    if (observation) observations.push(observation);
  }

  if (observations.length !== 6) missing.push("6개 run의 필수 값이 모두 확인되지 않았습니다.");
  if (missing.length > 0) return verdict("INSUFFICIENT_EVIDENCE", missing);
  const rates = new Set(observations.map((item) => item.rate));
  const durations = new Set(observations.map((item) => item.duration));
  const intervals = new Set(observations.map((item) => item.sampleInterval));
  const seeds = new Set(observations.map((item) => item.seed));
  const vus = new Set(observations.map((item) => item.preAllocatedVus));
  if (rates.size !== 1 || durations.size !== 1 || intervals.size !== 1 || seeds.size !== 1 || vus.size !== 1) {
    invalid.push("RPS·지속 시간·관측 간격·선택 seed·VU 설정이 한 배치에서 일치하지 않습니다.");
  }
  if (invalid.length > 0) return verdict("NOT_COMPARABLE", invalid);

  return {
    status: "COMPARABLE" as const,
    batchId,
    controlledConditions: {
      ratePerSecond: observations[0].rate,
      durationSeconds: observations[0].duration,
      totalSeats: 2000,
      hotRequestPercent: 70,
      holdDwellMilliseconds: 100,
      repeatsPerCondition: 2,
      warmup: false
    },
    observations: observations.map(({ file, repeat, hotSeatCount, iterations, holdSuccess, conflicts,
      successP95, conflictP95, pending, rowLockWaits }) => ({
      artifact: file, repeat, hotSeatCount, iterations, holdSuccess,
      releaseSuccess: holdSuccess, expectedSeatConflicts: conflicts,
      holdSuccessP95Ms: successP95, seatConflictP95Ms: conflictP95,
      hikariPendingPeak: pending, globalDbRowLockWaits: rowLockWaits
    })),
    limitations: SEAT_HOLD_CHURN_LIMITATIONS
  };
}

function parseRun(file: string, value: unknown, entry: JsonRecord, missing: string[], invalid: string[]) {
  const summary = record(value);
  const run = record(summary.Run);
  const fixture = record(summary.FixturePreparation);
  const k6 = record(summary.K6);
  const result = record(k6.Result);
  const weighted = record(result.WeightedHotspot);
  const final = record(k6.FinalSnapshot);
  const metrics = record(summary.Metrics);
  const peaks = record(metrics.Peaks);
  const deltas = record(metrics.Deltas);
  const domain = record(summary.DomainMetrics);
  const successDuration = record(result.HoldSuccessDurationMs);
  const conflictDuration = record(result.HoldSeatConflictDurationMs);
  const fields = {
    rate: num(run.RatePerSecond), duration: num(run.DurationSeconds),
    sampleInterval: num(run.SampleIntervalMilliseconds),
    seed: num(weighted.Seed), preAllocatedVus: num(result.PreAllocatedVus),
    iterations: num(result.Iterations), holdSuccess: num(result.HoldSuccess),
    releaseSuccess: num(result.ReleaseSuccess), conflicts: num(result.ExpectedContention),
    successP95: num(successDuration.P95), conflictP95: num(conflictDuration.P95),
    rowLockWaits: num(deltas.DbRowLockWaits), deadlocks: num(deltas.DbDeadlocks),
    dropped: num(result.DroppedIterations), unexpected: num(result.UnexpectedNonSuccessful),
    unexpectedRelease: num(result.UnexpectedRelease),
    attainment: num(result.ScheduledIterationAttainmentRate),
    pending: num(peaks.HikariPending),
    hotSelections: num(weighted.HotSelections), coldSelections: num(weighted.ColdSelections),
    actualSeats: num(final.actualSeatCount), remaining: num(final.remainingSeats),
    held: num(final.activeHeldSeats), holdRows: num(final.holdRows),
    reserved: num(final.reservedSeats), reservations: num(final.reservations),
    bookings: num(final.bookings), payments: num(final.payments),
    domainSuccess: num(domain.HoldSuccess), domainConflict: num(domain.HoldConflict),
    acquired: num(domain.HoldAcquired), domainRelease: num(domain.ReleaseSuccess),
    released: num(domain.Released)
  };
  if (Object.values(fields).some((field) => field === undefined)) {
    missing.push(`${file}: 필수 측정값이 누락되거나 숫자가 아닙니다.`);
    return undefined;
  }
  const integerFields = [fields.rate, fields.duration, fields.sampleInterval, fields.seed,
    fields.preAllocatedVus, fields.iterations, fields.holdSuccess, fields.releaseSuccess,
    fields.conflicts, fields.rowLockWaits, fields.deadlocks, fields.dropped,
    fields.unexpected, fields.unexpectedRelease, fields.pending, fields.hotSelections,
    fields.coldSelections, fields.actualSeats, fields.remaining, fields.held,
    fields.holdRows, fields.reserved, fields.reservations, fields.bookings, fields.payments,
    fields.domainSuccess, fields.domainConflict, fields.acquired, fields.domainRelease, fields.released];
  if (integerFields.some((field) => !Number.isInteger(field))) {
    invalid.push(`${file}: 건수·재고·설정값은 정수여야 합니다.`);
  }
  if (summary.SchemaVersion !== 1 || run.Id !== entry.RunId ||
      run.FixtureRunId !== `${String(entry.RunId).replace(/-r[12]-h(?:20|40|200)$/, "")}-fixture` ||
      run.Scenario !== "weighted-hotspot-churn" || result.Scenario !== "weighted-hotspot-churn" ||
      weighted.HotSeatCount !== entry.HotSeatCount || weighted.HotRequestPercent !== 70 ||
      run.HoldDwellMilliseconds !== 100 || result.HoldDwellMilliseconds !== 100 ||
      fixture.TotalSeats !== 2000 || fixture.ReusedAndReset !== true ||
      fixture.ExcludedFromMetricSamples !== true ||
      result.TargetRatePerSecond !== fields.rate || result.DurationSeconds !== fields.duration ||
      entry.Iterations !== fields.iterations || entry.HoldSuccess !== fields.holdSuccess ||
      entry.ReleaseSuccess !== fields.releaseSuccess || entry.ExpectedContention !== fields.conflicts ||
      entry.SuccessP95Ms !== fields.successP95 || entry.SeatConflictP95Ms !== fields.conflictP95 ||
      entry.HikariPendingPeak !== fields.pending || entry.DbRowLockWaits !== fields.rowLockWaits) {
    missing.push(`${file}: manifest·run·k6의 조건이나 결과가 일치하지 않습니다.`);
  }
  if (summary.ValidMeasurement !== true || k6.ExitCode !== 0 ||
      k6.StateInvariantSatisfied !== true || final.invariantSatisfied !== true ||
      result.ThresholdsEnforced !== false || fields.rate !== 50 && fields.rate !== 100 ||
      fields.duration !== 10 || fields.sampleInterval !== 1000 ||
      fields.seed !== 17 || fields.preAllocatedVus !== 200 || result.ConfiguredMaxVus !== 200 ||
      final.expectedTotalSeats !== 2000 || fields.actualSeats !== 2000 || fields.remaining !== 2000 ||
      fields.held !== 0 || fields.holdRows !== 0 || fields.reserved !== 0 ||
      fields.reservations !== 0 || fields.bookings !== 0 || fields.payments !== 0 ||
      fields.iterations! < fields.rate! * fields.duration! * 0.99 || fields.dropped !== 0 ||
      fields.iterations! > fields.rate! * fields.duration! * 1.01 ||
      fields.attainment! < 0.99 || fields.attainment! > 1 ||
      fields.holdSuccess! <= 0 || fields.conflicts! <= 0 ||
      fields.holdSuccess! + fields.conflicts! !== fields.iterations ||
      fields.releaseSuccess !== fields.holdSuccess ||
      fields.hotSelections! + fields.coldSelections! !== fields.iterations ||
      fields.unexpected !== 0 || fields.unexpectedRelease !== 0 ||
      fields.deadlocks !== 0 ||
      fields.domainSuccess !== fields.holdSuccess || fields.domainConflict !== fields.conflicts ||
      fields.acquired !== fields.holdSuccess || fields.domainRelease !== fields.releaseSuccess ||
      fields.released !== fields.releaseSuccess ||
      ["HoldInvalid", "HoldError", "HoldReused", "HoldReclaimed", "ReleaseConflict",
        "ReleaseInvalid", "ReleaseError", "ExpiredCleared"].some((key) => domain[key] !== 0)) {
    invalid.push(`${file}: 완료율·Hold/Release 전이·최종 점유·오류 게이트를 통과하지 못했습니다.`);
  }
  return {
    file, repeat: Number(entry.Repeat), hotSeatCount: Number(entry.HotSeatCount),
    rate: fields.rate!, duration: fields.duration!, sampleInterval: fields.sampleInterval!,
    seed: fields.seed!, preAllocatedVus: fields.preAllocatedVus!,
    iterations: fields.iterations!, holdSuccess: fields.holdSuccess!, conflicts: fields.conflicts!,
    successP95: fields.successP95!, conflictP95: fields.conflictP95!,
    pending: fields.pending!, rowLockWaits: fields.rowLockWaits!
  };
}

function record(value: unknown): JsonRecord {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as JsonRecord : {};
}

function num(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : undefined;
}

function verdict(status: "INSUFFICIENT_EVIDENCE" | "NOT_COMPARABLE", reasons: string[]) {
  return { status, reasons: [...new Set(reasons)], limitations: SEAT_HOLD_CHURN_LIMITATIONS };
}
