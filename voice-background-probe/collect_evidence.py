"""Collect only this probe's logs and evaluate the recorded five cases."""
import datetime, hashlib, json, pathlib, subprocess

ROOT = pathlib.Path(__file__).resolve().parent
DIRECTORY = ROOT / 'evidence'
ADB = r'C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe'
def adb(*args):
    return subprocess.run([ADB, '-d', *args], capture_output=True, text=True, encoding='utf-8', errors='replace')

all_events = adb('shell', 'run-as', 'com.stillwatch.voiceprobe', 'cat', 'files/events.jsonl')
if all_events.returncode:
    raise RuntimeError(all_events.stderr)
(DIRECTORY / 'all-events.jsonl').write_text(all_events.stdout, encoding='utf-8')
results = []
for name in ['foreground', 'home', 'locked', 'task-manager-stop', 'force-stop']:
    rows = [json.loads(line) for line in (DIRECTORY / (name + '.jsonl')).read_text(encoding='utf-8').splitlines() if line]
    events = [row['event'] for row in rows]
    stopped = name.endswith('stop')
    mic = next((row for row in rows if row['event'] == 'mic_finished'), {})
    trigger = next((row for row in rows if row['event'] == 'check_triggered'), {})
    passed = ('check_scheduled' in events and 'check_triggered' not in events) if stopped else ('speech_finished' in events and mic.get('frames', 0) > 0 and mic.get('clientSilenced') is False)
    if name == 'foreground': passed &= trigger.get('activityVisible') is True
    if name == 'home': passed &= trigger.get('activityVisible') is False and trigger.get('interactive') is True
    if name == 'locked': passed &= trigger.get('interactive') is False and trigger.get('locked') is True
    results.append({'case': name, 'expectedLocalBehaviorObserved': passed, 'checkState': trigger, 'micFrames': mic.get('frames'), 'humanAudibility': 'confirmed' if name in ('home', 'locked') else 'not separately confirmed' if not stopped else 'not applicable', 'noProcessObservedInTestCommandOutput': True if stopped else None})

report = {'testedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'model': adb('shell', 'getprop', 'ro.product.model').stdout.strip(), 'android': adb('shell', 'getprop', 'ro.build.version.release').stdout.strip(), 'sdk': 36, 'targetSdk': 35, 'apkSha256': hashlib.sha256((ROOT / 'build/stillwatch-voice-probe.apk').read_bytes()).hexdigest(), 'apkNote': 'Final APK includes extra audio-state logging. Earlier foreground/home/stop cases used the same implementation without these extra logs.', 'requestDelivery': 'local timer; remote push untested', 'earlierLockedAudibility': 'User first reported only home audible; later locked retest explicitly confirmed sound. Cause of earlier non-audibility is unproven.', 'cleanupAppProcessPresent': bool(adb('shell', 'pidof', 'com.stillwatch.voiceprobe').stdout.strip()), 'cases': results}
(DIRECTORY / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'model': report['model'], 'android': report['android'], 'cases': [{'case': row['case'], 'expectedBehaviorObserved': row['expectedLocalBehaviorObserved']} for row in results], 'cleanupAppProcessPresent': report['cleanupAppProcessPresent']}, ensure_ascii=False))
if not all(row['expectedLocalBehaviorObserved'] for row in results) or report['cleanupAppProcessPresent']:
    raise SystemExit(1)
