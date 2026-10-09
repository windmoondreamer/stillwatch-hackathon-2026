from pilot_tools import *
z=api('manager','GET','/api/bootstrap')['zone']
c=z.get('safetyCase') or {}
save('latest-test-zone.json',z)
print(json.dumps({'now':int(time.time()*1000),'sensor':z.get('nativeSensor'), 'case':{k:c.get(k) for k in ['id','phase','startedAt','deadlineAt','managerDeadlineAt','resolvedAt','questionCount','workerResponse','managerFeedback','draftStatus']},'contact':c.get('contact'), 'alerts':[{k:a.get(k) for k in ['id','type','status','caseId']} for a in z.get('alerts',[])[:6]]},ensure_ascii=True))
