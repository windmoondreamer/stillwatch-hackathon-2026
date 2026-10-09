"""USB developer integration test. Only controls com.stillwatch.worker."""
import argparse, json, pathlib, subprocess, time, urllib.request
ROOT = pathlib.Path(__file__).resolve().parent
ADB = r'C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe'
BASE = 'http://127.0.0.1:3210'
PACKAGE = 'com.stillwatch.worker'
TASK = '실기기 서버 연결 시험'
def adb(*args, check=True):
    result = subprocess.run([ADB, '-d', *args], capture_output=True, text=True, encoding='utf-8', errors='replace')
    if check and result.returncode: raise RuntimeError(result.stderr or result.stdout)
    return result.stdout.strip()
def api(route, value=None):
    request = urllib.request.Request(BASE + route, data=None if value is None else json.dumps(value).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=5) as response: return json.load(response)
def clear_own_job():
    state = api('/api/state')
    if state['state'] in ('IDLE', 'ENDED'): return
    if state['config'].get('task') != TASK: raise RuntimeError('다른 작업이 진행 중입니다. 자동으로 종료하지 않습니다.')
    if state['state'] in ('CHECKING', 'ALERTED'):
        api('/api/manager-feedback', {'incidentId': state['incident']['id'], 'action': 'normal', 'note': '개발 시험 종료 확인'})
    api('/api/end', {})
parser = argparse.ArgumentParser(); parser.add_argument('case', choices=['prepare', 'home', 'locked', 'cleanup']); case = parser.parse_args().case
if case == 'prepare':
    pairing = api('/api/mobile/pairing')['token']
    adb('reverse', 'tcp:3210', 'tcp:3210')
    adb('shell', 'am', 'start', '-S', '-n', PACKAGE + '/.MainActivity', '--es', 'server', BASE, '--es', 'pairing', pairing, '--ez', 'autostart', 'true')
    time.sleep(5)
    print(json.dumps({'devices': api('/api/config')['phoneDevices']}, ensure_ascii=False))
elif case == 'cleanup':
    clear_own_job(); adb('shell', 'am', 'force-stop', PACKAGE); adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    print('test app stopped; screen awake')
else:
    clear_own_job()
    phones = api('/api/config')['phoneDevices']
    if not any(device['role'] == 'worker' and device['connected'] and device['armed'] for device in phones): raise RuntimeError('Android 작업자 대기가 준비되지 않았습니다.')
    previous = adb('shell', 'run-as', PACKAGE, 'cat', 'files/events.jsonl', check=False)
    start_at = len([line for line in previous.splitlines() if line.startswith('{')])
    api('/api/start', {'room': '시험 구역', 'address': '실기기 시험용 주소', 'floor': '시험층', 'entrance': '시험용 진입 방법', 'workerName': '시험 작업자', 'task': TASK,
       'mode': 'test', 'input': 'demo', 'voiceOutput': 'phone', 'stillSeconds': 2, 'responseSeconds': 20, 'managerSeconds': 3, 'autoContact': True, 'managerEnabled': True, 'recipient': 'test'})
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME' if case == 'home' else 'KEYCODE_SLEEP')
    api('/api/demo-motion', {'motion': False})
    print('waiting for remote question, microphone and manager timeout', flush=True); time.sleep(28)
    state = api('/api/state')
    logs = adb('shell', 'run-as', PACKAGE, 'cat', 'files/events.jsonl')
    rows = [json.loads(line) for line in logs.splitlines() if line.startswith('{')][start_at:]
    directory = ROOT / 'evidence'; directory.mkdir(exist_ok=True)
    (directory / (case + '.json')).write_text(json.dumps({'state': state, 'deviceEvents': rows, 'humanAudibility': 'not separately confirmed in this run', 'aiUsed': False}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'case': case, 'contact': state['incident']['contact']['status'], 'phoneEvents': rows, 'questionCount': state['incident']['questionCount']}, ensure_ascii=False))
    if state['incident']['contact']['status'] != 'recorded' or not any(row['event'] == 'mic_finished' for row in rows): raise RuntimeError('실기기 연결 시험 실패')
    if case == 'locked' and not any(row['event'] == 'speech_finished' and row['locked'] and not row['interactive'] for row in rows): raise RuntimeError('잠금 음성 로그 없음')
