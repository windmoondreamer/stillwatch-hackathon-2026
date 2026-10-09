from pilot_tools import *
z=api('manager','GET','/api/bootstrap')['zone'];c=z.get('safetyCase') or {}
print(json.dumps({'now':int(time.time()*1000),'state':z['state'],'phoneStatus':z.get('phoneStatus'),'terminals':z.get('terminals'),
 'case':{k:c.get(k) for k in ['id','phase','deadline','managerDeadline','managerNotificationReceivedAt','managerFeedback','resolvedAt','questionCount','draftStatus']},'contact':c.get('contact',{}).get('status')},ensure_ascii=True))
save('latest-mutual-test.json',z)
