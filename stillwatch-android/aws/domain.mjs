import { randomUUID } from 'node:crypto';
import {startSafetyCase,advanceSafetyCase,resolveSafetyCase} from './flow.mjs';
export class DomainError extends Error { constructor(status,message) { super(message);this.status=status; } }
const iso = time => new Date(time).toISOString();
export function initialZone(id='room-01',deviceId='stillwatch-room-01') {
  return {id,deviceId,name:'공학관 B1 전기실',area:'지하 1층 · 설비 점검 구역',state:'UNKNOWN',registeredCount:0,workerSub:null,
    enteredAt:null,lastMotionAt:null,lastReceivedAt:null,lastValidAt:null,stillSince:null,checkStartedAt:null,
    motionScore:null,samples:[],t1:60,t2:60,sensorTimeout:30,exitGuide:'',guideReviewedAt:null,
    targetWifiSsid:'',targetWifiBssid:'',assignedWorkerSub:null,assignedPhoneId:null,workerName:null,
    phoneStatus:'UNKNOWN',phoneSeenAt:null,floorplan:null,alerts:[],deliveries:[],version:0,
    address:'',floor:'',entrance:'',callback:'',task:'단독 작업',managerEnabled:true,testRecipient:'+821000000000',safetyCase:null};
}
function event(zone,status,time,label,recipient) {
  const previous=zone.alerts.find(a=>!a.closedAt && a.status===status);
  if(previous) return previous;
  const alert={id:randomUUID(),status,occurredAt:iso(time),events:[{label,at:iso(time)}],acknowledgedAt:null,closedAt:null,
    workerSub:zone.workerSub,enteredAt:zone.enteredAt,lastMotionAt:zone.lastMotionAt,registeredCount:zone.registeredCount};
  zone.alerts.unshift(alert);
  if(status==='STILL_CHECK')startSafetyCase(zone,alert,time);
  if(status==='RESCUE'&&zone.safetyCase)alert.caseId=zone.safetyCase.id;
  if(zone.alerts.length>100) { // retain open cases even if the recent closed history is full
    const open=zone.alerts.filter(a=>!a.closedAt),closed=zone.alerts.filter(a=>a.closedAt);
    zone.alerts=[...open,...closed.slice(0,Math.max(0,100-open.length))];
  }
  zone.deliveries.push({id:alert.id,alertId:alert.id,status,recipient,workerSub:zone.workerSub,createdAt:iso(time),attempts:0,state:'pending'});
  return alert;
}
function openRescue(zone) { return zone.alerts.some(a=>a.status==='RESCUE'&&!a.closedAt); }
export function evaluate(zone,time) {
  advanceSafetyCase(zone,time);
  if(zone.phoneSeenAt && time-Date.parse(zone.phoneSeenAt)>45000) { zone.phoneStatus='UNKNOWN';zone.registeredCount=null; }
  if(zone.checkStartedAt && time-Date.parse(zone.checkStartedAt)>=zone.t2*1000 && !openRescue(zone)) {
    const check=zone.alerts.find(a=>a.status==='STILL_CHECK'&&!a.closedAt);
    if(check) { check.closedAt=iso(time);check.reason='본인 응답 기한 종료';check.events.push({label:'본인 응답 기한 종료',at:iso(time)}); }
    event(zone,'RESCUE',time,'본인 응답 없음 · 현장 확인 요청','MANAGER');
  }
  const validAt=Date.parse(zone.lastValidAt);
  const lost=!Number.isFinite(validAt)||time-validAt>zone.sensorTimeout*1000;
  if(lost) {
    if(zone.lastReceivedAt || zone.registeredCount>0) event(zone,'SENSOR_LOST',time,'센서 정보 수신 중단 또는 측정 불가','MANAGER');
    zone.state='SENSOR_LOST';return zone;
  }
  if(openRescue(zone)) { zone.state='RESCUE';return zone; }
  if(zone.registeredCount>0 && zone.stillSince && !zone.checkStartedAt && time-Date.parse(zone.stillSince)>=zone.t1*1000) {
    zone.checkStartedAt=iso(time);event(zone,'STILL_CHECK',time,'움직임 미감지 · 본인 확인 요청','WORKER');
  }
  zone.state=zone.checkStartedAt?'STILL_CHECK':zone.state==='RESOLVED'?'RESOLVED':'ACTIVE';
  return zone;
}
export function applyTelemetry(zone,input,time) {
  if(input.deviceId!==zone.deviceId) throw new DomainError(403,'등록된 센서가 아닙니다.');
  const captured=Date.parse(input.capturedAt);
  if(typeof input.capturedAt!=='string'||!/(Z|[+-]\d{2}:\d{2})$/.test(input.capturedAt)||!Number.isFinite(captured)||captured>time+5000||time-captured>30000) throw new DomainError(422,'오래되었거나 유효하지 않은 측정 시각입니다.');
  if(!Number.isInteger(input.seq)||input.seq<0||typeof input.bootId!=='string'||input.bootId.length<1||input.bootId.length>80) throw new DomainError(422,'bootId와 seq가 필요합니다.');
  // Captured time ordering also prevents an older boot from replacing a new boot.
  if(zone.sampleAt && captured<=Date.parse(zone.sampleAt)) return zone;
  if(zone.bootId===input.bootId && input.seq<=(zone.seq??-1)) return zone;
  if(typeof input.valid!=='boolean'||typeof input.motion!=='boolean'||!Number.isFinite(input.motionScore)||input.motionScore<0) throw new DomainError(422,'측정 값의 형식이 올바르지 않습니다.');
  zone.lastReceivedAt=iso(time);zone.sampleAt=iso(captured);zone.bootId=input.bootId;zone.seq=input.seq;
  if(!input.valid) { zone.lastValidAt=null;zone.motionScore=null;return evaluate(zone,time); }
  zone.lastValidAt=iso(time);zone.motionScore=input.motionScore;zone.samples=[...zone.samples.slice(-59),input.motionScore];
  if(input.motion) {
    zone.lastMotionAt=iso(captured);zone.stillSince=null;
    // Once personal confirmation is requested, new movement does not impersonate a response.
  } else if(zone.registeredCount>0 && !zone.stillSince) zone.stillSince=iso(time);
  // Reconnection records sensor recovery only; it cannot resolve a rescue case.
  for(const alert of zone.alerts.filter(a=>a.status==='SENSOR_LOST'&&!a.closedAt)) {
    alert.closedAt=iso(time);alert.reason='센서 정보 수신 복구';alert.events.push({label:'센서 정보 수신 복구',at:iso(time)});
  }
  return evaluate(zone,time);
}
export function act(zone,identity,action,body,time) {
  if(!['MANAGER','WORKER'].includes(identity.role)) throw new DomainError(403,'현장 접근 권한이 없습니다.');
  const workerActions=['respond','help'];
  if(workerActions.includes(action) && identity.role!=='WORKER') throw new DomainError(403,'작업자 본인만 수행할 수 있습니다.');
  if(action==='help') {
    if(zone.workerSub!==identity.sub||!zone.safetyCase||zone.safetyCase.resolvedAt)throw new DomainError(409,'진행 중인 본인 확인 사건이 없습니다.');
    const item=zone.safetyCase;if(!['standby','awaiting_manager','queued'].includes(item.contact.status))throw new DomainError(409,'연락 요청이 이미 처리되었습니다.');
    item.escalationReason='help_requested';item.response={category:'help_requested',quote:String(body.quote||'도움 요청 버튼').slice(0,1000)};
    item.phase='manager';item.contact.status='queued';item.contact.reason='help_requested';item.managerDeadline=time;
    const check=zone.alerts.find(a=>a.status==='STILL_CHECK'&&!a.closedAt);if(check)check.closedAt=iso(time);
    event(zone,'RESCUE',time,'작업자 도움 요청','MANAGER');zone.state='RESCUE';return zone;
  }
  if(action==='respond') {
    if(zone.workerSub!==identity.sub) throw new DomainError(403,'본인의 작업 등록이 아닙니다.');
    if(!zone.checkStartedAt || time-Date.parse(zone.checkStartedAt)>=zone.t2*1000 || openRescue(zone)) throw new DomainError(409,'현재 본인 확인 요청에 응답할 수 없습니다. 현장 확인을 이어가 주세요.');
    const alert=zone.alerts.find(a=>a.status==='STILL_CHECK'&&!a.closedAt);
    const method=body.confirmedBy==='voice'?'작업자 본인 음성 응답':'작업자 본인 정상 확인 버튼';
    if(alert) { alert.status='RESOLVED';alert.closedAt=iso(time);alert.reason=method;alert.events.push({label:method,at:iso(time)}); }
    resolveSafetyCase(zone,time,identity.sub,method);
    zone.checkStartedAt=null;zone.stillSince=iso(time);zone.state='RESOLVED';return evaluate(zone,time);
  }
  if(identity.role!=='MANAGER') throw new DomainError(403,'관리자 권한이 필요합니다.');
  if(action==='settings') {
    if(zone.safetyCase&&!zone.safetyCase.resolvedAt)throw new DomainError(409,'진행 중인 확인을 마친 뒤 현장 설정을 변경해주세요.');
    for(const key of ['t1','t2']) if(!Number.isInteger(body[key])||body[key]<5||body[key]>3600) throw new DomainError(422,'확인 시간은 5~3600초 정수로 입력해 주세요.');
    if(typeof body.name!=='string'||!body.name.trim()||body.name.length>100||typeof body.area!=='string'||body.area.length>150||typeof body.exitGuide!=='string'||body.exitGuide.length>3000) throw new DomainError(422,'현장 설정 값이 올바르지 않습니다.');
    if(typeof body.targetWifiSsid!=='string'||body.targetWifiSsid.length>32||typeof body.targetWifiBssid!=='string'||(body.targetWifiBssid&&!/^([0-9a-f]{2}:){5}[0-9a-f]{2}$/i.test(body.targetWifiBssid)))throw new DomainError(422,'현장 Wi-Fi 설정을 확인해 주세요.');
    for(const field of ['address','floor','entrance','callback','task'])if(body[field]!==undefined&&(typeof body[field]!=='string'||body[field].length>200))throw new DomainError(422,'현장 주소·진입 정보 길이를 확인해주세요.');
    if(body.testRecipient!==undefined&&!/^(?:01000000000|\+821000000000)$/.test(body.testRecipient))throw new DomainError(422,'현재 빌드는 승인한 시험 번호만 사용합니다.');
    Object.assign(zone,{name:body.name.trim(),area:body.area,t1:body.t1,t2:body.t2,exitGuide:body.exitGuide,guideReviewedAt:iso(time),targetWifiSsid:body.targetWifiSsid,targetWifiBssid:body.targetWifiBssid.toLowerCase()});
    for(const field of ['address','floor','entrance','callback','task'])if(body[field]!==undefined)zone[field]=body[field].trim();
    if(body.managerEnabled!==undefined)zone.managerEnabled=body.managerEnabled===true;
    zone.testRecipient='+821000000000';return zone;
  }
  const alert=zone.alerts.find(a=>a.id===body.alertId);
  if(!alert) throw new DomainError(404,'알림을 찾을 수 없습니다.');
  if(alert.closedAt) throw new DomainError(409,'이미 종료된 알림입니다.');
  if(action==='acknowledge') {
    alert.acknowledgedAt??=iso(time);alert.acknowledgedBy=identity.sub;
    if(zone.safetyCase&&alert.caseId===zone.safetyCase.id&&!zone.safetyCase.resolvedAt)zone.safetyCase.managerFeedback={action:'checking',note:String(body.reason||'앱 알림 확인').slice(0,500),at:time};return zone;
  }
  if(action==='request-help') {
    const item=zone.safetyCase;if(!item||item.resolvedAt||alert.caseId!==item.id)throw new DomainError(409,'해당 알림의 확인 사건이 진행 중이 아닙니다.');
    if(!['standby','awaiting_manager','queued'].includes(item.contact.status))throw new DomainError(409,'연락 요청이 이미 처리되었습니다.');
    item.managerFeedback={action:'help',note:String(body.reason||'관리자 구조 요청').slice(0,500),at:time};item.contact.status='queued';item.contact.reason='manager_help';item.escalationReason='help_requested';return zone;
  }
  if(action==='close') {
    if(typeof body.reason!=='string'||body.reason.trim().length<2||body.reason.length>500) throw new DomainError(422,'현장 확인 결과를 기록해 주세요.');
    alert.closedAt=iso(time);alert.closedBy=identity.sub;alert.reason=body.reason.trim();alert.events.push({label:'관리자 현장 확인 종료',at:iso(time)});
    if(['RESCUE','STILL_CHECK'].includes(alert.status)&&alert.caseId===zone.safetyCase?.id) {
      resolveSafetyCase(zone,time,identity.sub,body.reason.trim());zone.checkStartedAt=null;zone.stillSince=zone.registeredCount?iso(time):null;
      for(const related of zone.alerts.filter(a=>a.caseId===zone.safetyCase?.id&&!a.closedAt)){related.closedAt=iso(time);related.reason='관리자 정상 확인';}
    }
    return evaluate(zone,time);
  }
  throw new DomainError(404,'지원하지 않는 동작입니다.');
}
export function applyPresence(zone,identity,input,time) {
  if(identity.role!=='WORKER'||identity.sub!==zone.assignedWorkerSub||input.phoneId!==zone.assignedPhoneId)throw new DomainError(403,'이 구역에 지정된 작업자 휴대폰이 아닙니다.');
  if(!['CONNECTED','DISCONNECTED','UNKNOWN'].includes(input.connection))throw new DomainError(422,'휴대폰 연결 상태가 올바르지 않습니다.');
  zone.phoneSeenAt=iso(time);zone.workerSub=zone.assignedWorkerSub;
  const known=input.connection!=='UNKNOWN' && (input.connection==='DISCONNECTED'||typeof input.ssid==='string');
  const matches=input.connection==='CONNECTED' && input.ssid===zone.targetWifiSsid && !!zone.targetWifiSsid && (!zone.targetWifiBssid || input.bssid?.toLowerCase()===zone.targetWifiBssid);
  if(!known){zone.phoneStatus='UNKNOWN';zone.registeredCount=null;}
  else if(matches){if(zone.registeredCount!==1){zone.enteredAt=iso(time);zone.stillSince=null;}zone.phoneStatus='CONNECTED';zone.registeredCount=1;}
  else{zone.phoneStatus='DISCONNECTED';zone.registeredCount=0;zone.stillSince=null;}
  // A disconnected phone does not prove the person left; pending checks/rescues remain open.
  return evaluate(zone,time);
}
export function identityFromClaims(claims) {
  if(!claims?.sub) throw new DomainError(401,'로그인이 필요합니다.');
  let groups=claims['cognito:groups'];
  if(typeof groups==='string') { try { groups=JSON.parse(groups); } catch { groups=groups.replace(/^\[|\]$/g,'').split(/[\s,]+/).map(g=>g.replace(/^['"]|['"]$/g,'')); } }
  if(!Array.isArray(groups)) groups=[];
  const role=groups.includes('MANAGER')?'MANAGER':groups.includes('WORKER')?'WORKER':null;
  if(!role) throw new DomainError(403,'관리자가 부여한 현장 역할이 필요합니다.');
  return {sub:claims.sub,role};
}
export function publicZone(zone) {
  const {deliveries,pk,sk,version,bootId,seq,...publicData}=zone;
  return publicData;
}
