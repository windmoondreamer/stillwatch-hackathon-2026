from pilot_tools import *
phones=api('manager','GET','/api/phones')['phones']
if not phones:raise RuntimeError('Open the worker app once so the phone is registered.')
phone=phones[0]
api('manager','PUT','/api/assignment',{'phoneId':phone['phoneId']})
site={'name':'[시험] StillWatch 연결 확인 구역','area':'[시험] 연결된 태블릿과 ESP32','address':'[시험] 실제 현장 주소 미등록',
 'floor':'[시험] 현장 위치를 앱에서 입력해주세요','entrance':'[시험] 실제 진입 방법 미등록','callback':'01000000000','task':'[시험] 기능 연결 검증',
 't1':300,'t2':45,'managerEnabled':True,'exitGuide':'','targetWifiSsid':'Guest','targetWifiBssid':''}
api('manager','PUT','/api/zones/room-01',site)
save('test-site.json',site)
print('Registered tablet assigned. Clearly labelled test site stored; real site fields remain editable in the app.')
