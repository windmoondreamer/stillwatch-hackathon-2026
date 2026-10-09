import {randomUUID} from 'node:crypto';
import {templateReport} from './report.mjs';
import {DomainError} from './domain.mjs';

export function startSafetyCase(zone, alert, time) {
  if (zone.safetyCase && !zone.safetyCase.resolvedAt) return;
  const item = { id:alert.id, startedAt:time, deadline:time + zone.t2*1000, managerDeadline:null,
    room:zone.name, task:zone.task||'단독 작업', address:zone.address||'', floor:zone.floor||zone.area,
    entrance:zone.entrance||'', callback:zone.callback||'', workerName:zone.workerName||'',
    headcount:zone.registeredCount, entryAt:Date.parse(zone.enteredAt)||null, lastMotion:Date.parse(zone.lastMotionAt)||null,
    stillDurationSeconds:zone.t1, sensorConnected:true, managerEnabled:zone.managerEnabled!==false,
    managerFeedback:null, questionCount:0, response:null, transcripts:[], phase:'checking',
    contact:{status:'standby',idempotencyKey:randomUUID(),recipient:zone.testRecipient||'+821000000000'} };
  item.report=templateReport(item); zone.safetyCase=item;
  alert.caseId=item.id;
}
export function advanceSafetyCase(zone,time) {
  const item=zone.safetyCase;if(!item||item.resolvedAt)return;
  if(item.phase==='checking'&&time>=item.deadline){
    item.phase='manager';item.escalationReason='timeout';item.managerDeadline=item.deadline+60000;
    item.contact.status=item.managerEnabled?'awaiting_manager':'queued';
    if(!item.managerEnabled)item.contact.reason='no_manager';
  }
  if(item.contact.status==='awaiting_manager'&&time>=item.managerDeadline){item.contact.status='queued';item.contact.reason='manager_timeout';}
  if(item.contact.status==='sending'&&time-item.contact.attemptedAt>=30000){item.contact.status='unknown';item.contact.error='발송 결과 미확인 · 자동 재전송 없음';}
}
export function resolveSafetyCase(zone,time,who,note) {
  const item=zone.safetyCase;if(!item||item.resolvedAt)return;
  item.resolvedAt=time;item.resolvedBy=who;item.resolutionNote=note;
  if(['standby','awaiting_manager','queued'].includes(item.contact.status))item.contact.status='cancelled';
}
export function voiceCommand(zone,identity,input,time) {
  const item=zone.safetyCase;if(!item||item.resolvedAt)return null;
  if(identity.role==='MANAGER'){
    if(item.phase==='manager'&&!item.managerNotificationReceivedAt)return {id:item.id+':manager',incidentId:item.id,alertId:zone.alerts.find(a=>a.caseId===item.id&&a.status==='RESCUE')?.id||item.id,kind:'manager_alert',recordSeconds:0,deadline:Math.max(item.managerDeadline||0,time+10000),
      prompt:`${zone.name} 작업자 확인이 필요합니다. 앱에서 정상 확인 또는 구조 요청으로 답해주세요. ${item.contact.status==='awaiting_manager'?'관리자 응답 마감은 작업자 확인 마감 이후 60초입니다.':'자동 연락 처리 상태도 확인해주세요.'}`};
    return null;
  }
  if(identity.sub!==zone.workerSub||input.phoneId!==zone.assignedPhoneId||item.phase!=='checking')return null;
  const remaining=item.deadline-time;
  if(remaining<=8000&&!item.warningSpoken)return {id:item.id+':warning',incidentId:item.id,kind:'warning',deadline:item.deadline,recordSeconds:0,
    prompt:item.managerEnabled?'응답이 없으면 관리자에게 알리고, 1분 안에 확인되지 않으면 등록된 연락처로 도움을 요청하겠습니다.':'응답이 없으면 등록된 연락처로 도움을 요청하겠습니다.'};
  if(item.pendingQuestion&&time<item.deadline)return item.pendingQuestion;
  if(remaining>12000&&(item.questionCount||0)<3&&time-(item.lastQuestionAt||0)>=10000){
    item.pendingQuestion={id:item.id+':question:'+((item.questionCount||0)+1),incidentId:item.id,kind:'check',deadline:item.deadline,recordSeconds:5,
      prompt:item.questionCount?'응답이 확인되지 않았습니다. 도움이 필요하신지 말씀해주세요. 괜찮으시면 확인 버튼을 눌러주세요.':'괜찮으신가요? 문제가 있거나 도움이 필요하신가요?'};
    return item.pendingQuestion;
  }
  return null;
}
export function voiceEvent(zone,identity,input,time) {
  const item=zone.safetyCase;if(!item||item.id!==input.incidentId||item.resolvedAt)return;
  if(identity.role==='WORKER'&&(identity.sub!==zone.workerSub||input.phoneId!==zone.assignedPhoneId))throw new DomainError(403,'지정된 작업자 단말이 아닙니다.');
  if(input.kind==='failed'){item.voiceError=String(input.error||'음성 확인 오류').slice(0,200);return;}
  if(identity.role==='MANAGER'&&input.kind==='received'&&input.commandId===item.id+':manager'){item.managerNotificationReceivedAt=time;return;}
  if(input.kind!=='spoken')return;
  if(identity.role==='MANAGER'){if(input.commandId===item.id+':manager')item.managerSpoken=time;return;}
  if(input.commandId===item.id+':warning'){item.warningSpoken=time;return;}
  if(input.commandId===item.pendingQuestion?.id){
    item.questionCount++;item.lastQuestionAt=time;item.transcripts.push({role:'assistant',text:item.pendingQuestion.prompt,at:time});item.pendingQuestion=null;
  }
}
export function reportSignature(item){return JSON.stringify([item.response,item.questionCount,item.phase,item.managerFeedback,item.contact.reason]);}
export function contactText(item){
  const facts=`확인된 위치: ${item.address||'미입력'}, ${item.floor||'층 미입력'}, ${item.room}. 진입 방법: ${item.entrance||'미입력'}. 회신: ${item.callback||'미입력'}. 마지막 움직임: ${item.lastMotion?new Date(item.lastMotion).toLocaleString('ko-KR',{timeZone:'Asia/Seoul'}):'미확인'}.`;
  const latest=item.contact.reason==='manager_timeout'?'관리자에게 앱으로 확인을 요청했으나 60초 내 정상 확인이 기록되지 않았습니다.':item.escalationReason==='help_requested'?`도움 요청: ${item.response?.quote||item.managerFeedback?.note||'도움 요청 버튼'}`:'작업자 정상 확인이 기록되지 않았습니다.';
  return `[StillWatch 시험 · 실제 119 신고 아님]\n${item.report?.source==='ai'&&item.completedDraftSignature===reportSignature(item)?item.report.report_119_ko:templateReport(item).report_119_ko}\n${facts}\n${latest}\n의식·호흡 상태는 미확인입니다.`;
}
