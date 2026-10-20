import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';
import { validateManifest, validateResult, assertCleanInventory, parsePool, holdHttpCounts,
  assertHttpAgreement, assertDedicatedDatabase, classifyHoldStatus, BASE, MANAGEMENT } from '../admission-session-contract.mjs';
const manifest = () => ({ schemaVersion: 1, baseUrl: BASE, managementUrl: MANAGEMENT, runId: 'unit',
  concertId: 'LOAD-TEST-unit', concertTimeId: 1, totalSeats: 20, databaseFingerprint: 'a'.repeat(64),
  mode: 'on', sessionsPerSecond: 1, durationSeconds: 5, holdDwellMs: 500, pollIntervalMs: 5000,
  maxAdmissionWaitMs: 75000, expectedActive: 2, generation: 'g1', opensAt: '2020-01-01T00:00:00Z',
  users: Array.from({ length: 7 }, (_, i) => ({ username: `load-user-unit.${String(i + 1).padStart(3, '0')}`, accessToken: `aaa.bbb.ccc${i}` })) });
const result = () => ({ schemaVersion: 1, mode: 'on', phase: 'measure', sessionsPerSecond: 1, durationSeconds: 5,
  started: 5, finished: 5, joined: 5, admitted: 5, left: 5, polls: 5, holdAttempts: 5, holdSuccess: 3,
  seatConflicts: 2, released: 3, unexpected: 0, waitTimeouts: 0, dropped: 0,
  k6RunDurationMs: 5000, schedulerBoundarySkipped: 0,
  holdMs: { 'p(95)': 10 }, waitMs: { 'p(95)': 5000 }, sessionMs: { 'p(95)': 5500 } });
test('bounded manifest and complete on/off accounting', () => {
  const m = validateManifest(manifest()); validateResult(result(), m);
  m.mode = 'off'; const r = result(); Object.assign(r, { mode: 'off', joined: 0, admitted: 0, left: 0, polls: 0 });
  validateResult(r, m);
});
test('reject remote, cross-fixture/user, wrong rate and credential reuse before execution', () => {
  for (const [key, value] of [['baseUrl', 'https://example.com'], ['managementUrl', 'http://localhost:18081'],
    ['runId', '../escape'], ['concertId', 'real-show'], ['sessionsPerSecond', 100], ['durationSeconds', 60],
    ['pollIntervalMs', 10], ['expectedActive', 100], ['databaseFingerprint', 'invalid']]) {
    const m = manifest(); m[key] = value; assert.throws(() => validateManifest(m));
  }
  const m = manifest(); m.users[1].accessToken = m.users[0].accessToken; assert.throws(() => validateManifest(m));
  m.users[1].accessToken = 'aaa.bbb.distinct'; m.users[1].username = 'another-user'; assert.throws(() => validateManifest(m));
});
test('dropped, missing, unreleased and failed sessions cannot be successful evidence', () => {
  for (const [key, value] of [['started', 4], ['finished', 4], ['holdAttempts', 4], ['released', 2],
    ['unexpected', 1], ['waitTimeouts', 1], ['dropped', 1], ['left', 4], ['polls', 0], ['admitted', NaN],
    ['holdMs', {}], ['k6RunDurationMs', NaN], ['schedulerBoundarySkipped', 2]]) {
    const r = result(); r[key] = value; assert.throws(() => validateResult(r, manifest()));
  }
});
test('clean inventory must include every zero counter', () => {
  const s = { expectedTotalSeats: 20, actualSeatCount: 20, remainingSeats: 20, invariantSatisfied: true,
    reservedSeats: 0, activeHeldSeats: 0, holdRows: 0, partialHoldStates: 0, reservations: 0, bookings: 0, payments: 0 };
  assertCleanInventory(s); for (const key of Object.keys(s)) { const dirty = { ...s }; delete dirty[key]; assert.throws(() => assertCleanInventory(dirty)); }
});
test('pool samples reject missing or duplicate series', () => {
  const text = 'hikaricp_connections_active{pool="one"} 1\nhikaricp_connections_pending{pool="one"} 0\nhikaricp_connections_max{pool="one"} 4\n';
  assert.deepEqual(parsePool(text), { active: 1, pending: 0, max: 4 });
  assert.throws(() => parsePool('')); assert.throws(() => parsePool(text + text));
});
test('server Hold HTTP deltas must agree with client success/conflict outcomes', () => {
  const prom = 'http_server_requests_seconds_count{method="POST",uri="/main/detail/{concertId}/seat-holds",status="200"} 3\n'
    + 'http_server_requests_seconds_count{method="POST",uri="/main/detail/{concertId}/seat-holds",status="409"} 2\n';
  const after = holdHttpCounts(prom); assertHttpAgreement({ success: 0, conflict: 0, other: 0 }, after, result());
  assert.throws(() => assertHttpAgreement({ success: 1, conflict: 0, other: 0 }, after, result()));
});
test('only explicit ephemeral labelled loopback database is accepted', () => {
  const id = 'a'.repeat(64); const info = { Id: id, Name: `/ticket-admission238-db-${id.slice(0, 8)}`,
    Config: { Image: 'mariadb:10.11.8', Labels: { 'onticket.scope': 'admission238' } }, State: { Running: true }, Mounts: [],
    HostConfig: { PortBindings: { '3306/tcp': [{ HostIp: '127.0.0.1', HostPort: '13308' }] } } };
  assertDedicatedDatabase(info, id); assert.throws(() => assertDedicatedDatabase({ ...info, Mounts: [{}] }, id));
  assert.throws(() => assertDedicatedDatabase({ ...info, Name: '/globaltimes' }, id));
});
test('Hold 409 does not require verified-reservation conflict headers', () => {
  assert.equal(classifyHoldStatus(200), 'success'); assert.equal(classifyHoldStatus(409), 'seatConflict');
  for (const status of [0, 400, 401, 403, 404, 429, 500, 503]) assert.equal(classifyHoldStatus(status), 'unexpected');
});
test('runtime orchestration refuses to start without explicit disposable-resource confirmation', () => {
  const script = fileURLToPath(new URL('../../scripts/run-admission-session-comparison.mjs', import.meta.url));
  const r = spawnSync(process.execPath, [script], { encoding: 'utf8', timeout: 5000, windowsHide: true });
  assert.equal(r.status, 1); assert.match(r.stderr, /confirm-disposable-local-resources/);
  assert.equal(r.stdout, '');
});
test('published six-stage exploration preserves session, HTTP and inventory accounting without secrets', () => {
  const text = readFileSync(new URL('../../results/admission238-382b9a3b-comparison.json', import.meta.url), 'utf8');
  const batch = JSON.parse(text);
  assert.equal(batch.status, 'EXPLORATION_COMPLETE');
  assert.deepEqual(batch.runs.map(r => [r.sessionsPerSecond, r.mode]), [[1,'off'],[1,'on'],[2,'on'],[2,'off'],[5,'off'],[5,'on']]);
  for (const run of batch.runs) {
    validateResult(run.result, run); validateResult(run.warmup, run, 'warmup'); assertCleanInventory(run.snapshot);
    assertHttpAgreement(run.holdHttpCounters.before, run.holdHttpCounters.after, run.result);
    assert.equal(run.status, 'PASS'); assert.equal(run.escalationAllowed, true);
    assert.equal(run.observation.hikariPendingPeak, 0); assert.equal(run.observation.deadlockDelta, 0);
    assert.ok(run.observation.samples >= 2);
  }
  assert.equal(batch.runs.reduce((sum,r) => sum + r.result.started, 0), 80);
  const visit = object => {
    if (!object || typeof object !== 'object') return;
    for (const [key,value] of Object.entries(object)) {
      assert.doesNotMatch(key, /^(accessToken|token|cookie|password|users|username)$/i); visit(value);
    }
  };
  visit(batch);
  assert.doesNotMatch(text, /[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}/);
});
