from pilot_tools import *
z=api('manager','GET','/api/bootstrap')['zone'];c=z.get('safetyCase') or {}
a=next((a for a in z.get('alerts',[]) if a.get('caseId')==c.get('id') and not a.get('closedAt')),None)
if a:
 api('manager','POST','/api/alerts/'+a['id']+'/close',{'reason':'시험 종료: 실험용 입력과 연결 동작 확인 완료'})
 print('Closed controlled test case:',c['id'])
else:print('No open test case to close.')
