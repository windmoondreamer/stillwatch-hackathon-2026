import base64
from pilot_tools import *
wave=subprocess.run([ADB,'-d','exec-out','run-as','kr.stillwatch.app','cat','files/fixture-normal.wav'],capture_output=True,check=True).stdout
z=api('worker','GET','/api/bootstrap')['zone'];phone=api('manager','GET','/api/phones')['phones'][0]
case=z.get('safetyCase')
if not case or case.get('resolvedAt'):raise RuntimeError('Start a controlled worker check first.')
result=api('worker','POST','/api/voice/audio',{'phoneId':phone['phoneId'],'incidentId':case['id'],'wav':base64.b64encode(wave).decode()})
save('normal-voice-result.json',result)
print(json.dumps({'syntheticSpeech':True,'category':result.get('category'),'aiUsed':result.get('aiUsed'),'error':result.get('error')},ensure_ascii=True))
z=api('manager','GET','/api/bootstrap')['zone'];print('Contact status:',z['safetyCase']['contact']['status'])
