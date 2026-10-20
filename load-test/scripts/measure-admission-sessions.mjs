import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { cpus, freemem } from 'node:os';
import { createHash } from 'node:crypto';
import { admissionPolicyDescription } from '../k6/admission-cap-plan.mjs';
import { POLL_EXPERIMENT } from '../k6/admission-poll-plan.mjs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { validateManifest, validateResult, assertCleanInventory, parsePool, holdHttpCounts,
  assertHttpAgreement, pollHttpCounts, assertPollHttpAgreement, assertDedicatedDatabase, assertDedicatedRedis, BASE, MANAGEMENT } from '../k6/admission-session-contract.mjs';
const exec = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function command(name, args) {
  const { stdout } = await exec(name, args, { timeout: 10000, maxBuffer: 1024 * 1024, windowsHide: true });
  return stdout.trim();
}
async function get(url, text = false) {
  const r = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(5000) });
  if (!r.ok) throw new Error('Local measurement read failed');
  return text ? r.text() : r.json();
}
function cpuTotals() {
  const all = cpus().map(c => c.times);
  return { idle: all.reduce((s, t) => s + t.idle, 0), total: all.reduce((s, t) => s + Object.values(t).reduce((a, b) => a + b, 0), 0) };
}
export async function measure(manifestPath) {
  if (process.platform !== 'win32') throw new Error('This bounded collector requires a Windows host');
  const raw = await readFile(manifestPath, 'utf8');
  if (Buffer.byteLength(raw) > 64 * 1024) throw new Error('Manifest too large');
  const m = validateManifest(JSON.parse(raw.replace(/^\uFEFF/, '')));
  const dbId = m.databaseContainerId;
  if (!/^[a-f0-9]{64}$/.test(dbId || '')) throw new Error('Explicit database container ID required');
  const inspect = JSON.parse(await command('docker', ['inspect', dbId]));
  assertDedicatedDatabase(inspect[0], dbId);
  async function verifyPolicy() {
    if (m.experiment === undefined) return;
    const redis = JSON.parse(await command('docker', ['inspect', m.redisContainerId]));
    assertDedicatedRedis(redis[0], m.redisContainerId);
    const expected = createHash('sha256').update(admissionPolicyDescription(m)).digest('hex');
    const actual = await command('docker', ['exec', m.redisContainerId, 'redis-cli', '--raw', 'HGET',
      `admission:{${m.concertTimeId}:${m.generation}}:meta`, 'policy']);
    if (expected !== m.policyFingerprint || actual !== expected) throw new Error('Admission policy fingerprint mismatch');
  }
  await verifyPolicy();
  if (freemem() < 2 * 1024 ** 3) throw new Error('Host memory below 2GiB; no workload started');
  const sqlIdentity = await command('docker', ['exec', dbId, 'mariadb', '-uonticket', '-ponticket', '-N', '-B',
    'onticket_admission238', '-e', "SELECT SHA2(CONCAT(@@hostname, ':', DATABASE()), 256)"]);
  if (sqlIdentity !== m.databaseFingerprint || (await get(`${BASE}/loadtest/database-identity`)).fingerprint !== sqlIdentity) {
    throw new Error('Dedicated database identity mismatch');
  }
  const folder = resolve(root, 'load-test/results', m.runId);
  await mkdir(folder); // no overwrite, reset or deletion of existing evidence
  let child;
  try {
    async function run(phase, samples) {
      const args = ['run', '--quiet', '-e', `ADMISSION_MANIFEST=${resolve(manifestPath)}`, '-e', `ADMISSION_PHASE=${phase}`,
        resolve(root, 'load-test/k6/admission-sessions.js')];
      child = spawn('k6', args, { windowsHide: true, env: { ...process.env, K6_NO_USAGE_REPORT: 'true', K6_WEB_DASHBOARD: 'false' } });
      let stdout = ''; let stderr = ''; let overflow = false;
      const append = (chunk, kind) => {
        if (stdout.length + stderr.length + chunk.length > 1024 * 1024) { overflow = true; child.kill(); return; }
        if (kind === 'out') stdout += chunk; else stderr += chunk;
      };
      child.stdout.on('data', chunk => append(chunk, 'out')); child.stderr.on('data', chunk => append(chunk, 'err'));
      let done = false; let code;
      const completed = new Promise(res => {
        child.once('error', () => { done = true; code = -1; res(); });
        child.once('close', c => { done = true; code = c; res(); });
      });
      const began = Date.now(); let cpu = cpuTotals(); let hotSamples = 0;
      try {
        while (!done) {
          await sleep(1000);
          if (Date.now() - began > 120000) throw new Error('Bounded run deadline exceeded');
          if (freemem() < 2 * 1024 ** 3) throw new Error('Host memory stop gate');
          const now = cpuTotals();
          const usage = now.total > cpu.total ? 100 * (1 - (now.idle - cpu.idle) / (now.total - cpu.total)) : null;
          cpu = now;
          if (usage === null) throw new Error('Host CPU sample missing');
          hotSamples = usage >= 90 ? hotSamples + 1 : 0;
          if (samples && !done) {
            const prom = await get(`${MANAGEMENT}/actuator/prometheus`, true);
            const db = await command('docker', ['exec', dbId, 'mariadb', '-uonticket', '-ponticket', '-N', '-B', '-e',
              "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_deadlocks','Innodb_row_lock_time','Innodb_row_lock_waits','Threads_connected','Threads_running')"]);
            const status = Object.fromEntries(db.split(/\r?\n/).map(line => { const [k, v] = line.split(/\s+/); return [k, Number(v)]; }));
            if (['Innodb_deadlocks', 'Innodb_row_lock_time', 'Innodb_row_lock_waits', 'Threads_connected', 'Threads_running']
              .some(k => !Number.isFinite(status[k]))) throw new Error('DB counter sample missing');
            samples.push({ atMs: Date.now() - began, hostCpuPercent: usage, freeHostBytes: freemem(), pool: parsePool(prom), db: status,
              ...(m.experiment === POLL_EXPERIMENT ? {pollSuccessCount:pollHttpCounts(prom).success} : {}) });
          }
          if (hotSamples >= 3) throw new Error('Host CPU >=90% for three consecutive samples; stop escalation');
        }
        await completed;
      } finally {
        if (!done) { child.kill(); await completed.catch(() => {}); }
        // These private console files are not published; structured evidence is allowlisted below.
        await writeFile(resolve(folder, `${phase}.stdout.log`), stdout);
        await writeFile(resolve(folder, `${phase}.stderr.log`), stderr);
        if (samples) await writeFile(resolve(folder, `${phase}.samples.json`), JSON.stringify(samples, null, 2));
      }
      if (overflow || code !== 0) throw new Error('k6 did not complete successfully');
      const lines = stdout.split(/\r?\n/).filter(l => l.startsWith('ADMISSION_RESULT '));
      if (lines.length !== 1) throw new Error('Missing/ambiguous k6 result');
      return validateResult(JSON.parse(lines[0].slice(17)), m, phase);
    }
    const warmup = await run('warmup', null);
    await sleep(2000);
    const before = await get(`${MANAGEMENT}/actuator/prometheus`, true);
    const samples = [];
    const result = await run('measure', samples);
    await verifyPolicy();
    const after = await get(`${MANAGEMENT}/actuator/prometheus`, true);
    const snapshot = await get(`${BASE}/loadtest/seat-holds/snapshot?runId=${m.runId}`);
    assertCleanInventory(snapshot);
    const httpBefore = holdHttpCounts(before); const httpAfter = holdHttpCounts(after);
    assertHttpAgreement(httpBefore, httpAfter, result);
    const pollCounters = {before:pollHttpCounts(before), after:pollHttpCounts(after)};
    if (m.experiment === POLL_EXPERIMENT) assertPollHttpAgreement(pollCounters.before, pollCounters.after, result);
    if (samples.length < 2) throw new Error('Insufficient resource samples');
    const first = samples[0]; const last = samples.at(-1);
    if (last.db.Innodb_deadlocks !== first.db.Innodb_deadlocks) throw new Error('Observed SQL deadlock');
    const record = { schemaVersion: 1, status: 'PASS', scope: 'short local virtual 20-seat admission exploration; not capacity or production performance',
      runId: m.runId, mode: m.mode, sessionsPerSecond: m.sessionsPerSecond, totalSeats: 20, hotSeats: 1,
      ...(m.experiment ? { experiment: m.experiment, round: m.round, sequence: m.sequence, policyFingerprintVerified: true } : {}),
      ...(m.experiment === POLL_EXPERIMENT ? {policyPollIntervalMs:m.policyPollIntervalMs, pollHttpCounters:pollCounters} : {}),
      holdDwellMs: 500, maxActive: m.mode === 'on' ? m.expectedActive : null, pollIntervalMs: m.mode === 'on' ? m.pollIntervalMs : null,
      workerDelayMs: m.mode === 'on' ? 1000 : null, warmup, result, snapshot, holdHttpCounters: { before: httpBefore, after: httpAfter },
      observation: { samples: samples.length, hikariPendingPeak: Math.max(...samples.map(s => s.pool.pending)),
        hikariActivePeak: Math.max(...samples.map(s => s.pool.active)), hikariMax: first.pool.max,
        hostCpuPeakPercent: Math.max(...samples.map(s => s.hostCpuPercent)), minimumFreeHostBytes: Math.min(...samples.map(s => s.freeHostBytes)),
        rowLockWaitDelta: last.db.Innodb_row_lock_waits - first.db.Innodb_row_lock_waits,
        ...(m.experiment === POLL_EXPERIMENT ? {sampledPollRpsPeak:Math.max(...samples.slice(1).map((s,i)=>
          1000*(s.pollSuccessCount-samples[i].pollSuccessCount)/(s.atMs-samples[i].atMs)))} : {}),
        rowLockTimeMsDelta: last.db.Innodb_row_lock_time - first.db.Innodb_row_lock_time,
        deadlockDelta: last.db.Innodb_deadlocks - first.db.Innodb_deadlocks,
        requestedPauseMs: 1000, actualSampleGapsMs: samples.slice(1).map((s, i) => s.atMs - samples[i].atMs),
        counterScope: 'first-to-last sampled counter window, includes drain and collector SQL; not query attribution or whole-run delta' } };
    record.escalationAllowed = record.observation.hikariPendingPeak === 0 && record.observation.hikariActivePeak < first.pool.max;
    if (!record.escalationAllowed) record.status = 'RESOURCE_REVIEW_REQUIRED';
    await writeFile(resolve(folder, 'samples.json'), JSON.stringify(samples, null, 2));
    await writeFile(resolve(folder, 'summary.json'), JSON.stringify(record, null, 2));
    if (!record.escalationAllowed) {
      throw new Error('Pool pressure observed; preserve run and stop escalation');
    }
    console.log(JSON.stringify({ runId: m.runId, mode: m.mode, rate: m.sessionsPerSecond, status: 'PASS',
      started: result.started, success: result.holdSuccess, conflicts: result.seatConflicts,
      holdP95: result.holdMs['p(95)'], waitP95: result.waitMs['p(95)'], sessionP95: result.sessionMs['p(95)'],
      pendingPeak: record.observation.hikariPendingPeak }));
    return record;
  } catch (error) {
    await writeFile(resolve(folder, 'failure.json'), JSON.stringify({ status: 'STOPPED', runId: m.runId,
      reason: 'Measurement incomplete or resource/consistency gate; preserve private console and revalidate before escalation' }));
    throw error;
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  measure(process.argv[2]).catch(() => { console.error('ADMISSION_MEASUREMENT_STOPPED; private evidence retained'); process.exitCode = 1; });
}
