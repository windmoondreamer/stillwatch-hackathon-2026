"""Save an unedited screenshot of the foreground StillWatch pilot app."""
from pilot_tools import *
import sys,xml.etree.ElementTree as ET
package=os.environ.get('STILLWATCH_ADB_PACKAGE','kr.stillwatch.workerpilot')
name=sys.argv[1]
if not re.fullmatch(r'[a-z0-9_-]+',name):raise RuntimeError('Unexpected capture name')
adb('shell','uiautomator','dump','/data/local/tmp/stillwatch-capture-ui.xml')
xml=adb('exec-out','cat','/data/local/tmp/stillwatch-capture-ui.xml');root=ET.fromstring(xml)
if not any(n.get('package')==package for n in root.iter('node')):raise RuntimeError('Pilot app is not visible. No screenshot saved.')
out=ROOT/'captures/2026-10-09';out.mkdir(parents=True,exist_ok=True)
serial=os.environ.get('STILLWATCH_ADB_SERIAL','')
command=[ADB,*(['-s',serial] if serial else ['-d']),'exec-out','screencap','-p']
result=subprocess.run(command,capture_output=True,check=True)
if not result.stdout.startswith(b'\x89PNG'):raise RuntimeError('Unexpected screenshot format')
(out/(name+'.png')).write_bytes(result.stdout)
(PRIVATE/(name+'-ui.xml')).write_text(xml,encoding='utf-8')
print('Saved actual pilot screenshot:',name+'.png')
