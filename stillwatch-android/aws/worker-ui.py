"""Inspect or tap a named control in the explicitly selected pilot app only."""
import xml.etree.ElementTree as ET,sys
from pilot_tools import *
package=os.environ.get('STILLWATCH_ADB_PACKAGE','kr.stillwatch.workerpilot')
adb('shell','uiautomator','dump','/data/local/tmp/stillwatch-worker-ui.xml')
root=ET.fromstring(adb('exec-out','cat','/data/local/tmp/stillwatch-worker-ui.xml'))
nodes=[n for n in root.iter('node') if n.get('package')==package]
if not nodes:raise RuntimeError('Pilot app is not in the foreground; no UI action performed.')
if len(sys.argv)==1:
 for n in nodes:
  if n.get('text'):print(json.dumps({'text':n.get('text'),'bounds':n.get('bounds'),'enabled':n.get('enabled')},ensure_ascii=True))
else:
 target=sys.argv[1];node=next((n for n in nodes if n.get('text')==target and n.get('enabled')=='true'),None)
 if node is None:raise RuntimeError('Requested pilot control is not visible.')
 nums=[int(s) for s in re.findall(r'\d+',node.get('bounds',''))]
 if len(nums)!=4:raise RuntimeError('Invalid control bounds')
 adb('shell','input','tap',str((nums[0]+nums[2])//2),str((nums[1]+nums[3])//2))
 print('Tapped pilot control:',json.dumps(target,ensure_ascii=True))
