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
      || m.pollIntervalMs !== 5000 || m.maxAdmissionWaitMs !== 75000 || m.expectedActive !== 2
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
  return r;
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
