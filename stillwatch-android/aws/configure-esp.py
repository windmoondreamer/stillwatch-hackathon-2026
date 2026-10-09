from pilot_tools import *
h=json.loads((PRIVATE/'hardware.json').read_text(encoding='utf-8-sig'));m=metadata()
credentials=json.loads((PRIVATE/'device-secret.json').read_text())
body={'scheme':'mqtts','host':m['nativeEndpoint'],'port':443,'username':credentials['username'],'password':credentials['password'],'topic_prefix':'espectre/v1/devices'}
request=urllib.request.Request('http://'+h['ip']+':62587/espectre/v1/mqtt',method='PATCH',data=json.dumps(body).encode(),headers={'Origin':'https://test.espectre.dev','Content-Type':'application/json'})
with urllib.request.urlopen(request,timeout=12) as response:print('ESP32 secure cloud broker configured:',json.load(response)['accepted'])
print('Endpoint:',m['nativeEndpoint'])
