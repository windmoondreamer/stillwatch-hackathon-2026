from pilot_tools import *
package=os.environ.get('STILLWATCH_ADB_PACKAGE','kr.stillwatch.app')
z=api('manager','GET','/api/bootstrap')['zone'];c=z.get('safetyCase') or {}
a=next((a for a in z['alerts'] if a.get('caseId')==c.get('id') and a.get('status')=='RESCUE'),None)
if not a:a=next((a for a in z['alerts'] if a.get('caseId')==c.get('id')),None)
if not a:raise RuntimeError('No case alert to display')
print(adb('shell','am','start','-n',package+'/kr.stillwatch.app.MainActivity','--es','alertId',a['id']))
print('Opened current cloud case:',c['id'])
