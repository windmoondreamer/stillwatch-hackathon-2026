import sys,time
from pilot_tools import *
role=sys.argv[1].lower() if len(sys.argv)>1 else 'worker'
package=os.environ.get('STILLWATCH_ADB_PACKAGE','kr.stillwatch.app')
if not re.fullmatch(r'kr\.stillwatch\.[a-z]+',package):raise RuntimeError('Unexpected pilot package')
activity=package+'/kr.stillwatch.app.MainActivity'
adb('shell','am','start','-n',activity)
time.sleep(1)
data=(PRIVATE/(role+'-session.json')).read_bytes()
adb('shell','run-as',package,'mkdir','-p','files')
adb('shell','run-as '+package+' sh -c "cat > files/debug-session.json"',input=data)
arm='false' if '--no-arm' in sys.argv else 'true'
print(adb('shell','am','start','-n',activity,'--ez','debugImportSession','true','--ez','debugArmVoice',arm))
print('Private '+role+' session imported for the authorized USB test; no credentials printed.')
