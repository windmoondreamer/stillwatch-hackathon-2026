import sys
from pilot_tools import *
NODE=r'C:\Users\User\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
resources=parsed('cloudformation','list-stack-resources','--stack-name','stillwatch-pilot')['StackResourceSummaries']
table=next(r['PhysicalResourceId'] for r in resources if r['LogicalResourceId']=='State')
keyfile=save('db-key.json',{'pk':{'S':'ZONE#room-01'},'sk':{'S':'STATE'}})
def decode(value):
 if 'S'in value:return value['S']
 if 'N'in value:return float(value['N']) if '.'in value['N'] else int(value['N'])
 if 'BOOL'in value:return value['BOOL']
 if 'NULL'in value:return None
 if 'L'in value:return [decode(v) for v in value['L']]
 if 'M'in value:return {k:decode(v) for k,v in value['M'].items()}
 raise ValueError('Unexpected attribute type')
def encode(value):
 if value is None:return {'NULL':True}
 if isinstance(value,bool):return {'BOOL':value}
 if isinstance(value,str):return {'S':value}
 if isinstance(value,(int,float)):return {'N':str(value)}
 if isinstance(value,list):return {'L':[encode(v) for v in value]}
 return {'M':{k:encode(v) for k,v in value.items()}}
for attempt in range(5):
 raw=parsed('dynamodb','get-item','--table-name',table,'--key',keyfile,'--consistent-read')['Item'];zone={k:decode(v) for k,v in raw.items()};save('current-zone.json',zone)
 result=subprocess.run([NODE,str(ROOT/'aws/create-test-case.mjs'),sys.argv[1] if len(sys.argv)>1 else '45'],capture_output=True,text=True)
 if result.returncode:raise RuntimeError(result.stderr[:500])
 updated=json.loads((PRIVATE/'test-zone.json').read_text(encoding='utf-8'))
 values=save('db-test-values.json',{':case':encode(updated['safetyCase']),':at':encode(updated['checkStartedAt']),':one':encode(1),':phone':encode('CONNECTED'),
  ':t1':encode(300),':t2':encode(updated['t2']),':added':encode([updated['alerts'][0]]),':nulltype':encode('NULL')})
 put=aws('dynamodb','update-item','--table-name',table,'--key',keyfile,
  '--update-expression','SET #c = :case, checkStartedAt = :at, registeredCount = :one, phoneStatus = :phone, phoneSeenAt = :at, t1 = :t1, t2 = :t2, #a = list_append(:added, #a), #v = #v + :one',
  '--condition-expression','attribute_not_exists(#c) OR attribute_type(#c, :nulltype) OR attribute_exists(#c.resolvedAt)',
  '--expression-attribute-names','{"#c":"safetyCase","#a":"alerts","#v":"version"}','--expression-attribute-values',values,allow_error=True)
 if not put.returncode:
  for read_attempt in range(3):
   try:api('manager','GET','/api/bootstrap');break
   except urllib.error.HTTPError as error:
    if error.code!=409 or read_attempt==2:raise
    time.sleep(.5)
  print(result.stdout.strip());print('Synthetic input only; Android voice, OpenAI, AWS deadlines and approved test SMS use real integrations.');break
 if 'ConditionalCheckFailedException' not in put.stderr:raise RuntimeError(put.stderr)
else:raise RuntimeError('State changed during all attempts. Retry while the app is idle.')
