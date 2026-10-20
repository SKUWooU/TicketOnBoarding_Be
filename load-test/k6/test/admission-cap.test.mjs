import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { CAP_EXPERIMENT, capComparisonPlan, capPolicyDescription } from '../admission-cap-plan.mjs';
import { validateManifest, validateResult, summarizeCapRuns, assertDedicatedRedis, BASE, MANAGEMENT } from '../admission-session-contract.mjs';

function manifest(stage=capComparisonPlan()[0]) {
  const runId=`unit-${stage.sequence}`;
  const m={schemaVersion:1,baseUrl:BASE,managementUrl:MANAGEMENT,runId,concertId:`LOAD-TEST-${runId}`,
    concertTimeId:stage.sequence,totalSeats:20,databaseFingerprint:'a'.repeat(64),redisContainerId:'b'.repeat(64),
    experiment:CAP_EXPERIMENT,round:stage.round,sequence:stage.sequence,expectedActive:stage.active,
    mode:'on',sessionsPerSecond:5,durationSeconds:5,holdDwellMs:500,pollIntervalMs:5000,
    maxAdmissionWaitMs:75000,generation:`unit-${stage.sequence}`,opensAt:'2020-01-01T00:00:00Z',
    users:Array.from({length:27},(_,i)=>({username:`load-user-${runId}.${String(i+1).padStart(3,'0')}`,accessToken:`aaa.bbb.ccc${i}`}))};
  m.policyFingerprint=createHash('sha256').update(capPolicyDescription(m)).digest('hex'); return m;
}
function records() {
  const previous=JSON.parse(readFileSync(new URL('../../results/admission238-382b9a3b-comparison.json',import.meta.url),'utf8')).runs.at(-1);
  return capComparisonPlan().map(stage=>{
    const run=structuredClone(previous), m=manifest(stage);
    Object.assign(run,{runId:m.runId,experiment:CAP_EXPERIMENT,sequence:m.sequence,round:m.round,
      maxActive:m.expectedActive,policyFingerprintVerified:true});
    for(const phase of ['result','warmup']) Object.assign(run[phase],{experiment:CAP_EXPERIMENT,
      sequence:m.sequence,round:m.round,expectedActive:m.expectedActive});
    return run;
  });
}
test('cap limits have balanced positions at fixed five sessions/s',()=>{
  const plan=capComparisonPlan(); assert.equal(plan.length,9);
  for(const position of [0,1,2]) assert.deepEqual(plan.filter((_,i)=>i%3===position).map(s=>s.active).sort((a,b)=>a-b),[2,4,8]);
  for(const stage of plan) {assert.equal(stage.rate,5);assert.equal(stage.mode,'on');validateManifest(manifest(stage));}
});
test('cap profile rejects unknown experiments, swapped stage, missing identity and altered controls',()=>{
  for(const [key,value] of [['experiment','other'],['round',2],['sequence',2],['expectedActive',4],
    ['mode','off'],['sessionsPerSecond',2],['policyFingerprint',''],['redisContainerId',''],['pollIntervalMs',1000],['holdDwellMs',1000]]) {
    assert.throws(()=>validateManifest({...manifest(),[key]:value}),key);
  }
  const m=manifest(); delete m.experiment; m.expectedActive=4; assert.throws(()=>validateManifest(m));
});
test('canonical fingerprint includes actual active cap while keeping batch independent',()=>{
  const m=manifest(); assert.match(capPolicyDescription(m),/maxActive=2, maxWaiting=30, waitingTtl=PT2M, admissionTtl=PT2M, batchSize=2\]$/);
  assert.notEqual(m.policyFingerprint,manifest(capComparisonPlan()[1]).policyFingerprint);
  assert.throws(()=>capPolicyDescription({...m,generation:'../escape'}));
  const source=readFileSync(new URL('../../../onticket/src/main/java/com/onticket/admission/AdmissionPolicy.java',import.meta.url),'utf8');
  assert.match(source,/record AdmissionPolicy\(long concertTimeId, String generation, Instant opensAt,\s*int maxActive, int maxWaiting, Duration waitingTtl,\s*Duration admissionTtl, int batchSize\)/);
});
test('result must belong to its cap stage and aggregations retain per-run p95 semantics',()=>{
  const runs=records(), m=manifest(); validateResult(runs[0].result,m);
  assert.throws(()=>validateResult({...runs[0].result,expectedActive:8},m));
  const summary=summarizeCapRuns(runs);
  assert.deepEqual(summary.map(s=>[s.maxActive,s.repeats,s.sessions]),[[2,3,75],[4,3,75],[8,3,75]]);
  assert.equal(summary[0].waitP95Ms.medianOfRunP95,runs[0].result.waitMs['p(95)']);
  for(const mutate of [r=>r.pop(),r=>r.reverse(),r=>{r[1].runId=r[0].runId},
    r=>{r[0].policyFingerprintVerified=false},r=>{r[0].workerDelayMs=500},r=>{r[0].result.dropped=1},
    r=>{r[0].snapshot.remainingSeats=19},r=>{r[0].holdHttpCounters.after.success++}]) {
    const changed=records();mutate(changed);assert.throws(()=>summarizeCapRuns(changed));
  }
});
test('Redis policy reads accept only the newly created isolated loopback container',()=>{
  const id='b'.repeat(64),info={Id:id,Name:'/ticket-admission238-redis-1234abcd',Config:{Image:'redis:7.4.7-alpine',Labels:{'onticket.scope':'admission238'}},
    State:{Running:true},Mounts:[],HostConfig:{PortBindings:{'6379/tcp':[{HostIp:'127.0.0.1',HostPort:'17380'}]}}};
  assertDedicatedRedis(info,id);
  for(const changed of [{...info,Id:'c'.repeat(64)},{...info,Name:'/globaltimes-redis'},
    {...info,Mounts:[{Type:'bind',Destination:'/data'}]}, {...info,State:{Running:false}},
    {...info,HostConfig:{PortBindings:{'6379/tcp':[{HostIp:'0.0.0.0',HostPort:'17380'}]}}}]) assert.throws(()=>assertDedicatedRedis(changed,id));
});

test('published nine-stage cap comparison preserves balanced accounting and excludes credentials',()=>{
  const text=readFileSync(new URL('../../results/admission-cap-f3fff2ec-comparison.json',import.meta.url),'utf8'), batch=JSON.parse(text);
  assert.equal(batch.status,'EXPLORATION_COMPLETE');assert.equal(batch.runtimeCleanup,'COMPLETE');
  assert.equal(batch.experiment,CAP_EXPERIMENT);assert.match(batch.sourceBaseCommit,/^[a-f0-9]{40}$/);assert.match(batch.jarSha256,/^[a-f0-9]{64}$/);
  assert.deepEqual(summarizeCapRuns(batch.runs),batch.capSummaries);
  assert.equal(batch.runs.reduce((sum,r)=>sum+r.result.started,0),225);
  assert.deepEqual(batch.capSummaries.map(s=>[s.holdSuccess,s.seatConflicts,s.polls]),[[36,39,480],[34,41,238],[27,48,118]]);
  for(const run of batch.runs) {
    assert.equal(run.observation.hikariPendingPeak,0);assert.equal(run.observation.deadlockDelta,0);
    assert.ok(run.observation.samples>=2);assert.ok(run.observation.minimumFreeHostBytes>=2*1024**3);
  }
  function visit(value) {
    if(!value || typeof value!=='object')return;
    for(const [key,child] of Object.entries(value)){assert.doesNotMatch(key,/^(accessToken|token|cookie|password|users|username)$/i);visit(child);}
  }
  visit(batch);assert.doesNotMatch(text,/[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}/);
});
