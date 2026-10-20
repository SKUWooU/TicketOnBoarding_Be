import { CAP_EXPERIMENT, capComparisonPlan, validCapAssignment } from './admission-cap-plan.mjs';
import { POLL_EXPERIMENT, pollComparisonPlan, validPollAssignment } from './admission-poll-plan.mjs';

const validAssignment = m => validCapAssignment(m) || validPollAssignment(m);
export const advertisedPollInterval = m => m.experiment === POLL_EXPERIMENT ? m.policyPollIntervalMs : m.pollIntervalMs;

export const BASE = 'http://127.0.0.1:18080';
export const MANAGEMENT = 'http://127.0.0.1:18081';
// Hold conflicts use @ResponseStatus(409), unlike verified-reservation's separate conflict header.
export function classifyHoldStatus(status) {
  return status === 200 ? 'success' : status === 409 ? 'seatConflict' : 'unexpected';
}
export function validateManifest(m) {
  if (!m || m.schemaVersion !== 1 || m.baseUrl !== BASE || m.managementUrl !== MANAGEMENT
      || !/^[A-Za-z0-9-]{1,32}$/.test(m.runId || '') || m.concertId !== `LOAD-TEST-${m.runId}`
      || !Number.isSafeInteger(m.concertTimeId) || m.concertTimeId < 1 || m.totalSeats !== 20
      || !/^[a-f0-9]{64}$/.test(m.databaseFingerprint || '') || !['off', 'on'].includes(m.mode)
      || ![1, 2, 5].includes(m.sessionsPerSecond) || m.durationSeconds !== 5 || m.holdDwellMs !== 500
      || (m.experiment === POLL_EXPERIMENT ? !validPollAssignment(m) : m.pollIntervalMs !== 5000) || m.maxAdmissionWaitMs !== 75000
      || (m.experiment === undefined ? m.expectedActive !== 2 : !validAssignment(m))
      || (m.experiment !== undefined && (!/^[a-f0-9]{64}$/.test(m.policyFingerprint || '')
        || !/^[a-f0-9]{64}$/.test(m.redisContainerId || '')))
      || !/^[A-Za-z0-9-]{1,32}$/.test(m.generation || '') || m.opensAt !== '2020-01-01T00:00:00Z'
      || !Array.isArray(m.users) || m.users.length !== 2 + 5 * m.sessionsPerSecond) {
    throw new Error('Invalid bounded admission manifest');
  }
  const tokens = new Set();
  m.users.forEach((u, i) => {
    if (!u || u.username !== `load-user-${m.runId}.${String(i + 1).padStart(3, '0')}`
        || typeof u.accessToken !== 'string' || u.accessToken.length > 2048
        || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(u.accessToken)
        || tokens.has(u.accessToken)) throw new Error('Invalid bounded admission users');
    tokens.add(u.accessToken);
  });
  return m;
}
export function assertCleanInventory(s) {
  if (!s || s.expectedTotalSeats !== 20 || s.actualSeatCount !== 20 || s.remainingSeats !== 20
      || s.invariantSatisfied !== true || ['reservedSeats', 'activeHeldSeats', 'holdRows',
        'partialHoldStates', 'reservations', 'bookings', 'payments'].some(k => s[k] !== 0)) {
    throw new Error('Admission fixture inventory is not clean');
  }
}
export function validateResult(r, m, phase = 'measure') {
  const expected = phase === 'warmup' ? 2 : m.sessionsPerSecond * 5;
  const counts = ['started', 'finished', 'joined', 'admitted', 'left', 'polls', 'holdAttempts',
    'holdSuccess', 'seatConflicts', 'released', 'unexpected', 'waitTimeouts', 'dropped'];
  if (!r || r.schemaVersion !== 1 || r.mode !== m.mode || r.phase !== phase
      || r.sessionsPerSecond !== (phase === 'warmup' ? 1 : m.sessionsPerSecond)
      || r.durationSeconds !== (phase === 'warmup' ? 2 : 5)
      || counts.some(k => !Number.isSafeInteger(r[k]) || r[k] < 0)
      || r.started !== expected || r.finished !== expected || r.holdAttempts !== expected
      || r.holdSuccess + r.seatConflicts !== expected || r.holdSuccess < 1
      || r.released !== r.holdSuccess || r.unexpected !== 0 || r.waitTimeouts !== 0 || r.dropped !== 0
      || !Number.isSafeInteger(r.schedulerBoundarySkipped) || r.schedulerBoundarySkipped < 0 || r.schedulerBoundarySkipped > 1
      || !Number.isFinite(r.k6RunDurationMs) || r.k6RunDurationMs <= 0
      || ['holdMs', 'waitMs', 'sessionMs'].some(k => !r[k] || !Number.isFinite(r[k]['p(95)']) || r[k]['p(95)'] < 0)
      || ['joined', 'admitted', 'left'].some(k => r[k] !== (m.mode === 'on' ? expected : 0))
      || (m.mode === 'on' ? r.polls < expected : r.polls !== 0)) {
    throw new Error('Incomplete or inconsistent admission session accounting');
  }
  if (m.experiment !== undefined && (!validAssignment(m) || r.experiment !== m.experiment
      || r.expectedActive !== m.expectedActive || r.round !== m.round || r.sequence !== m.sequence
      || (m.experiment === POLL_EXPERIMENT && (r.pollIntervalMs !== m.pollIntervalMs || r.policyPollIntervalMs !== 5000
        || !Number.isFinite(r.pollRequestMs?.['p(95)']) || r.pollRequestMs['p(95)'] < 0)))) {
    throw new Error('Comparison result does not match assigned stage');
  }
  return r;
}

export function summarizeCapRuns(runs) {
  return summarizeComparisonRuns(runs, CAP_EXPERIMENT);
}

export function summarizePollRuns(runs) {
  return summarizeComparisonRuns(runs, POLL_EXPERIMENT);
}

function summarizeComparisonRuns(runs, experiment) {
  const polling = experiment === POLL_EXPERIMENT;
  const plan = polling ? pollComparisonPlan() : capComparisonPlan();
  if (!Array.isArray(runs) || runs.length !== plan.length) throw new Error('Incomplete cap comparison');
  if (new Set(runs.map(r => r.runId)).size !== plan.length) throw new Error('Fresh cap fixtures required');
  runs.forEach((run, i) => {
    const stage = plan[i];
    if (run.experiment !== experiment || run.sequence !== stage.sequence || run.round !== stage.round
        || run.maxActive !== stage.active || run.mode !== 'on' || run.sessionsPerSecond !== 5
        || run.status !== 'PASS' || run.escalationAllowed !== true || run.policyFingerprintVerified !== true
        || run.pollIntervalMs !== (polling ? stage.pollIntervalMs : 5000)
        || (polling && (run.policyPollIntervalMs !== 5000 || !Number.isFinite(run.observation?.sampledPollRpsPeak)
          || run.observation.sampledPollRpsPeak < 0))
        || run.workerDelayMs !== 1000 || run.holdDwellMs !== 500 || run.totalSeats !== 20
        || !run.observation || ['hikariPendingPeak', 'hikariActivePeak', 'rowLockWaitDelta', 'rowLockTimeMsDelta']
          .some(k => !Number.isFinite(run.observation[k]) || run.observation[k] < 0)) {
      throw new Error('Cap stage order, identity or fixed conditions changed');
    }
    const m = { ...run, expectedActive: run.maxActive };
    validateResult(run.result, m); validateResult(run.warmup, m, 'warmup'); assertCleanInventory(run.snapshot);
    assertHttpAgreement(run.holdHttpCounters.before, run.holdHttpCounters.after, run.result);
    if (polling) assertPollHttpAgreement(run.pollHttpCounters.before, run.pollHttpCounters.after, run.result);
  });
  function range(values) {
    if (values.some(v => !Number.isFinite(v))) throw new Error('Missing cap timing evidence');
    const sorted = [...values].sort((a, b) => a - b);
    return { minimum: sorted[0], medianOfRunP95: sorted[1], maximum: sorted[2] };
  }
  return (polling ? [5000, 2000, 1000] : [2, 4, 8]).map(value => {
    const selected = runs.filter(r => (polling ? r.pollIntervalMs : r.maxActive) === value);
    const sum = field => selected.reduce((total, r) => total + r.result[field], 0);
    return { ...(polling ? {pollIntervalMs:value, maxActive:4} : {maxActive:value}), repeats: 3, sessions: sum('started'), holdSuccess: sum('holdSuccess'),
      seatConflicts: sum('seatConflicts'), polls: sum('polls'), unexpected: sum('unexpected'), dropped: sum('dropped'),
      waitP95Ms: range(selected.map(r => r.result.waitMs['p(95)'])),
      sessionP95Ms: range(selected.map(r => r.result.sessionMs['p(95)'])),
      holdP95Ms: range(selected.map(r => r.result.holdMs['p(95)'])),
      ...(polling ? {pollRequestP95Ms:range(selected.map(r=>r.result.pollRequestMs['p(95)'])),
        sampledPollRpsPeak:Math.max(...selected.map(r=>r.observation.sampledPollRpsPeak))} : {}),
      sampledHikariPendingPeak: Math.max(...selected.map(r => r.observation.hikariPendingPeak)),
      sampledHikariActivePeak: Math.max(...selected.map(r => r.observation.hikariActivePeak)),
      sampledRowLockWaits: selected.reduce((s, r) => s + r.observation.rowLockWaitDelta, 0),
      sampledRowLockTimeMs: selected.reduce((s, r) => s + r.observation.rowLockTimeMsDelta, 0) };
  });
}

export function parsePool(text) {
  const output = {};
  for (const field of ['active', 'pending', 'max']) {
    const matches = [...text.matchAll(new RegExp(`^hikaricp_connections_${field}(?:\\{[^}]*\\})?\\s+([0-9.eE+-]+)\\s*$`, 'gm'))];
    if (matches.length !== 1 || !Number.isFinite(Number(matches[0][1]))) throw new Error('Missing/ambiguous pool sample');
    output[field] = Number(matches[0][1]);
  }
  return output;
}

export function holdHttpCounts(text) {
  const counts = { success: 0, conflict: 0, other: 0 };
  for (const line of text.split(/\r?\n/)) {
    const match = /^http_server_requests_seconds_count\{(.+)\}\s+([0-9.eE+-]+)\s*$/.exec(line);
    if (!match) continue;
    const labels = Object.fromEntries([...match[1].matchAll(/([a-zA-Z_]+)="([^"]*)"/g)].map(m => [m[1], m[2]]));
    if (labels.method !== 'POST' || labels.uri !== '/main/detail/{concertId}/seat-holds') continue;
    const value = Number(match[2]);
    if (!Number.isFinite(value) || value < 0) throw new Error('Invalid Hold HTTP count');
    counts[labels.status === '200' ? 'success' : labels.status === '409' ? 'conflict' : 'other'] += value;
  }
  return counts;
}

export function assertHttpAgreement(before, after, result) {
  if (after.success - before.success !== result.holdSuccess
      || after.conflict - before.conflict !== result.seatConflicts || after.other - before.other !== 0) {
    throw new Error('Hold HTTP counters do not match k6 outcomes');
  }
}

export function pollHttpCounts(text) {
  const counts = {success:0, other:0};
  for (const line of text.split(/\r?\n/)) {
    const match = /^http_server_requests_seconds_count\{(.+)\}\s+([0-9.eE+-]+)\s*$/.exec(line);
    if (!match) continue;
    const labels = Object.fromEntries([...match[1].matchAll(/([a-zA-Z_]+)="([^"]*)"/g)].map(m => [m[1],m[2]]));
    if (labels.method !== 'GET' || labels.uri !== '/main/detail/{concertId}/times/{concertTimeId}/admission') continue;
    const value = Number(match[2]);
    if (!Number.isFinite(value) || value < 0) throw new Error('Invalid polling HTTP count');
    // k6 setup checks NOT_JOINED with GET 404 before the first join; not a workload poll.
    if (labels.status !== '404') counts[labels.status === '200' ? 'success' : 'other'] += value;
  }
  return counts;
}

export function assertPollHttpAgreement(before, after, result) {
  if (!before || !after || after.success - before.success !== result.polls || after.other - before.other !== 0) {
    throw new Error('Polling HTTP counters do not match k6 reads');
  }
}

export function assertDedicatedDatabase(info, id) {
  if (!/^[a-f0-9]{64}$/.test(id || '') || info.Id !== id
      || !/^\/ticket-admission238-db-[a-f0-9]{8}$/.test(info.Name || '')
      || info.Config?.Image !== 'mariadb:10.11.8' || info.Config?.Labels?.['onticket.scope'] !== 'admission238'
      || info.State?.Running !== true || !Array.isArray(info.Mounts) || info.Mounts.length > 1
      || info.Mounts.some(m => m.Type !== 'volume' || !/^[a-f0-9]{64}$/.test(m.Name || '') || m.Destination !== '/var/lib/mysql')
      || info.HostConfig?.PortBindings?.['3306/tcp']?.length !== 1
      || info.HostConfig.PortBindings['3306/tcp'][0].HostIp !== '127.0.0.1'
      || info.HostConfig.PortBindings['3306/tcp'][0].HostPort !== '13308') {
    throw new Error('Not the dedicated ephemeral admission database');
  }
}
export function assertDedicatedRedis(info, id) {
  if (!/^[a-f0-9]{64}$/.test(id || '') || info.Id !== id
      || !/^\/ticket-admission238-redis-[a-f0-9]{8}$/.test(info.Name || '')
      || info.Config?.Image !== 'redis:7.4.7-alpine' || info.Config?.Labels?.['onticket.scope'] !== 'admission238'
      || info.State?.Running !== true || !Array.isArray(info.Mounts) || info.Mounts.length > 1
      || info.Mounts.some(m => m.Type !== 'volume' || !/^[a-f0-9]{64}$/.test(m.Name || '') || m.Destination !== '/data')
      || info.HostConfig?.PortBindings?.['6379/tcp']?.length !== 1
      || info.HostConfig.PortBindings['6379/tcp'][0].HostIp !== '127.0.0.1'
      || info.HostConfig.PortBindings['6379/tcp'][0].HostPort !== '17380') {
    throw new Error('Not the dedicated ephemeral admission Redis');
  }
}
