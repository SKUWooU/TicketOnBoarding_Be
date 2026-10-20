import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { validateManifest, assertCleanInventory, classifyHoldStatus, BASE, MANAGEMENT } from './admission-session-contract.mjs';

// Credentials are prepared before admission activation; never fetch tokens or mutate fixtures here.
const m = validateManifest(JSON.parse(open(__ENV.ADMISSION_MANIFEST)));
const phase = __ENV.ADMISSION_PHASE || 'measure';
if (!['warmup', 'measure'].includes(phase)) throw new Error('Invalid admission phase');
const rate = phase === 'warmup' ? 1 : m.sessionsPerSecond;
const duration = phase === 'warmup' ? 2 : 5;
const startIndex = phase === 'warmup' ? 0 : 2;
const counters = {};
for (const name of ['started', 'finished', 'joined', 'admitted', 'left', 'polls', 'holdAttempts',
  'holdSuccess', 'seatConflicts', 'released', 'unexpected', 'waitTimeouts']) counters[name] = new Counter(`admission_${name}`);
const waits = new Trend('admission_wait_ms', true);
const holds = new Trend('admission_hold_ms', true);
const successes = new Trend('admission_hold_success_ms', true);
const conflicts = new Trend('admission_hold_conflict_ms', true);
const sessions = new Trend('admission_session_ms', true);
const boundarySkipped = new Counter('admission_boundary_skipped');
const prefix = `${BASE}/main/detail/${m.concertId}`;
const queue = `${prefix}/times/${m.concertTimeId}/admission`;
const body = JSON.stringify({ concertTimeId: m.concertTimeId, seatNumberList: ['R001-S001'] });
http.setResponseCallback(http.expectedStatuses(200, 204, 404, 409));
export const options = {
  scenarios: { purchase_sessions: { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${duration}s`,
    preAllocatedVUs: 32, maxVUs: 32, gracefulStop: '90s' } },
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  thresholds: { admission_unexpected: ['count==0'], admission_waitTimeouts: ['count==0'],
    admission_boundary_skipped: ['count<=1'], dropped_iterations: ['count==0'] },
};
function params(user, name, token) {
  return { redirects: 0, timeout: '5s', tags: { name }, headers: {
    'Content-Type': 'application/json', Cookie: `accessToken=${user.accessToken}`,
    ...(token ? { 'X-Admission-Token': token } : {}),
  } };
}
function data(response) { try { return response.json(); } catch { throw new Error('Invalid local response'); } }
function read(path) {
  const r = http.get(path, { redirects: 0, timeout: '5s', tags: { name: 'preflight' } });
  if (r.status !== 200) throw new Error('Local preflight rejected');
  return data(r);
}
export function setup() {
  if (read(`${MANAGEMENT}/actuator/health`).status !== 'UP'
      || read(`${BASE}/loadtest/database-identity`).fingerprint !== m.databaseFingerprint) throw new Error('Local target mismatch');
  const fixture = read(`${BASE}/loadtest/fixture?runId=${m.runId}`);
  if (fixture.concertId !== m.concertId || fixture.concertTimeId !== m.concertTimeId || fixture.totalSeats !== 20) throw new Error('Fixture mismatch');
  const policy = read(`${queue}-policy`);
  if (policy.enabled !== (m.mode === 'on') || (m.mode === 'on' && (policy.generation !== m.generation
      || Date.parse(policy.opensAt) !== Date.parse(m.opensAt) || policy.pollIntervalMillis !== m.pollIntervalMs))) throw new Error('Policy mismatch');
  assertCleanInventory(read(`${BASE}/loadtest/seat-holds/snapshot?runId=${m.runId}`));
  for (const u of m.users.slice(startIndex, startIndex + rate * duration)) {
    const auth = http.get(`${BASE}/auth/valid`, params(u, 'preflight_auth'));
    if (auth.status !== 200 || data(auth).userName !== u.username || data(auth).valid !== true) throw new Error('Prepared user mismatch');
    if (m.mode === 'on') {
      const r = http.get(queue, params(u, 'preflight_entry'));
      if (r.status !== 404 || data(r).code !== 'ADMISSION_NOT_JOINED') throw new Error('User already joined');
    }
  }
}
export default function () {
  boundarySkipped.add(0);
  Object.values(counters).forEach(c => c.add(0));
  // Some timer boundaries schedule one iteration exactly at duration. Record it, but never reuse a phase's user.
  if (exec.scenario.iterationInTest >= rate * duration) {
    boundarySkipped.add(1);
    if (exec.scenario.iterationInTest > rate * duration) counters.unexpected.add(1);
    return;
  }
  counters.started.add(1);
  const began = Date.now();
  const u = m.users[startIndex + exec.scenario.iterationInTest];
  let entry = null; let held = false;
  try {
    if (!u) throw new Error('User budget exhausted');
    let admissionToken;
    if (m.mode === 'on') {
      const waitingAt = Date.now();
      const joined = http.post(queue, null, params(u, 'admission_join'));
      if (joined.status !== 200) throw new Error('Join failed');
      entry = data(joined).admission; counters.joined.add(1);
      while (entry.state === 'WAITING') {
        if (Date.now() - waitingAt + m.pollIntervalMs > m.maxAdmissionWaitMs) {
          counters.waitTimeouts.add(1); throw new Error('Admission wait budget exceeded');
        }
        sleep(m.pollIntervalMs / 1000); counters.polls.add(1);
        const r = http.get(queue, params(u, 'admission_poll'));
        if (r.status !== 200) throw new Error('Poll failed');
        entry = data(r).admission;
      }
      if (entry.state !== 'ADMITTED' || typeof entry.token !== 'string') throw new Error('Admission not granted');
      admissionToken = entry.token; counters.admitted.add(1); waits.add(Date.now() - waitingAt);
    } else waits.add(0);
    counters.holdAttempts.add(1);
    const r = http.post(`${prefix}/seat-holds`, body, params(u, 'purchase_hold', admissionToken));
    holds.add(r.timings.duration);
    const outcome = classifyHoldStatus(r.status);
    if (outcome === 'success') {
      held = true; counters.holdSuccess.add(1); successes.add(r.timings.duration); sleep(m.holdDwellMs / 1000);
    } else if (outcome === 'seatConflict') {
      counters.seatConflicts.add(1); conflicts.add(r.timings.duration);
    } else throw new Error('Unexpected purchase result');
  } catch {
    counters.unexpected.add(1); // Never print response/request/credentials.
  } finally {
    if (held) {
      const r = http.del(`${prefix}/seat-holds`, body, params(u, 'purchase_release'));
      if (r.status === 204) counters.released.add(1); else counters.unexpected.add(1);
    }
    if (entry && u) {
      const r = http.del(`${queue}?generation=${m.generation}&queueEntryId=${encodeURIComponent(entry.queueEntryId)}`,
        null, params(u, 'admission_leave'));
      if (r.status === 204) counters.left.add(1); else counters.unexpected.add(1);
    }
    sessions.add(Date.now() - began); counters.finished.add(1);
  }
}
export function teardown() { assertCleanInventory(read(`${BASE}/loadtest/seat-holds/snapshot?runId=${m.runId}`)); }
export function handleSummary(d) {
  const values = name => d.metrics[name] ? d.metrics[name].values : {};
  const result = { schemaVersion: 1, mode: m.mode, phase, sessionsPerSecond: rate, durationSeconds: duration,
    k6RunDurationMs: d.state.testRunDurationMs, httpRequestsIncludingPreflight: values('http_reqs').count || 0,
    schedulerBoundarySkipped: values('admission_boundary_skipped').count || 0,
    dropped: values('dropped_iterations').count || 0, waitMs: values('admission_wait_ms'),
    holdMs: values('admission_hold_ms'), successHoldMs: values('admission_hold_success_ms'),
    conflictHoldMs: values('admission_hold_conflict_ms'), sessionMs: values('admission_session_ms') };
  for (const name of Object.keys(counters)) result[name] = values(`admission_${name}`).count || 0;
  return { stdout: `ADMISSION_RESULT ${JSON.stringify(result)}\n` };
}
