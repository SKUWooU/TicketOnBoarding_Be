import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { createMixedSeatPlan } from './mixed-seat-plan.mjs';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const RUN = __ENV.FIXTURE_RUN_ID;
const MODE = __ENV.MIXED_MODE || 'small-only';
const SIZE = Number(__ENV.LARGE_SEATS || 20);
const SMALL_RATE = Number(__ENV.SMALL_RATE || 10);
const LARGE_RATE = Number(__ENV.LARGE_RATE || 2);
const DURATION = __ENV.DURATION || '15s';
if (BASE !== 'http://127.0.0.1:18080' || !/^[A-Za-z0-9-]{1,32}$/.test(RUN || '') ||
    !['small-only', 'separate', 'overlap'].includes(MODE) ||
    !Number.isSafeInteger(SMALL_RATE) || SMALL_RATE < 1 || SMALL_RATE > 20 ||
    !Number.isSafeInteger(LARGE_RATE) || LARGE_RATE < 1 || LARGE_RATE > 5) {
  throw new Error('Use bounded rates, an explicit mode, fixture ID and loopback Backend.');
}
createMixedSeatPlan(2000, 40, SIZE, MODE === 'overlap');
const attempts = new Counter('mixed_attempts');
const success = new Counter('mixed_success');
const conflicts = new Counter('mixed_conflicts');
const failures = new Counter('mixed_failures');
const released = new Counter('mixed_released');
const latency = new Trend('mixed_hold_ms', true);
const successLatency = new Trend('mixed_success_ms', true);
const conflictLatency = new Trend('mixed_conflict_ms', true);
const thresholds = { mixed_failures: ['count==0'], dropped_iterations: ['count==0'] };
for (const kind of ['small', 'large']) {
  for (const metric of ['mixed_attempts', 'mixed_success', 'mixed_conflicts', 'mixed_failures', 'mixed_released', 'mixed_hold_ms', 'mixed_success_ms', 'mixed_conflict_ms']) {
    thresholds[`${metric}{kind:${kind}}`] = []; // materialize class-specific summary, not an SLO
  }
}
export const options = {
  scenarios: {
    small: { executor: 'constant-arrival-rate', exec: 'small', rate: SMALL_RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 20, maxVUs: 20 },
    ...(MODE === 'small-only' ? {} : { large: { executor: 'constant-arrival-rate', exec: 'large', rate: LARGE_RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 10, maxVUs: 10 } }),
  }, thresholds, summaryTrendStats: ['avg', 'med', 'p(95)', 'max'],
};
http.setResponseCallback(http.expectedStatuses(200, 204, 409));
export function setup() {
  const fixture = http.get(`${BASE}/loadtest/fixture?runId=${RUN}`);
  const tokens = http.get(`${BASE}/loadtest/tokens?runId=${RUN}&count=40`);
  if (fixture.status !== 200 || tokens.status !== 200) throw new Error('Fixture/tokens unavailable.');
  const f = fixture.json(); const t = tokens.json();
  if (f.totalSeats !== 2000 || t.length !== 40) throw new Error('Expected 2000 seats and 40 users.');
  return { fixture: f, tokens: t, plan: createMixedSeatPlan(f.totalSeats, f.seatsPerRow, SIZE, MODE === 'overlap') };
}
export function small(data) { run(data, 'small'); }
export function large(data) { run(data, 'large'); }
function run(data, kind) {
  const tags = { kind };
  const seats = kind === 'small' ? [data.plan.small[exec.scenario.iterationInTest % 40]] : data.plan.large;
  // VU identities are unique across both scenarios; no concurrently shared user credentials.
  const token = data.tokens[exec.vu.idInTest - 1];
  const body = JSON.stringify({ concertTimeId: data.fixture.concertTimeId, seatNumberList: seats });
  const params = { headers: { 'Content-Type': 'application/json', Cookie: `accessToken=${token.accessToken}` }, tags, timeout: '10s' };
  attempts.add(1, tags);
  const response = http.post(`${BASE}/main/detail/${data.fixture.concertId}/seat-holds`, body, params);
  latency.add(response.timings.duration, tags);
  if (response.status === 409) { conflicts.add(1, tags); conflictLatency.add(response.timings.duration, tags); return; }
  if (response.status !== 200) { failures.add(1, tags); return; }
  success.add(1, tags); successLatency.add(response.timings.duration, tags);
  sleep(0.05); // identical 50ms dwell for both classes and all conditions
  const release = http.del(`${BASE}/main/detail/${data.fixture.concertId}/seat-holds`, body, params);
  if (release.status === 204) released.add(1, tags); else failures.add(1, tags);
}
export function teardown() {
  const r = http.get(`${BASE}/loadtest/seat-holds/snapshot?runId=${RUN}`);
  if (r.status !== 200) throw new Error('Final snapshot unavailable.');
  const snapshot = r.json();
  console.log(`MIXED_SNAPSHOT ${JSON.stringify(snapshot)}`);
  if (!snapshot.invariantSatisfied || snapshot.activeHeldSeats !== 0) throw new Error('Hold/inventory invariant violated.');
}
export function handleSummary(data) {
  const values = (name) => data.metrics[name] ? data.metrics[name].values : {};
  const count = (name) => values(name).count || 0;
  const classes = {};
  for (const kind of ['small', 'large']) {
    const metric = (name) => `${name}{kind:${kind}}`;
    classes[kind] = {
      attempts: count(metric('mixed_attempts')), success: count(metric('mixed_success')),
      conflicts: count(metric('mixed_conflicts')), failures: count(metric('mixed_failures')),
      released: count(metric('mixed_released')), holdMs: values(metric('mixed_hold_ms')),
      successMs: values(metric('mixed_success_ms')), conflictMs: values(metric('mixed_conflict_ms')),
    };
  }
  return { stdout: `MIXED_RESULT ${JSON.stringify({ mode: MODE, largeSeats: SIZE, smallIterationsPerSecond: SMALL_RATE, largeIterationsPerSecond: MODE === 'small-only' ? 0 : LARGE_RATE, duration: DURATION, dwellMs: 50, classes, droppedIterations: count('dropped_iterations'), httpRequests: count('http_reqs') })}\n` };
}
