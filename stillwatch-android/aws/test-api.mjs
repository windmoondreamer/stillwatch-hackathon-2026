import fs from 'node:fs';
import {generateReport} from './report.mjs';
const secret=JSON.parse(fs.readFileSync(new URL('../.private/openai-secret.json',import.meta.url)));
const now=Date.now();const fixture={room:'[시험] 연결 검증 구역',task:'연결 시험',headcount:1,entryAt:now-600000,lastMotion:now-300000,startedAt:now-30000,stillDurationSeconds:300,
 address:'[시험] 실제 주소 미등록',floor:'시험 구역',entrance:'시험 출입구',callback:'01000000000',workerName:'시험 작업자',sensorConnected:true,managerEnabled:true,questionCount:1};
const report=await generateReport(fixture,{key:secret.apiKey});
console.log(JSON.stringify({source:report.source,status:report.status,summary:report.summary_ko}));
if(report.source!=='ai')process.exitCode=1;
