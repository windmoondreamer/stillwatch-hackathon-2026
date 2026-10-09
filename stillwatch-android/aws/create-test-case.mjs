import fs from 'node:fs';
import {evaluate} from './domain.mjs';
const path=new URL('../.private/current-zone.json',import.meta.url);const zone=JSON.parse(fs.readFileSync(path));
if(zone.safetyCase&&!zone.safetyCase.resolvedAt)throw new Error('Finish the active test case first.');
const now=Date.now();Object.assign(zone,{registeredCount:1,phoneStatus:'CONNECTED',phoneSeenAt:new Date(now).toISOString(),enteredAt:new Date(now-60000).toISOString(),
 stillSince:new Date(now-6000).toISOString(),lastValidAt:new Date(now).toISOString(),lastReceivedAt:new Date(now).toISOString(),lastMotionAt:new Date(now-6000).toISOString(),checkStartedAt:null,t1:5,t2:Number(process.argv[2]||45),telemetrySource:'SYNTHETIC TEST INPUT'});
evaluate(zone,now);zone.safetyCase.testInput='synthetic';zone.alerts[0].events.unshift({label:'연결 시험 · 가상 무응답 입력',at:new Date(now).toISOString()});zone.version++;
fs.writeFileSync(new URL('../.private/test-zone.json',import.meta.url),JSON.stringify(zone));
console.log('Controlled synthetic incident:',zone.safetyCase.id);
