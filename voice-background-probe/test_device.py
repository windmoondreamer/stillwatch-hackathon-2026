"""Physical USB Android probe; only controls our diagnostic package."""
import argparse, datetime, json, pathlib, subprocess, time

ROOT = pathlib.Path(__file__).resolve().parent
ADB = r'C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe'
PACKAGE = 'com.stillwatch.voiceprobe'

def adb(*args, check=True):
    result = subprocess.run([ADB, '-d', *args], capture_output=True, text=True, encoding='utf-8', errors='replace')
    if check and result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result.stdout.strip()

def events():
    content = adb('shell', 'run-as', PACKAGE, 'cat', 'files/events.jsonl', check=False)
    return [json.loads(line) for line in content.splitlines() if line.startswith('{')]

def save(case, rows):
    directory = ROOT / 'evidence'
    directory.mkdir(exist_ok=True)
    (directory / (case + '.jsonl')).write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8')
    negative = case in ('force-stop', 'task-manager-stop')
    process = adb('shell', 'pidof', PACKAGE, check=False) if negative else None
    names = [row['event'] for row in rows]
    if negative:
        passed = 'check_scheduled' in names and 'check_triggered' not in names and not process
    else:
        mic = next((row for row in rows if row['event'] == 'mic_finished'), {})
        triggered = next((row for row in rows if row['event'] == 'check_triggered'), {})
        passed = 'speech_finished' in names and mic.get('frames', 0) > 0 and mic.get('clientSilenced') is False
        if case == 'home': passed = passed and triggered.get('activityVisible') is False and triggered.get('interactive') is True
        if case == 'locked': passed = passed and triggered.get('interactive') is False and triggered.get('locked') is True
        if case == 'foreground': passed = passed and triggered.get('activityVisible') is True
    summary = {'case': case, 'expectedBehaviorObserved': passed, 'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'appProcessAfterStop': process, 'audibilityConfirmedByHuman': None, 'deliveryMethod': 'local Handler timer, not FCM'}
    (directory / (case + '.summary.json')).write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'case': case, 'events': rows}, ensure_ascii=False))

parser = argparse.ArgumentParser()
parser.add_argument('case', choices=['foreground', 'home', 'locked', 'force-stop', 'task-manager-stop', 'collect-home'])
case = parser.parse_args().case
if case == 'collect-home':
    time.sleep(12)
    all_rows = events()
    starts = [i for i, row in enumerate(all_rows) if row['event'] == 'activity_created']
    save('home', all_rows[starts[-1]:])
else:
    before = len(events())
    action = 'CHECK' if case == 'foreground' else 'DELAY'
    print(adb('shell', 'am', 'start', '-S', '-n', PACKAGE + '/.MainActivity', '--ez', 'autostart', 'true', '--es', 'action', action), flush=True)
    time.sleep(1)
    if case == 'home':
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    elif case == 'locked':
        adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
    elif case in ('force-stop', 'task-manager-stop'):
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        if case == 'force-stop':
            adb('shell', 'am', 'force-stop', PACKAGE)
        else:
            adb('shell', 'cmd', 'activity', 'stop-app', PACKAGE)
    time.sleep(10 if case == 'foreground' else 18)
    save(case, events()[before:])
    if case in ('force-stop', 'task-manager-stop'):
        print('app_process_after_wait=' + adb('shell', 'pidof', PACKAGE, check=False))
