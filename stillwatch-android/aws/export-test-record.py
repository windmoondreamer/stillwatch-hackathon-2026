"""Export factual, clearly labelled cloud evidence; no credentials or raw microphone audio."""
from pilot_tools import *
z=api('manager','GET','/api/bootstrap')['zone'];c=z.get('safetyCase') or {};contact=c.get('contact') or {}
if c.get('id')!='b6a4b390-8f19-459d-b001-42e88ab4a62a':raise RuntimeError('Expected the approved mutual no-response test case')
record={'test':True,'real119Sent':False,'scenario':'가상 움직임 미감지 입력 → 실제 작업자 음성 질문 → 작업자 무응답 → 실제 관리자 앱 알림 → 관리자 무응답 → 시험 번호 문자',
 'incidentId':c['id'],'testInput':'synthetic','workerDevice':'Samsung Galaxy S23 (SM-S911N)','managerDevice':'Samsung tablet (SM-X930), Wi-Fi, USB 미연결',
 'workerDeadline':c.get('deadline'),'managerDeadline':c.get('managerDeadline'),'managerNotificationReceivedAt':c.get('managerNotificationReceivedAt'),'managerFeedback':c.get('managerFeedback'),
 'questionCount':c.get('questionCount'),'draftStatus':c.get('draftStatus'),'reportSource':c.get('report',{}).get('source'),
 'sms':{k:contact.get(k) for k in ['recipient','status','reason','attemptedAt','acceptedAt','messageId','message']},
 'userConfirmedManagerAlert':True,'userConfirmedSmsDelivery':True,
 'userConfirmation':'사용자가 관리자 태블릿 알림과 시험 번호 문자 모두 도착했다고 확인했습니다.',
 'deliveryMeaning':'gateway_accepted는 AWS 발송 요청 수락이며, 이번 시험의 실제 문자 수신은 사용자가 별도로 확인했습니다. 119 접수·출동을 의미하지 않습니다.'}
out=ROOT/'captures/2026-10-09';out.mkdir(parents=True,exist_ok=True)
(out/'automatic-contact-record.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'incidentId':record['incidentId'],'smsStatus':contact.get('status'),'workerDeadline':record['workerDeadline'],'managerDeadline':record['managerDeadline'],'acceptedAt':contact.get('acceptedAt')},ensure_ascii=True))
