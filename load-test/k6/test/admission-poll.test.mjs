import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {POLL_EXPERIMENT,pollComparisonPlan} from '../admission-poll-plan.mjs';
import {admissionPolicyDescription} from '../admission-cap-plan.mjs';
import {validateManifest,validateResult,summarizePollRuns,advertisedPollInterval,pollHttpCounts,assertPollHttpAgreement,BASE,MANAGEMENT} from '../admission-session-contract.mjs';

function manifest(stage=pollComparisonPlan()[0]) {
  const runId=`poll-unit-${stage.sequence}`;
  const m={schemaVersion:1,baseUrl:BASE,managementUrl:MANAGEMENT,runId,concertId:`LOAD-TEST-${runId}`,
    concertTimeId:stage.sequence,totalSeats:20,databaseFingerprint:'a'.repeat(64),redisContainerId:'b'.repeat(64),
    experiment:POLL_EXPERIMENT,round:stage.round,sequence:stage.sequence,expectedActive:4,mode:'on',sessionsPerSecond:5,
    durationSeconds:5,holdDwellMs:500,pollIntervalMs:stage.pollIntervalMs,policyPollIntervalMs:5000,
    maxAdmissionWaitMs:75000,generation:`g-${stage.sequence}`,opensAt:'2020-01-01T00:00:00Z',
    users:Array.from({length:27},(_,i)=>({username:`load-user-${runId}.${String(i+1).padStart(3,'0')}`,accessToken:`aaa.bbb.ccc${i}`}))};
  m.policyFingerprint=createHash('sha256').update(admissionPolicyDescription(m)).digest('hex');return m;
}
function records() {
  // Synthetic contract cases based on a known record shape, not new measurement evidence.
  const old=JSON.parse(readFileSync(new URL('../../results/admission-cap-f3fff2ec-comparison.json',import.meta.url),'utf8')).runs[1];
  return pollComparisonPlan().map(stage=>{
    const r=structuredClone(old),m=manifest(stage);
    Object.assign(r,{runId:m.runId,experiment:POLL_EXPERIMENT,round:m.round,sequence:m.sequence,
      pollIntervalMs:m.pollIntervalMs,policyPollIntervalMs:5000,
      pollHttpCounters:{before:{success:0,other:0},after:{success:r.result.polls,other:0}}});
    r.observation.sampledPollRpsPeak=5;
    for(const phase of ['result','warmup']) Object.assign(r[phase],{experiment:POLL_EXPERIMENT,round:m.round,
      sequence:m.sequence,expectedActive:4,pollIntervalMs:m.pollIntervalMs,policyPollIntervalMs:5000,pollRequestMs:{'p(95)':10}});
    return r;
  });
}
test('polling crossover balances positions while keeping active, arrival and advertised policy fixed',()=>{
  const plan=pollComparisonPlan();assert.equal(plan.length,9);
  for(const position of [0,1,2])assert.deepEqual(plan.filter((_,i)=>i%3===position).map(s=>s.pollIntervalMs).sort((a,b)=>a-b),[1000,2000,5000]);
  for(const stage of plan) {
    const m=validateManifest(manifest(stage));assert.equal(stage.active,4);assert.equal(stage.rate,5);
    assert.equal(advertisedPollInterval(m),5000);
  }
  assert.equal(advertisedPollInterval({pollIntervalMs:5000}),5000);
});
test('poll experiment rejects unknown, unbounded and cross-assigned controls',()=>{
  for(const [key,value] of [['experiment','other'],['expectedActive',8],['round',2],['sequence',2],['pollIntervalMs',1],
    ['policyPollIntervalMs',1000],['sessionsPerSecond',10],['mode','off'],['holdDwellMs',1],['redisContainerId','']]) {
    assert.throws(()=>validateManifest({...manifest(),[key]:value}),key);
  }
  const m=manifest(pollComparisonPlan()[1]);delete m.experiment;assert.throws(()=>validateManifest(m));
});
test('Redis fingerprint describes the same server policy regardless of synthetic client polling',()=>{
  const m=manifest(), other={...manifest(pollComparisonPlan()[1]),concertTimeId:m.concertTimeId,generation:m.generation};
  assert.equal(admissionPolicyDescription(m),admissionPolicyDescription(other));
  assert.match(admissionPolicyDescription(m),/maxActive=4, maxWaiting=30, waitingTtl=PT2M, admissionTtl=PT2M, batchSize=2/);
  assert.throws(()=>admissionPolicyDescription({...other,policyPollIntervalMs:1000}));
});
test('poll HTTP accounting excludes setup 404 and unrelated endpoints, but rejects failed reads',()=>{
  const line=(method,uri,status,n)=>`http_server_requests_seconds_count{method="${method}",uri="${uri}",status="${status}"} ${n}\n`;
  const uri='/main/detail/{concertId}/times/{concertTimeId}/admission';
  const text=line('GET',uri,'200',10)+line('GET',uri,'404',25)+line('POST',uri,'200',25)+line('GET',uri+'-policy','200',1);
  assert.deepEqual(pollHttpCounts(text),{success:10,other:0});assertPollHttpAgreement({success:0,other:0},pollHttpCounts(text),{polls:10});
  assert.throws(()=>assertPollHttpAgreement({success:0,other:0},pollHttpCounts(text),{polls:11}));
  assert.throws(()=>assertPollHttpAgreement({success:0,other:0},pollHttpCounts(text+line('GET',uri,'500',1)),{polls:10}));
  assert.throws(()=>pollHttpCounts(line('GET',uri,'200',-1)));
});
test('poll result and nine-stage summary preserve stage identity, fixed controls and HTTP agreement',()=>{
  const runs=records(),m=manifest();validateResult(runs[0].result,m);
  assert.throws(()=>validateResult({...runs[0].result,pollIntervalMs:1000},m));
  const summaries=summarizePollRuns(runs);
  assert.deepEqual(summaries.map(s=>[s.pollIntervalMs,s.maxActive,s.repeats,s.sessions]),[[5000,4,3,75],[2000,4,3,75],[1000,4,3,75]]);
  for(const change of [r=>r.pop(),r=>r.reverse(),r=>{r[1].runId=r[0].runId},r=>{r[0].maxActive=2},
    r=>{r[0].policyPollIntervalMs=1000},r=>{r[0].pollHttpCounters.after.success++},r=>{r[0].policyFingerprintVerified=false},
    r=>{r[0].result.unexpected=1},r=>{r[0].snapshot.remainingSeats=19}]) {
    const changed=records();change(changed);assert.throws(()=>summarizePollRuns(changed));
  }
});
test('unknown and combined runtime modes fail before resource creation',()=>{
  const script=fileURLToPath(new URL('../../scripts/run-admission-session-comparison.mjs',import.meta.url));
  for(const args of [['--unknown'],['--compare-client-polling','--compare-active-limits']]) {
    const r=spawnSync(process.execPath,[script,'--confirm-disposable-local-resources',...args],{encoding:'utf8',timeout:5000,windowsHide:true,
      env:{...process.env,JAVA_HOME:process.env.JAVA_HOME||'unused-before-validation'}});
    assert.equal(r.status,1);assert.equal(r.stdout,'');
  }
});
