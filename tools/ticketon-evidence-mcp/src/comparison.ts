type JsonRecord = Record<string, unknown>;

const LIMITATIONS = [
  "가상 좌석 2,000개의 로컬 Mock PG 측정이며 실제 공연장·운영 처리량이 아닙니다.",
  "같은 배치라도 DB cache·호스트 부하가 동일하다는 증거는 없습니다.",
  "p95·SQL 실행시간 차이는 인과적 성능 개선이나 InnoDB row-lock 원인 귀속이 아닙니다."
];

interface RunEvidence {
  artifact: string;
  runId: string;
  repeat: number;
  scenario: string;
  rate: number;
  duration: number;
  sampleInterval: number;
  thresholdsEnforced: boolean;
  dockerStatsCollected: boolean;
  totalSeats: number;
  hotSeatCount: number;
  hotRequestPercent: number;
  seed: number;
  preAllocatedVus: number;
  maxVus: number;
  success: number;
  conflicts: number;
  successP95Ms: number;
  conflictP95Ms: number;
}

export function compareControlledHotspotEvidence(
  batchId: string,
  manifestValue: unknown,
  firstArtifact: string,
  firstSummary: unknown,
  secondArtifact: string,
  secondSummary: unknown
) {
  const insufficient: string[] = [];
  const incompatible: string[] = [];
  const manifest = record(manifestValue);
  if (manifest.SchemaVersion !== 1 || manifest.BatchId !== batchId ||
      manifest.ComposeProject !== "ticketon-controlled166" ||
      manifest.FixtureIsolatedBeforeEachRun !== true || manifest.Complete !== true ||
      manifest.SqlDigestTimingIsNotRowLockWaitAttribution !== true) {
    insufficient.push("완료된 전용 fixture 배치의 manifest 계약이 확인되지 않았습니다.");
  }
  const records = Array.isArray(manifest.Records) ? manifest.Records.map(record) : [];
  if (!Array.isArray(manifest.Records)) insufficient.push("배치 실행 목록이 없습니다.");
  if (firstArtifact === secondArtifact) incompatible.push("동일한 summary 파일은 두 run 비교가 아닙니다.");

  const first = parseRun(firstArtifact, firstSummary, records, insufficient, incompatible);
  const second = parseRun(secondArtifact, secondSummary, records, insufficient, incompatible);
  if (insufficient.length > 0 || !first || !second) {
    return verdict("INSUFFICIENT_EVIDENCE", insufficient.length ? insufficient : ["측정 요약이 불완전합니다."]);
  }

  for (const [name, left, right] of [
    ["시나리오", first.scenario, second.scenario],
    ["목표 RPS", first.rate, second.rate],
    ["지속 시간", first.duration, second.duration],
    ["관측 표본 간격", first.sampleInterval, second.sampleInterval],
    ["k6 threshold 적용", first.thresholdsEnforced, second.thresholdsEnforced],
    ["Docker stats 수집", first.dockerStatsCollected, second.dockerStatsCollected],
    ["fixture 좌석 수", first.totalSeats, second.totalSeats],
    ["인기 좌석 요청 비율", first.hotRequestPercent, second.hotRequestPercent],
    ["좌석 선택 시드", first.seed, second.seed],
    ["사전 VU", first.preAllocatedVus, second.preAllocatedVus],
    ["최대 VU", first.maxVus, second.maxVus]
  ] as const) {
    if (left !== right) incompatible.push(`${name}이(가) 일치하지 않습니다.`);
  }
  if (first.hotSeatCount === second.hotSeatCount && first.repeat === second.repeat) {
    incompatible.push("같은 인기 좌석 조건은 서로 다른 반복 회차에서 비교해야 합니다.");
  }
  if (incompatible.length > 0) return verdict("NOT_COMPARABLE", incompatible);

  const comparisonType = first.hotSeatCount === second.hotSeatCount
    ? "SAME_CONDITION_REPEAT" : "HOT_SEAT_COUNT_ONLY";
  return {
    status: "COMPARABLE" as const,
    comparisonType,
    controlledConditions: {
      scenario: first.scenario,
      ratePerSecond: first.rate,
      durationSeconds: first.duration,
      sampleIntervalMilliseconds: first.sampleInterval,
      thresholdsEnforced: first.thresholdsEnforced,
      dockerStatsCollected: first.dockerStatsCollected,
      totalSeats: first.totalSeats,
      hotRequestPercent: first.hotRequestPercent,
      seed: first.seed,
      preAllocatedVus: first.preAllocatedVus,
      maxVus: first.maxVus
    },
    observations: [observation(first), observation(second)],
    observedDifferenceSecondMinusFirst: {
      reservationSuccess: second.success - first.success,
      expectedSeatConflicts: second.conflicts - first.conflicts,
      successP95Ms: round(second.successP95Ms - first.successP95Ms),
      seatConflictP95Ms: round(second.conflictP95Ms - first.conflictP95Ms)
    },
    limitations: LIMITATIONS
  };
}

function parseRun(
  artifact: string,
  value: unknown,
  manifestRecords: JsonRecord[],
  insufficient: string[],
  incompatible: string[]
): RunEvidence | undefined {
  const matches = manifestRecords.filter((entry) => entry.SummaryFile === artifact);
  if (matches.length !== 1) {
    insufficient.push(`${artifact}: manifest에 정확히 한 번 등록된 summary가 아닙니다.`);
    return undefined;
  }
  const entry = matches[0];
  const summary = record(value);
  const run = record(summary.Run);
  const fixture = record(summary.FixturePreparation);
  const fresh = record(fixture.FreshFixture);
  const k6 = record(summary.K6);
  const result = record(k6.Result);
  const weighted = record(result.WeightedHotspot);
  const final = record(k6.FinalSnapshot);
  const metrics = record(summary.Metrics);
  const deltas = record(metrics.Deltas);
  const observer = record(metrics.ObserverEffects);
  const digest = record(summary.DatabaseStatementDigests);
  const coverage = record(digest.Coverage);
  const health = record(digest.InstrumentationHealth);
  const seatLock = record(digest.SeatLockSelect);
  const decrement = record(digest.ConcertTimeDecrement);
  const successDuration = record(result.ReservationSuccessDurationMs);
  const conflictDuration = record(result.ReservationSeatContentionDurationMs);

  const values = {
    rate: number(run.RatePerSecond),
    duration: number(run.DurationSeconds),
    sampleInterval: number(run.SampleIntervalMilliseconds),
    repeat: number(entry.Repeat),
    totalSeats: number(fixture.TotalSeats),
    hotSeatCount: number(weighted.HotSeatCount),
    hotRequestPercent: number(weighted.HotRequestPercent),
    seed: number(weighted.Seed),
    preAllocatedVus: number(result.PreAllocatedVus),
    maxVus: number(result.ConfiguredMaxVus),
    iterations: number(result.Iterations),
    scheduledAttainment: number(result.ScheduledIterationAttainmentRate),
    completedPerSecond: number(result.CompletedIterationsPerScheduledSecond),
    dropped: number(result.DroppedIterations),
    unexpected: number(result.UnexpectedNonSuccessful),
    success: number(result.ReservationSuccess),
    conflicts: number(result.ExpectedContention),
    successP95Ms: number(successDuration.P95),
    conflictP95Ms: number(conflictDuration.P95),
    deadlocks: number(deltas.DbDeadlocks),
    physicalSeats: number(fresh.PhysicalSeatRows),
    remaining: number(fresh.RemainingSeats),
    reserved: number(fresh.ReservedSeats),
    reservations: number(fresh.Reservations),
    bookings: number(fresh.Bookings),
    payments: number(fresh.Payments),
    finalSeats: number(final.actualSeatCount),
    finalExpected: number(final.expectedTotalSeats),
    finalRemaining: number(final.remainingSeats),
    finalReserved: number(final.reservedSeats),
    finalReservations: number(final.reservations),
    finalBookings: number(final.bookings),
    finalPayments: number(final.payments),
    seatCoverage: number(coverage.SeatLockSelectRate),
    updateCoverage: number(coverage.ConcertTimeDecrementRate),
    expectedSeatSelects: number(coverage.ExpectedSeatLockSelects),
    observedSeatSelects: number(coverage.SeatLockSelectCount),
    seatSelectCount: number(seatLock.Count),
    expectedSuccess: number(coverage.ExpectedSuccessfulReservations),
    observedDecrements: number(coverage.ConcertTimeDecrementCount),
    decrementCount: number(decrement.Count),
    decrementRowsAffected: number(decrement.RowsAffected),
    lostDigests: number(health.PerformanceSchemaDigestLost),
    nullDigests: number(health.NullDigestEvents)
  };
  if (Object.values(values).some((item) => item === undefined)) {
    insufficient.push(`${artifact}: 필수 측정값이 누락되거나 유효하지 않습니다.`);
    return undefined;
  }
  if (typeof run.Id !== "string" || run.Id !== entry.RunId ||
      summary.SchemaVersion !== 1 ||
      run.Scenario !== "weighted-hotspot" || result.Scenario !== "weighted-hotspot" ||
      entry.HotSeatCount !== values.hotSeatCount ||
      entry.Success !== values.success || entry.SeatConflicts !== values.conflicts ||
      entry.SuccessP95Ms !== values.successP95Ms || entry.ConflictP95Ms !== values.conflictP95Ms ||
      result.TargetRatePerSecond !== values.rate || result.DurationSeconds !== values.duration) {
    insufficient.push(`${artifact}: manifest·run·k6 조건 또는 결과가 일치하지 않습니다.`);
    return undefined;
  }
  if (summary.ValidMeasurement !== true || k6.ExitCode !== 0 ||
      typeof result.ThresholdsEnforced !== "boolean" ||
      typeof observer.DockerStatsCollected !== "boolean" ||
      fixture.ExcludedFromMetricSamples !== true || fresh.ExcludedFromMetricSamples !== true ||
      digest.Enabled !== true ||
      k6.InventoryInvariantSatisfied !== true || fresh.InvariantSatisfied !== true ||
      final.invariantSatisfied !== true ||
      values.totalSeats !== 2000 || values.physicalSeats !== 2000 || values.remaining !== 2000 ||
      values.reserved !== 0 || values.reservations !== 0 || values.bookings !== 0 || values.payments !== 0 ||
      values.finalExpected !== 2000 || values.finalSeats !== 2000 ||
      values.finalRemaining! + values.finalReserved! !== 2000 ||
      values.finalReserved !== values.success ||
      values.finalReservations !== values.success || values.finalBookings !== values.success ||
      values.finalPayments !== values.success ||
      values.rate! <= 0 || values.duration! <= 0 || values.iterations! <= 0 ||
      values.success! <= 0 || values.conflicts! <= 0 ||
      values.iterations !== values.success! + values.conflicts! || values.dropped !== 0 ||
      values.iterations! < values.rate! * values.duration! * 0.99 ||
      values.iterations! > values.rate! * values.duration! * 1.01 ||
      values.scheduledAttainment! < 0.99 || values.scheduledAttainment! > 1 ||
      Math.abs(values.completedPerSecond! - values.iterations! / values.duration!) > 0.001 ||
      values.unexpected !== 0 || values.deadlocks !== 0 ||
      values.seatCoverage! < 0.95 || values.seatCoverage! > 1 || values.updateCoverage !== 1 ||
      values.expectedSeatSelects !== values.iterations ||
      values.observedSeatSelects !== values.seatSelectCount ||
      Math.abs(values.seatCoverage! - values.seatSelectCount! / values.iterations!) > 0.000001 ||
      values.expectedSuccess !== values.success ||
      values.observedDecrements !== values.decrementCount ||
      values.decrementCount !== values.success || values.decrementRowsAffected !== values.success ||
      values.lostDigests !== 0 || values.nullDigests !== 0) {
    incompatible.push(`${artifact}: 완료율·초기/최종 재고·오류 또는 SQL 관측 품질 조건을 통과하지 못했습니다.`);
  }
  return {
    artifact,
    runId: run.Id,
    repeat: values.repeat!,
    scenario: "weighted-hotspot",
    rate: values.rate!,
    duration: values.duration!,
    sampleInterval: values.sampleInterval!,
    thresholdsEnforced: result.ThresholdsEnforced as boolean,
    dockerStatsCollected: observer.DockerStatsCollected as boolean,
    totalSeats: values.totalSeats!,
    hotSeatCount: values.hotSeatCount!,
    hotRequestPercent: values.hotRequestPercent!,
    seed: values.seed!,
    preAllocatedVus: values.preAllocatedVus!,
    maxVus: values.maxVus!,
    success: values.success!,
    conflicts: values.conflicts!,
    successP95Ms: values.successP95Ms!,
    conflictP95Ms: values.conflictP95Ms!
  };
}

function observation(run: RunEvidence) {
  return {
    artifact: run.artifact,
    runId: run.runId,
    hotSeatCount: run.hotSeatCount,
    reservationSuccess: run.success,
    expectedSeatConflicts: run.conflicts,
    successP95Ms: round(run.successP95Ms),
    seatConflictP95Ms: round(run.conflictP95Ms)
  };
}

function verdict(status: "NOT_COMPARABLE" | "INSUFFICIENT_EVIDENCE", reasons: string[]) {
  return { status, reasons: [...new Set(reasons)], limitations: LIMITATIONS };
}

function record(value: unknown): JsonRecord {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as JsonRecord : {};
}

function number(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : undefined;
}

function round(value: number) {
  return Math.round(value * 1000) / 1000;
}
