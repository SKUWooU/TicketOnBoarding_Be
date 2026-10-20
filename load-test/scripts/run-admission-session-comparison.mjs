// Explicitly confirmed Windows-only disposable runtime. Never attaches to existing DB/Redis resources.
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createWriteStream } from 'node:fs';
import { readFile, writeFile, mkdtemp, unlink, rmdir, stat } from 'node:fs/promises';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { tmpdir, freemem, totalmem, cpus } from 'node:os';
import { join, resolve } from 'node:path';
import { createServer } from 'node:net';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { measure } from './measure-admission-sessions.mjs';
const exec = promisify(execFile);
if(process.platform!=='win32' || process.argv[2]!=='--confirm-disposable-local-resources' || !process.env.JAVA_HOME)
 throw new Error('Requires Windows, JAVA_HOME (Java 21), and --confirm-disposable-local-resources');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const work = await mkdtemp(join(tmpdir(), 'onticket-admission238-logs-'));
console.log('PRIVATE_DIAGNOSTICS '+work);
const java = join(process.env.JAVA_HOME, 'bin/java.exe');
const jar = root + '/onticket/build/libs/onticket-0.0.1-SNAPSHOT.jar';
const nonce = randomUUID().slice(0,8);
const pause = ms => new Promise(r => setTimeout(r,ms));
const command = async (name,args) => (await exec(name,args,{timeout:30000,maxBuffer:1024*1024,windowsHide:true})).stdout.trim();
const request = async (path, method='GET') => {
 const r = await fetch('http://127.0.0.1:18080'+path,{method,redirect:'error',signal:AbortSignal.timeout(10000)});
 if(!r.ok) throw new Error('Preparation API rejected'); return r.json();
};
async function ensureUnused(port) {
 const socket=createServer(); await new Promise((res,rej)=>{socket.once('error',rej);socket.listen(port,'127.0.0.1',res)});
 await new Promise((res,rej)=>socket.close(e=>e?rej(e):res()));
}
let db; let redis; let backend; let log; let directory; let batch; let batchFile;
let success=false; let label='preflight'; const files=[];
const records=[];
async function stopBackend() {
 if(backend && backend.exitCode===null) { const done=new Promise(r=>backend.once('exit',r)); backend.kill(); await done; }
 backend=null; if(log) { await new Promise(r=>log.end(r)); log=null; } await pause(1000);
}
async function startBackend(policy, name) {
 await stopBackend();
 log=createWriteStream(join(work,`admission238-${nonce}-${name}.log`),{flags:'wx'});
 backend=spawn(java,['-Xmx384m','-XX:MaxMetaspaceSize=192m','-jar',jar,'--spring.profiles.active=local,loadtest',
  '--debug=false','--logging.level.org.springframework=INFO','--logging.level.org.hibernate=WARN','--logging.level.io.lettuce=WARN',
  `--spring.config.additional-location=file:${policy}`],{cwd:root+'/onticket',windowsHide:true,
  env:{...process.env,JWT_SECRET:secret,ONTICKET_DB_HOST:'127.0.0.1',ONTICKET_DB_PORT:'13308',
   ONTICKET_DB_NAME:'onticket_admission238',ONTICKET_DB_USERNAME:'onticket',ONTICKET_DB_PASSWORD:'onticket',
   ONTICKET_HIKARI_MAXIMUM_POOL_SIZE:'4',ONTICKET_LOADTEST_ROWS:'2',ONTICKET_LOADTEST_SEATS_PER_ROW:'10',SERVER_PORT:'18080',MANAGEMENT_SERVER_PORT:'18081'}});
 backend.stdout.pipe(log,{end:false});backend.stderr.pipe(log,{end:false});
 for(let i=0;i<90;i++) {
  if(backend.exitCode!==null) throw new Error('Backend startup failed; private log retained');
  try{if((await (await fetch('http://127.0.0.1:18081/actuator/health',{signal:AbortSignal.timeout(1000)})).json()).status==='UP')return;}catch{}
  await pause(1000);
 }
 throw new Error('Backend startup deadline');
}
const secret=randomBytes(48).toString('base64');
try {
 const version=await command(java,['--version']);
 if(!/^(openjdk|java) 21[. ]/.test(version)) throw new Error('Java 21 required before runtime creation');
 await stat(jar); await command('k6',['version']); await command('docker',['info','--format','{{.ServerVersion}}']);
 for(const port of [18080,18081,13308,17380]) await ensureUnused(port);
 if(freemem()<2*1024**3) throw new Error('Insufficient host memory');
 const dbName=`ticket-admission238-db-${nonce}`;
 db=await command('docker',['run','--rm','-d','--name',dbName,'--label','onticket.scope=admission238','--memory','512m',
  '-p','127.0.0.1:13308:3306','-e','MARIADB_DATABASE=onticket_admission238','-e','MARIADB_USER=onticket',
  '-e','MARIADB_PASSWORD=onticket','-e','MARIADB_ROOT_PASSWORD=onticket-root','mariadb:10.11.8']);
 redis=await command('docker',['run','--rm','-d','--name',`ticket-admission238-redis-${nonce}`,'--label','onticket.scope=admission238',
  '--memory','128m','-p','127.0.0.1:17380:6379','redis:7.4.7-alpine','redis-server','--maxmemory','64mb','--maxmemory-policy','noeviction']);
 if(!/^[a-f0-9]{64}$/.test(db)||!/^[a-f0-9]{64}$/.test(redis)) throw new Error('Invalid dedicated container identity');
 for(let i=0;i<60;i++){try{await command('docker',['exec',db,'mariadb','-uonticket','-ponticket','-e','SELECT 1']);break;}catch{if(i===59)throw new Error('DB readiness');await pause(1000);}}
 directory=await mkdtemp(join(tmpdir(),'onticket-admission238-'));
 const disabled=join(directory,'disabled.properties');files.push(disabled);
 await writeFile(disabled,'onticket.admission.enabled=false\n',{mode:0o600});
 await startBackend(disabled,'seed');
 const fingerprint=await command('docker',['exec',db,'mariadb','-uonticket','-ponticket','-N','-B','onticket_admission238',
  '-e',"SELECT SHA2(CONCAT(@@hostname, ':', DATABASE()), 256)"]);
 if((await request('/loadtest/database-identity')).fingerprint!==fingerprint) throw new Error('Seed DB mismatch');
 const plan=[[1,'off'],[1,'on'],[2,'on'],[2,'off'],[5,'off'],[5,'on']];
 const prepared=[];
 for(let i=0;i<plan.length;i++) {
  const [rate,mode]=plan[i]; const runId=`adm238-${nonce}-${i+1}-${mode}`;
  const fixture=await request('/loadtest/runs?runId='+runId,'POST');
  const users=await request('/loadtest/tokens?runId='+runId+'&count='+(2+rate*5));
  const generation=`g238-${nonce}-${i+1}`; const policy=join(directory,`policy-${i}.properties`); files.push(policy);
  await writeFile(policy,`onticket.admission.enabled=${mode==='on'}\nonticket.admission.concert-id=${fixture.concertId}\nonticket.admission.concert-time-id=${fixture.concertTimeId}\nonticket.admission.generation=${generation}\nonticket.admission.opens-at=2020-01-01T00:00:00Z\nonticket.admission.max-active=2\nonticket.admission.max-waiting=30\nonticket.admission.waiting-ttl=2m\nonticket.admission.admission-ttl=2m\nonticket.admission.batch-size=2\nonticket.admission.redis-port=17380\nonticket.admission.worker.delay-ms=1000\n`,{mode:0o600});
  // Bootstrap CLI intentionally only accepts policy keys, so omit worker-specific configuration.
  const bootstrap=join(directory,`bootstrap-${i}.properties`);files.push(bootstrap);
  await writeFile(bootstrap,(await readFile(policy,'utf8')).replace('onticket.admission.worker.delay-ms=1000\n',''),{mode:0o600});
  const manifest=join(directory,`manifest-${i}.json`);files.push(manifest);
  await writeFile(manifest,JSON.stringify({schemaVersion:1,baseUrl:'http://127.0.0.1:18080',managementUrl:'http://127.0.0.1:18081',
   runId,concertId:fixture.concertId,concertTimeId:fixture.concertTimeId,totalSeats:20,databaseFingerprint:fingerprint,databaseContainerId:db,
   mode,sessionsPerSecond:rate,durationSeconds:5,holdDwellMs:500,pollIntervalMs:5000,maxAdmissionWaitMs:75000,expectedActive:2,
   generation,opensAt:'2020-01-01T00:00:00Z',users}),{mode:0o600});
  prepared.push({mode,rate,runId,generation,policy,bootstrap,manifest});
 }
 await stopBackend(); console.log('PREPARED six isolated 20-seat fixtures; no external APIs');
 await writeFile(join(work,`admission238-${nonce}-private-state.json`),JSON.stringify({db,redis,directory,files,prepared},null,2));
 for(const p of prepared) {
  label=p.runId;
  if(p.mode==='on') await command(java,['-Xmx128m','-Dloader.main=com.onticket.admission.AdmissionBootstrapCommand','-cp',jar,
    'org.springframework.boot.loader.launch.PropertiesLauncher',`--policy-file=${p.bootstrap}`,`--confirm-new-generation=${p.generation}`]);
  await startBackend(p.policy,p.runId);
  console.log(`MEASURING ${p.runId} ${p.rate} sessions/s ${p.mode}`);
  records.push(await measure(p.manifest));
  if(p.mode==='on') {
   for(const suffix of ['waiting','active']) {
    const n=await command('docker',['exec',redis,'redis-cli','--raw','ZCARD',`admission:{${records.at(-1).snapshot.concertTimeId??JSON.parse(await readFile(p.manifest,'utf8')).concertTimeId}:${p.generation}}:${suffix}`]);
    if(n!=='0') throw new Error('Queue cleanup invariant failed');
   }
  }
  await stopBackend();
 }
 batch={schemaVersion:1,status:'MEASUREMENTS_COMPLETE_CLEANUP_PENDING',runtimeCleanup:'PENDING',sourceBaseCommit:await command('git',['-C',root,'-c',`safe.directory=${root.replaceAll('\\','/')}`,'rev-parse','HEAD']),
  jarSha256:createHash('sha256').update(await readFile(jar)).digest('hex'),createdAt:new Date().toISOString(),
  environment:{hostMemoryBytes:totalmem(),logicalProcessors:cpus().length,cpuModel:cpus()[0].model,
   javaHeapMiB:384,metaspaceMiB:192,hikariMaximum:4,mariaDbContainerMiB:512,redisContainerMiB:128,redisMaxmemoryMiB:64,k6Version:await command('k6',['version'])},
  notes:['One short run per mode/rate, not a statistically repeated benchmark or capacity limit.','Each stage uses a fresh 20-seat fixture; all six fixtures prepared in the same disposable DB.',
   'Hold/release only. No Checkout, real PG, KOPIS or browser traffic.','Arrival rate is sessions/s, not HTTP RPS. Poll interval 5s; active tickets are held through release/leave.',
   'DB counter windows are sampled, include drain/collector traffic and are not query-specific.',
   'Explicit --debug=false and package logger overrides are requested; inspect private runtime logs for effective logging before interpreting latency.'],
  runs:records};
 batchFile=root+`/load-test/results/admission238-${nonce}-comparison.json`;
 await writeFile(batchFile,JSON.stringify(batch,null,2));
 success=true;
}catch(e){console.error('BATCH_STOPPED stage='+label+' reason='+e.message);process.exitCode=1;}
finally {
 await stopBackend();
 if(success) {
  try {
  // Exact freshly created IDs only. --rm also removes their own anonymous DB volume, never existing user volumes.
  for(const id of [redis,db]) if(id) await command('docker',['stop',id]);
  const safe=directory && resolve(directory).startsWith(resolve(tmpdir())+'\\onticket-admission238-');
  if(!safe) throw new Error('Temporary cleanup boundary rejected');
  for(const file of files) {if(!resolve(file).startsWith(resolve(directory)+'\\'))throw new Error('Temp file boundary');await unlink(file);}
  await rmdir(directory);
  } catch {
   batch.status='CLEANUP_FAILED'; batch.runtimeCleanup='INCOMPLETE';
   await writeFile(batchFile,JSON.stringify(batch,null,2));
   throw new Error('Owned runtime cleanup incomplete; inspect private diagnostics');
  }
  batch.status='EXPLORATION_COMPLETE'; batch.runtimeCleanup='COMPLETE';
  await writeFile(batchFile,JSON.stringify(batch,null,2));
  console.log(`BATCH_COMPLETE admission238-${nonce}-comparison.json`);
  console.log('CLEANED owned runtime and temporary credentials');
 }else console.log('RETAINED isolated containers and private preparation state for diagnosis; no automatic retry/reset; private logs: '+work);
}
