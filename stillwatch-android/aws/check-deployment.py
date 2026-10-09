from pilot_tools import *
m=metadata();stack=parsed('cloudformation','describe-stacks','--stack-name',m['stack'])['Stacks'][0]
z=api('manager','GET','/api/bootstrap')['zone'];c=z.get('safetyCase') or {}
print(json.dumps({'stack':stack['StackStatus'],'state':z['state'],'sensor':z.get('nativeSensor'), 'lastReceivedAt':z.get('lastReceivedAt'), 'lastValidAt':z.get('lastValidAt'), 'reportSource':c.get('report',{}).get('source'), 'reportStatus':c.get('report',{}).get('status'),'resolved':bool(c.get('resolvedAt')),'contact':c.get('contact',{}).get('status'),'t1':z.get('t1'),'t2':z.get('t2')},ensure_ascii=True))
events=parsed('logs','filter-log-events','--log-group-name','/aws/lambda/stillwatch-pilot-api','--start-time',str(int(time.time()*1000)-180000),'--filter-pattern','native_availability')['events']
print('Availability transitions:',len(events))
for e in events[-8:]:print(e['message'].strip())
