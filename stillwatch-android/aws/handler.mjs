import {createHash,createSign,randomUUID} from 'node:crypto';
import {DynamoDBClient} from '@aws-sdk/client-dynamodb';
import {DynamoDBDocumentClient,GetCommand,PutCommand,QueryCommand,DeleteCommand} from '@aws-sdk/lib-dynamodb';
import {DomainError,initialZone,applyTelemetry,applyPresence,evaluate,act,identityFromClaims,publicZone} from './domain.mjs';
import {signObjectUrl} from './s3-sign.mjs';
import {SFNClient,StartExecutionCommand} from '@aws-sdk/client-sfn';
import {LambdaClient,InvokeCommand} from '@aws-sdk/client-lambda';
import {SNSClient,PublishCommand} from '@aws-sdk/client-sns';
import {SecretsManagerClient,GetSecretValueCommand} from '@aws-sdk/client-secrets-manager';
import {voiceCommand,voiceEvent,reportSignature,contactText} from './flow.mjs';
import {generateReport,templateReport} from './report.mjs';
import {interpretAudio} from './audio.mjs';
import {applyNative} from './native.mjs';
const db=DynamoDBDocumentClient.from(new DynamoDBClient({region:process.env.AWS_REGION,maxAttempts:3}),{marshallOptions:{removeUndefinedValues:true}});
const table=process.env.TABLE_NAME,zoneId=process.env.ZONE_ID,deviceId=process.env.DEVICE_ID,bucket=process.env.FLOORPLAN_BUCKET;
const key={pk:`ZONE#${zoneId}`,sk:'STATE'};
const hash=token=>createHash('sha256').update(token).digest('hex');
let cachedToken=null;
const steps=new SFNClient({maxAttempts:2}),functions=new LambdaClient({maxAttempts:2});
const messages=new SNSClient({maxAttempts:1}),secrets=new SecretsManagerClient({maxAttempts:2});
let openAiSecret;
async function openAiKey(){
  if(openAiSecret?.until>Date.now())return openAiSecret.value;
  if(!process.env.OPENAI_SECRET_ARN)return '';
  const result=await secrets.send(new GetSecretValueCommand({SecretId:process.env.OPENAI_SECRET_ARN}));
  const data=JSON.parse(result.SecretString);openAiSecret={value:data.apiKey,until:Date.now()+300000};return data.apiKey;
}
async function readZone(){const data=await db.send(new GetCommand({TableName:table,Key:key,ConsistentRead:true}));return data.Item??{...initialZone(zoneId,deviceId),...key};}
async function updateZone(change){
  for(let attempt=0;attempt<5;attempt++){
    const zone=await readZone(),version=zone.version;
    await change(zone);zone.version=version+1;
    try{await db.send(new PutCommand({TableName:table,Item:zone,ConditionExpression:'attribute_not_exists(pk) OR #v = :old',ExpressionAttributeNames:{'#v':'version'},ExpressionAttributeValues:{':old':version}}));return zone;}
    catch(error){if(error.name!=='ConditionalCheckFailedException')throw error;}
  }
  throw new DomainError(409,'동시에 상태가 갱신되었습니다. 다시 시도해 주세요.');
}
async function queryDevices(role){
  const result=[];let start;
  do{const page=await db.send(new QueryCommand({TableName:table,KeyConditionExpression:'pk = :pk',ExpressionAttributeValues:{':pk':`DEVICE#${role}`},ExclusiveStartKey:start}));result.push(...(page.Items??[]));start=page.LastEvaluatedKey;}while(start);
  return result;
}
async function firebaseAccess(){
  if(cachedToken?.expires>Date.now()+60000)return cachedToken.value;
  const account=JSON.parse(process.env.FCM_SERVICE_ACCOUNT||'null');
  if(!account?.private_key||!account?.client_email||!account?.project_id)throw new Error('push_not_configured');
  const now=Math.floor(Date.now()/1000),encode=value=>Buffer.from(JSON.stringify(value)).toString('base64url');
  const content=encode({alg:'RS256',typ:'JWT'})+'.'+encode({iss:account.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600});
  const signature=createSign('RSA-SHA256').update(content).sign(account.private_key,'base64url');
  const response=await fetch('https://oauth2.googleapis.com/token',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:content+'.'+signature}),signal:AbortSignal.timeout(10000)});
  if(!response.ok)throw new Error('push_auth_failed');
  const data=await response.json();cachedToken={value:data.access_token,expires:Date.now()+data.expires_in*1000};return cachedToken.value;
}
function pushText(zone,delivery){
  const title=delivery.status==='STILL_CHECK'?'괜찮으신가요?':delivery.status==='SENSOR_LOST'?'감시 불가':'현장 확인 요청';
  const body=delivery.status==='STILL_CHECK'?`${zone.name}. 움직임이 감지되지 않습니다. 본인 응답을 부탁드립니다.`:
    delivery.status==='SENSOR_LOST'?`${zone.name}. 센서 정보 수신이 중단돼 작업자 상태를 확인할 수 없습니다.`:`${zone.name}. 작업자의 본인 응답이 없어 현장 확인이 필요합니다.`;
  return {title,body};
}
async function deliver(zone){
  for(const delivery of zone.deliveries.filter(d=>['pending','waiting','failed'].includes(d.state)&&d.attempts<5)){
    const alert=zone.alerts.find(a=>a.id===delivery.alertId);
    if(!alert||alert.closedAt){await markDelivery(delivery.id,'cancelled',delivery.attempts);continue;}
    if(!process.env.FCM_SERVICE_ACCOUNT){if(delivery.state!=='waiting'||delivery.error!=='push_not_configured')await markDelivery(delivery.id,'waiting',delivery.attempts,'push_not_configured');continue;}
    try{
      const devices=(await queryDevices(delivery.recipient)).filter(d=>d.enabled && (delivery.recipient==='MANAGER'||d.sub===delivery.workerSub&&d.phoneId===zone.assignedPhoneId));
      if(!devices.length){await markDelivery(delivery.id,'waiting',delivery.attempts,'no_registered_device');continue;}
      const bearer=await firebaseAccess();const account=JSON.parse(process.env.FCM_SERVICE_ACCOUNT);const content=pushText(zone,delivery);
      for(const device of devices){
        const response=await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(account.project_id)}/messages:send`,{method:'POST',headers:{authorization:`Bearer ${bearer}`,'content-type':'application/json'},body:JSON.stringify({message:{token:device.token,
          notification:content,data:{alertId:delivery.alertId,status:delivery.status,...content},android:{priority:'HIGH',ttl:'60s',notification:{channel_id:delivery.status==='SENSOR_LOST'?'stillwatch_connection':'stillwatch_alerts',tag:delivery.alertId}}}}),signal:AbortSignal.timeout(10000)});
        if(!response.ok)throw new Error('push_send_failed');
      }
      await markDelivery(delivery.id,'accepted',delivery.attempts+1);
    }catch(error){await markDelivery(delivery.id,'failed',delivery.attempts+1,['push_not_configured','push_auth_failed','push_send_failed'].includes(error.message)?error.message:'push_transport_failed');}
  }
}
async function markDelivery(id,state,attempts,error=null){await updateZone(zone=>{const d=zone.deliveries.find(x=>x.id===id);if(d){d.state=state;d.attempts=attempts;d.error=error;d.updatedAt=new Date().toISOString();}});}
function bodyOf(event){
  if(!event.body)return{};
  const text=event.isBase64Encoded?Buffer.from(event.body,'base64').toString():event.body;
  if(text.length>(event.rawPath==='/api/voice/audio'?450000:16384))throw new DomainError(413,'요청이 너무 큽니다.');
  try{return JSON.parse(text);}catch{throw new DomainError(400,'JSON 요청이 필요합니다.');}
}
const response=(status,data)=>({statusCode:status,headers:{'content-type':'application/json; charset=utf-8','cache-control':'no-store'},body:JSON.stringify(data)});
async function afterZone(zone){
  const item=zone.safetyCase;
  if(item&&!item.resolvedAt){
    if(!item.workflowStarted&&process.env.FLOW_ARN){
      try{
        await steps.send(new StartExecutionCommand({stateMachineArn:process.env.FLOW_ARN,name:'case-'+item.id,input:JSON.stringify({caseId:item.id,deadlineAt:new Date(item.deadline).toISOString(),managerDeadlineAt:new Date(item.deadline+60000).toISOString(),source:'stillwatch.deadline'})}));
      }catch(error){if(error.name!=='ExecutionAlreadyExists')throw error;}
      await updateZone(z=>{if(z.safetyCase?.id===item.id)z.safetyCase.workflowStarted=true;});
    }
    const signature=reportSignature(item);
    if(item.draftSignature!==signature&&process.env.OPENAI_SECRET_ARN){
      let enqueue=false;
      await updateZone(z=>{enqueue=false;const active=z.safetyCase;if(active?.id===item.id&&!active.resolvedAt&&reportSignature(active)===signature&&active.draftSignature!==signature){active.draftSignature=signature;active.draftStatus='preparing';enqueue=true;}});
      if(enqueue){
        try{await functions.send(new InvokeCommand({FunctionName:process.env.AWS_LAMBDA_FUNCTION_NAME,InvocationType:'Event',Payload:Buffer.from(JSON.stringify({source:'stillwatch.ai',caseId:item.id,signature}))}));}
        catch{await updateZone(z=>{if(z.safetyCase?.id===item.id&&z.safetyCase.draftSignature===signature)z.safetyCase.draftStatus='fallback';});}
      }
    }
    if(item.contact.status==='queued')await dispatchContact(item.id);
  }
  await deliver(zone);
}
async function dispatchContact(caseId){
  const snapshot=(await readZone()).safetyCase;
  if(!snapshot||snapshot.id!==caseId||snapshot.resolvedAt||snapshot.contact.status!=='queued')return;
  let finalReport=snapshot.report;
  if(snapshot.completedDraftSignature!==reportSignature(snapshot)){
    try{finalReport=await generateReport(snapshot,{key:await openAiKey()});}catch{finalReport=templateReport(snapshot);}
  }
  let selected;
  try { await updateZone(z=>{
    const item=z.safetyCase;if(!item||item.id!==caseId||item.resolvedAt||item.contact.status!=='queued')throw new DomainError(409,'연락 요청이 취소되거나 이미 처리됐습니다.');
    // The pilot cannot be redirected to emergency services or an unapproved recipient.
    if(process.env.SMS_TEST_RECIPIENT!=='+821000000000')throw new Error('시험 수신자 설정 오류');
    if(!item.address||!item.entrance){item.contact.status='failed';item.contact.error='현장 주소·진입 방법을 입력한 후 시험해주세요.';return;}
    if(reportSignature(item)===reportSignature(snapshot)){item.report=finalReport;item.completedDraftSignature=reportSignature(item);item.draftStatus=finalReport.source==='ai'?'ready':'fallback';}
    item.contact.status='sending';item.contact.attemptedAt=Date.now();item.contact.message=contactText(item);selected=structuredClone(item);
  }); }catch(error){if(error instanceof DomainError&&error.status===409)return;throw error;}
  if(!selected)return;
  try{
    const result=await messages.send(new PublishCommand({PhoneNumber:process.env.SMS_TEST_RECIPIENT,Message:selected.contact.message.slice(0,1500),MessageAttributes:{'AWS.SNS.SMS.SMSType':{DataType:'String',StringValue:'Transactional'}}}));
    await updateZone(z=>{if(z.safetyCase?.id===caseId)Object.assign(z.safetyCase.contact,{status:'gateway_accepted',messageId:result.MessageId,acceptedAt:Date.now()});});
  }catch{
    await updateZone(z=>{if(z.safetyCase?.id===caseId)Object.assign(z.safetyCase.contact,{status:'unknown',error:'문자 발송 결과 미확인 · 자동 재전송 없음'});});
  }
}
export async function handler(event){
  try{
    if(event.ingest==='native'){
      const current=await readZone();
      if(event.nativeResource==='motion'&&current.nativeSensor?.ready&&current.nativeSampleAt&&event.brokerTime-current.nativeSampleAt<1500&&
        !(event.state==='motion'&&event.brokerTime-Date.parse(current.lastMotionAt||0)>1500))return {accepted:true,sampled:false};
      const zone=await updateZone(z=>applyNative(z,event,Date.now(),process.env.NATIVE_DEVICE_ID));
      if(current.nativeSensor?.ready!==zone.nativeSensor?.ready||current.nativeSensor?.online!==zone.nativeSensor?.online)
        console.info(JSON.stringify({event:'native_availability',kind:event.nativeResource,ready:zone.nativeSensor?.ready,online:zone.nativeSensor?.online}));
      await afterZone(zone);return {accepted:true};
    }
    if(event.source==='stillwatch.ai'){
      const zone=await readZone(),item=zone.safetyCase;if(!item||item.id!==event.caseId||item.resolvedAt||reportSignature(item)!==event.signature)return {ignored:true};
      let report;try{report=await generateReport(item,{key:await openAiKey()});}catch{report=templateReport(item);}
      await updateZone(z=>{const active=z.safetyCase;if(active?.id===item.id&&!active.resolvedAt&&reportSignature(active)===event.signature){active.report=report;active.completedDraftSignature=event.signature;active.draftStatus=report.source==='ai'?'ready':'fallback';}});return {drafted:true};
    }
    if(event.source==='stillwatch.deadline'){
      const zone=await updateZone(z=>{if(z.safetyCase?.id===event.caseId)evaluate(z,Date.now());});await afterZone(zone);return {checked:true};
    }
    if(event._ingest==='telemetry'||event.ingest==='telemetry'){
      const zone=await updateZone(z=>applyTelemetry(z,event,Date.now()));await afterZone(zone);return {accepted:true};
    }
    if(event.source==='aws.events'){
      const zone=await updateZone(z=>evaluate(z,Date.now()));await afterZone(zone);return {checked:true};
    }
    const identity=identityFromClaims(event.requestContext?.authorizer?.jwt?.claims);
    const method=event.requestContext.http.method,path=event.rawPath,body=bodyOf(event);
    if(path==='/api/voice/poll'&&method==='POST'){
      let command;
      const zone=await updateZone(z=>{
        evaluate(z,Date.now());
        if(identity.role==='WORKER'&&(identity.sub!==z.workerSub||body.phoneId!==z.assignedPhoneId))throw new DomainError(403,'지정 작업자 휴대폰이 아닙니다.');
        z.terminals||={};z.terminals[identity.role]={lastSeen:new Date().toISOString(),armed:body.armed===true};
        if(body.armed===true)command=voiceCommand(z,identity,body,Date.now());
      });await afterZone(zone);
      return response(200,{command,serverTime:Date.now(),incidentId:zone.safetyCase?.id,contactStatus:zone.safetyCase?.contact.status,state:zone.state});
    }
    if(path==='/api/voice/event'&&method==='POST'){
      const zone=await updateZone(z=>voiceEvent(z,identity,body,Date.now()));await afterZone(zone);return response(200,{recorded:true});
    }
    if(path==='/api/voice/audio'&&method==='POST'){
      const zone=await readZone(),item=zone.safetyCase;
      if(identity.role!=='WORKER'||identity.sub!==zone.workerSub||body.phoneId!==zone.assignedPhoneId||!item||item.id!==body.incidentId||item.resolvedAt||Date.now()>=item.deadline)throw new DomainError(409,'현재 본인 음성 확인 요청이 아닙니다.');
      if(typeof body.wav!=='string'||body.wav.length>400000||!/^[A-Za-z0-9+/=]+$/.test(body.wav))throw new DomainError(422,'음성 형식을 확인해주세요.');
      const bytes=Buffer.from(body.wav,'base64');if(bytes.length<44||bytes.subarray(0,4).toString()!=='RIFF'||bytes.subarray(8,12).toString()!=='WAVE')throw new DomainError(422,'WAV 음성이 필요합니다.');
      const result=await interpretAudio(bytes,{key:await openAiKey()});
      if(result.error)return response(200,result);
      const updated=await updateZone(z=>{
        const active=z.safetyCase;if(!active||active.id!==item.id||active.resolvedAt||Date.now()>=active.deadline)throw new DomainError(409,'음성 처리 중 응답 마감이 종료됐습니다.');
        active.response={category:result.category,quote:result.quote};active.transcripts.push({role:'worker',text:result.quote,at:Date.now()});active.transcripts=active.transcripts.slice(-30);
        if(result.category==='help_requested')act(z,identity,'help',{quote:result.quote},Date.now());
        else if(result.category==='normal_confirmation')act(z,identity,'respond',{confirmedBy:'voice',quote:result.quote},Date.now());
      });await afterZone(updated);return response(200,{...result,aiUsed:true});
    }
    if(path==='/api/phones'&&method==='POST'){
      if(typeof body.phoneId!=='string'||!/^[-a-zA-Z0-9]{10,80}$/.test(body.phoneId)||typeof body.name!=='string'||body.name.length>50)throw new DomainError(422,'휴대폰 등록 정보를 확인해 주세요.');
      const existing=await db.send(new GetCommand({TableName:table,Key:{pk:'PHONES',sk:body.phoneId},ConsistentRead:true}));
      if(existing.Item&&existing.Item.sub!==identity.sub)throw new DomainError(403,'다른 사용자에게 등록된 휴대폰입니다.');
      await db.send(new PutCommand({TableName:table,Item:{pk:'PHONES',sk:body.phoneId,phoneId:body.phoneId,sub:identity.sub,role:identity.role,name:body.name.trim()||'등록 작업자',updatedAt:new Date().toISOString()},ConditionExpression:'attribute_not_exists(pk) OR #s = :s',ExpressionAttributeNames:{'#s':'sub'},ExpressionAttributeValues:{':s':identity.sub}}));
      return response(200,{registered:true});
    }
    if(path==='/api/phones'&&method==='GET'){
      if(identity.role!=='MANAGER')throw new DomainError(403,'관리자 권한이 필요합니다.');
      const items=await db.send(new QueryCommand({TableName:table,KeyConditionExpression:'pk = :pk',ExpressionAttributeValues:{':pk':'PHONES'}}));
      return response(200,{phones:(items.Items??[]).filter(p=>p.role==='WORKER').map(p=>({phoneId:p.phoneId,name:p.name,sub:p.sub}))});
    }
    if(path==='/api/assignment'&&method==='PUT'){
      if(identity.role!=='MANAGER')throw new DomainError(403,'관리자 권한이 필요합니다.');
      const data=await db.send(new GetCommand({TableName:table,Key:{pk:'PHONES',sk:body.phoneId||'invalid'},ConsistentRead:true}));
      const phone=data.Item;if(!phone||phone.role!=='WORKER')throw new DomainError(422,'등록된 작업자 휴대폰을 선택해 주세요.');
      const zone=await updateZone(z=>{
        if(z.alerts.some(a=>!a.closedAt&&['RESCUE','STILL_CHECK'].includes(a.status)))throw new DomainError(409,'진행 중인 현장 확인을 마친 뒤 작업자를 변경해 주세요.');
        Object.assign(z,{assignedWorkerSub:phone.sub,assignedPhoneId:phone.phoneId,workerSub:phone.sub,workerName:phone.name,phoneStatus:'UNKNOWN',registeredCount:null,enteredAt:null,phoneSeenAt:null,stillSince:null,checkStartedAt:null});
      });return response(200,{zone:publicZone(zone)});
    }
    if(path==='/api/presence'&&method==='POST'){
      const zone=await updateZone(z=>applyPresence(z,identity,body,Date.now()));await afterZone(zone);return response(200,{accepted:true,state:zone.state});
    }
    if(path==='/api/floorplan/upload'&&method==='POST'){
      if(identity.role!=='MANAGER')throw new DomainError(403,'관리자 권한이 필요합니다.');
      const types={'application/pdf':'pdf','image/png':'png','image/jpeg':'jpg'};
      if(!types[body.mime]||!Number.isInteger(body.size)||body.size<1||body.size>10485760)throw new DomainError(422,'10MB 이하 PDF·PNG·JPEG를 선택해 주세요.');
      const objectKey=`${zoneId}/${randomUUID()}.${types[body.mime]}`;
      const url=signObjectUrl({bucket,key:objectKey,method:'PUT',mime:body.mime,size:body.size,region:process.env.AWS_REGION,credentials:{accessKeyId:process.env.AWS_ACCESS_KEY_ID,secretAccessKey:process.env.AWS_SECRET_ACCESS_KEY,sessionToken:process.env.AWS_SESSION_TOKEN}});
      return response(200,{key:objectKey,url});
    }
    if(path==='/api/floorplan'&&method==='GET'){
      const zone=await readZone();if(!zone.floorplan)throw new DomainError(404,'등록된 도면이 없습니다.');
      const url=signObjectUrl({bucket,key:zone.floorplan.key,region:process.env.AWS_REGION,credentials:{accessKeyId:process.env.AWS_ACCESS_KEY_ID,secretAccessKey:process.env.AWS_SECRET_ACCESS_KEY,sessionToken:process.env.AWS_SESSION_TOKEN}});
      return response(200,{...zone.floorplan,url});
    }
    if(path==='/api/floorplan'&&method==='PUT'){
      if(identity.role!=='MANAGER')throw new DomainError(403,'관리자 권한이 필요합니다.');
      const point=p=>p&&Number.isFinite(p.x)&&Number.isFinite(p.y)&&p.x>=0&&p.x<=1&&p.y>=0&&p.y<=1;
      if(typeof body.key!=='string'||!body.key.startsWith(`${zoneId}/`)||!/^[-a-zA-Z0-9]+\.(pdf|png|jpg)$/.test(body.key.slice(zoneId.length+1))||!point(body.espPosition)||!Array.isArray(body.primaryRoute)||!Array.isArray(body.backupRoute)||body.primaryRoute.length>100||body.backupRoute.length>100||!body.primaryRoute.every(point)||!body.backupRoute.every(point))throw new DomainError(422,'도면 위치·대피 경로를 확인해 주세요.');
      const zone=await updateZone(z=>{z.floorplan={key:body.key,mime:body.mime,espPosition:body.espPosition,primaryRoute:body.primaryRoute,backupRoute:body.backupRoute,reviewedAt:body.confirmed===true?new Date().toISOString():null};});return response(200,{zone:publicZone(zone)});
    }
    if(path==='/api/devices'){
      if(typeof body.token!=='string'||body.token.length<20||body.token.length>4096)throw new DomainError(422,'기기 등록 토큰이 필요합니다.');
      const deviceKey={pk:`DEVICE#${identity.role}`,sk:`${identity.sub}#${hash(body.token)}`};
      if(method==='POST'&&(typeof body.phoneId!=='string'||!/^[-a-zA-Z0-9]{10,80}$/.test(body.phoneId)))throw new DomainError(422,'등록 휴대폰 식별자가 필요합니다.');
      if(method==='POST')await db.send(new PutCommand({TableName:table,Item:{...deviceKey,sub:identity.sub,phoneId:body.phoneId,token:body.token,enabled:body.enabled===true,updatedAt:new Date().toISOString()}}));
      else if(method==='DELETE')await db.send(new DeleteCommand({TableName:table,Key:deviceKey}));
      else throw new DomainError(404,'지원하지 않는 요청입니다.');
      return response(200,{registered:method==='POST'});
    }
    if(method==='GET'&&(path==='/api/bootstrap'||path==='/api/alerts')){
      const zone=await updateZone(z=>evaluate(z,Date.now()));await afterZone(zone);
      // Delivery is scheduled independently; foreground reads are never needed for push.
      const visible=publicZone(zone);
      if(identity.role==='WORKER'){visible.alerts=visible.alerts.filter(alert=>alert.workerSub===identity.sub);if(zone.workerSub!==identity.sub)visible.safetyCase=null;}
      return response(200,path==='/api/bootstrap'?{role:identity.role,sub:identity.sub,zone:visible}:{alerts:visible.alerts});
    }
    const zoneRoute=path.match(/^\/api\/zones\/([^/]+)(?:\/(respond|help))?$/);
    const alertRoute=path.match(/^\/api\/alerts\/([^/]+)\/(acknowledge|close|request-help)$/);
    if(zoneRoute&&zoneRoute[1]===zoneId){
      const action=method==='PUT'&&!zoneRoute[2]?'settings':method==='POST'?zoneRoute[2]:null;
      if(!action)throw new DomainError(404,'지원하지 않는 요청입니다.');
      const zone=await updateZone(z=>act(z,identity,action,body,Date.now()));await afterZone(zone);return response(200,{zone:publicZone(zone)});
    }
    if(method==='POST'&&alertRoute){const zone=await updateZone(z=>act(z,identity,alertRoute[2],{...body,alertId:alertRoute[1]},Date.now()));await afterZone(zone);return response(200,{zone:publicZone(zone)});}
    throw new DomainError(404,'요청한 기능을 찾을 수 없습니다.');
  }catch(error){
    if(error instanceof DomainError)return response(error.status,{message:error.message});
    console.error(JSON.stringify({error:error.name||'Error'}));
    return response(500,{message:'서버 처리를 완료하지 못했습니다. 다시 확인해 주세요.'});
  }
}
