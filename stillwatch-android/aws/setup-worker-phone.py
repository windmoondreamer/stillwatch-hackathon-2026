"""Assign only the explicitly selected USB worker phone; preserve all site settings."""
import xml.etree.ElementTree as ET
from pilot_tools import *
package=os.environ.get('STILLWATCH_ADB_PACKAGE','kr.stillwatch.app')
if not re.fullmatch(r'kr\.stillwatch\.[a-z]+',package):raise RuntimeError('Unexpected pilot package')
text=adb('exec-out','run-as',package,'cat','shared_prefs/phone_identity.xml')
prefs=ET.fromstring(text);phone_id=next((v.text for v in prefs.findall('string') if v.get('name')=='id'),None)
if not phone_id:raise RuntimeError('Open the worker app and wait for registration first.')
phones=api('manager','GET','/api/phones')['phones']
phone=next((p for p in phones if p['phoneId']==phone_id),None)
if not phone:raise RuntimeError('Worker registration has not reached AWS yet.')
z=api('manager','GET','/api/bootstrap')['zone']
save('s23-previous-assignment.json',{k:z.get(k) for k in ['assignedPhoneId','assignedWorkerSub','workerName']})
api('manager','PUT','/api/assignment',{'phoneId':phone_id})
save('s23-worker-device.json',{'serial':os.environ.get('STILLWATCH_ADB_SERIAL'),'package':package,'phoneId':phone_id})
print('New worker phone assigned to the existing zone. Site fields preserved.')
