"""Package only original captures and whitelisted, factual test records."""
import datetime, hashlib, html, json, pathlib, zipfile
from PIL import Image

root = pathlib.Path(__file__).resolve().parent.parent
out = root / 'captures/2026-10-09'
kst = datetime.timezone(datetime.timedelta(hours=9))
def stamp(value):
    return datetime.datetime.fromtimestamp(value / 1000, kst).isoformat(timespec='milliseconds') if value else None

zone = json.loads((root / '.private/latest-mutual-test.json').read_text(encoding='utf-8'))
case = zone['safetyCase']
if case['id'] != '0a2007df-1ecd-4d6b-b156-e8093ebf56fc' or not case.get('resolvedAt') or case['contact']['status'] != 'cancelled':
    raise RuntimeError('Expected the completed, cancelled normal-response capture case')
normal = {
    'test': True, 'real119Sent': False, 'testInput': 'synthetic', 'incidentId': case['id'],
    'device': 'Samsung Galaxy S23 (SM-S911N), Android 16',
    'interaction': '실제 작업자 앱에서 Codex가 괜찮아요 버튼을 눌렀습니다. 이 영상은 음성 답변 성공 영상과 구분합니다.',
    'startedAtKst': stamp(case['startedAt']), 'workerDeadlineKst': stamp(case['deadline']),
    'resolvedAtKst': stamp(case['resolvedAt']), 'contactStatus': case['contact']['status'],
    'managerDeadline': case.get('managerDeadline'), 'voiceQuestionCount': case['questionCount'],
    'sensorStateAfterResponse': zone['state'],
    'sensorMeaning': '응답 성공과 센서 감시 상태는 별개입니다. 시험 중 SENSOR_LOST가 관찰돼 감시 불가로 표시한 구간도 있습니다.',
    'video': {'file': 'normal-response.mp4', 'audioIncluded': False, 'width': 1080, 'height': 2340, 'durationSeconds': 126.484944},
}
(out / 'normal-response-record.json').write_text(json.dumps(normal, ensure_ascii=False, indent=2), encoding='utf-8')
automatic = json.loads((out / 'automatic-contact-record.json').read_text(encoding='utf-8'))
for key in ['workerDeadline', 'managerDeadline', 'managerNotificationReceivedAt']:
    automatic[key + 'Kst'] = stamp(automatic.get(key))
for key in ['attemptedAt', 'acceptedAt']:
    automatic['sms'][key + 'Kst'] = stamp(automatic['sms'].get(key))
automatic['messageSourceNote'] = '사전 AI 문안 기록과 실제 발송 문자를 구분합니다. 이번 발송 문자는 사실 템플릿을 사용했으며 sms.message에 실제 발송 내용이 있습니다.'
(out / 'automatic-contact-record.json').write_text(json.dumps(automatic, ensure_ascii=False, indent=2), encoding='utf-8')

items = [
    ('01_worker_home_after_alert.png', '작업자 홈 · 현장 확인 요청', '실제 Galaxy S23'),
    ('02_manager_incident.png', '관리자 사건 상세 · 작업자 무응답', '검증용 Android 에뮬레이터 / 실제 AWS 사건'),
    ('03_manager_incident_timeline.png', '관리자 사건 상세 · 문안과 연락 상태', '검증용 Android 에뮬레이터 / 실제 AWS 사건'),
    ('04_manager_automatic_contact.png', '자동 연락 · 발송 문안과 사건 기록', '검증용 Android 에뮬레이터 / 실제 AWS 사건'),
    ('05_worker_connection_settings.png', '작업자 음성 대기 · 서버 연결', '실제 Galaxy S23'),
    ('06_manager_sms_status.png', '관리자 60초 마감 · AWS 문자 발송 수락', '검증용 Android 에뮬레이터 / 실제 AWS 사건'),
    ('07_manager_home.png', '관리자 홈 · 미해결 사건', '검증용 Android 에뮬레이터 / 실제 AWS 사건'),
    ('08_manager_settings.png', '관리자 현장 설정 · 주소와 진입 방법', '검증용 Android 에뮬레이터'),
    ('09_worker_home_before_check.png', '작업자 홈 · 확인 요청 전', '실제 Galaxy S23'),
    ('10_worker_normal_request.png', '정상 응답 과정 1 · 괜찮으신가요?', '실제 Galaxy S23'),
    ('11_worker_normal_response_sent.png', '정상 응답 과정 2 · 서버 기록 완료', '실제 Galaxy S23'),
    ('12_worker_normal_response_complete.png', '정상 응답 과정 3 · 자동 연락 취소', '실제 Galaxy S23'),
]
readme = f'''# StillWatch 상황별 캡처 · 2026-10-09

`index.html`을 열면 사진과 영상을 한 화면에서 볼 수 있습니다. PNG 12장, 원본 MP4 1개, 사건 기록 JSON 2개와 파일 해시를 포함합니다.

## 정상 확인 및 응답

- 실제 Galaxy S23의 앱 화면을 녹화했습니다. 확인 요청 → 괜찮아요 버튼 → 본인 응답 기록 → 자동 연락 취소입니다.
- 이 녹화에서 버튼은 Codex가 조작했습니다. 마이크 음성 답변 성공 영상으로 소개하지 않습니다.
- 요청: {normal['startedAtKst']}; 응답 완료: {normal['resolvedAtKst']}; 자동 연락: cancelled.
- `normal-response.mp4`: 1080×2340, 약 2분 6초, 무음. 원본을 편집하거나 재생 속도를 바꾸지 않았습니다.
- 별도로 실제 S23 마이크 응답 시험은 16:18:33~38 녹음 → 16:18:42 normal_confirmation → 발송 취소로 확인했습니다. 해당 시험 사건은 b9b63f8e-ff81-460f-a79b-0cb3256a139b이며, 이 영상의 버튼 응답 사건과 다릅니다.

## 작업자·관리자 무응답에 따른 자동 시험 문자

- 작업자 응답 마감: {automatic['workerDeadlineKst']}.
- 실제 관리자 태블릿 알림 수신 기록: {automatic['managerNotificationReceivedAtKst']}.
- 관리자 60초 응답 마감: {automatic['managerDeadlineKst']}.
- AWS 문자 발송 요청 수락: {automatic['sms']['acceptedAtKst']}.
- 관리자 정상 확인 피드백 없음, 발송 사유 manager_timeout.
- 사용자가 실제 관리자 태블릿 알림과 시험 번호 문자 모두 도착했다고 확인했습니다.
- 실제 수신자는 승인된 시험 번호 010-0000-0000입니다. 실제 119 신고·접수·출동 시험은 하지 않았습니다.
- 사전 AI 문안 작성과 실제 발송 문안을 구분합니다. 이번 실제 문자에는 사실 템플릿을 사용했습니다. 정확한 발송 내용과 메시지 ID는 automatic-contact-record.json에 있습니다.

## 화면과 시험의 출처

- 작업자 사진·영상: USB로 연결된 실제 Galaxy S23 / Android 16.
- 관리자 알림을 받은 실제 기기: SM-X930 태블릿 / Android 16. USB 연결 없이 AWS와 통신했습니다.
- 관리자 화면 사진: 같은 실제 AWS 사건을 조회하는 별도 Android 에뮬레이터에서 캡처했습니다. 실제 태블릿 사진으로 소개하지 않습니다. 에뮬레이터 상태바 시각은 UTC, 앱 사건 시각은 KST입니다.
- 움직임 미감지 사건 시작 조건은 가상 시험 입력입니다. 음성 질문, 앱 응답, 관리자 알림과 문자 발송은 실제 연결을 사용했습니다. 실제 위험·사망 검출을 입증하지 않습니다.
- ESP32의 감시 준비가 중간에 끊긴 SENSOR_LOST 구간도 있었습니다. 정상 응답 성공이 연속 센서 감시 성공을 뜻하지 않습니다.
- 현장 주소와 진입 방법은 시험용 미등록 문구입니다. 실제 현장 위치 브리핑 정확도를 검증한 자료가 아닙니다.
- 캡처 원본을 보존했습니다. API 키·로그인 토큰·개인 음성 녹음은 포함하지 않았습니다. 등록한 시험 전화번호는 실제 문자 기록과 화면에 포함돼 있습니다.

## 파일 목록

''' + '\n'.join(f'- `{name}`: {title} — {source}' for name, title, source in items) + '\n'
(out / 'README.md').write_text(readme, encoding='utf-8')

cards = ''.join(f'<figure><a href="{name}"><img src="{name}" alt="{html.escape(title)}" loading="lazy"></a><figcaption><strong>{html.escape(title)}</strong><span>{html.escape(source)}</span></figcaption></figure>' for name, title, source in items)
page = f'''<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>StillWatch 상황별 검증 자료</title>
<style>body{{margin:0;background:#f4f7f7;color:#183b42;font:16px/1.7 system-ui,sans-serif}}main{{max-width:1100px;margin:auto;padding:32px 20px}}h1{{font-size:32px;line-height:1.3}}h2{{font-size:23px}}p{{max-width:900px}}.note,.facts{{padding:20px;border-radius:16px;background:white;margin:20px 0}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:20px}}figure{{margin:0;background:white;border-radius:16px;overflow:hidden;box-shadow:0 3px 15px #183b4210}}img{{display:block;width:100%;height:490px;object-fit:contain;background:#fff}}figcaption{{padding:16px}}span{{display:block;font-size:13px;color:#5b7377}}a{{color:#245e69}}video{{width:min(100%,370px);max-height:650px;background:#000;border-radius:12px}}table{{border-collapse:collapse;width:100%}}td,th{{text-align:left;border-bottom:1px solid #e4ecec;padding:10px}}code{{overflow-wrap:anywhere}}</style>
<main><h1>StillWatch · 상황별 캡처</h1><p>2026년 10월 9일 · 원본 사진 12장 / 실제 작업자 휴대폰 녹화 / 실제 AWS 사건 기록</p>
<div class="note"><strong>자료의 출처</strong><p>작업자 화면은 실제 Galaxy S23에서 촬영했습니다. 관리자 사진은 별도 Android 에뮬레이터에서 같은 AWS 사건을 조회한 화면입니다. 실제 관리자 태블릿의 알림과 시험 문자는 사용자가 수신을 확인했습니다.</p><p>움직임 미감지 시작 조건은 가상 입력입니다. 이번 연락은 승인된 시험 번호로 보냈습니다.</p></div>
<h2>정상 확인 요청 → 응답 → 자동 연락 취소</h2><p>실제 앱의 ‘괜찮아요’ 버튼을 Codex가 눌러 응답했습니다. 영상은 무음이며 약 2분 6초입니다.</p><video controls preload="metadata" poster="10_worker_normal_request.png"><source src="normal-response.mp4" type="video/mp4"></video><p><a href="normal-response.mp4">원본 영상</a> · <a href="normal-response-record.json">정상 응답 서버 기록</a></p>
<h2>관리자 무응답 → 자동 시험 문자</h2><div class="facts"><table><tr><th>단계</th><th>KST 시각 / 결과</th></tr><tr><td>작업자 응답 마감</td><td>{automatic['workerDeadlineKst']}</td></tr><tr><td>실제 관리자 태블릿 알림 수신</td><td>{automatic['managerNotificationReceivedAtKst']}</td></tr><tr><td>관리자 60초 응답 마감</td><td>{automatic['managerDeadlineKst']}</td></tr><tr><td>AWS 발송 수락</td><td>{automatic['sms']['acceptedAtKst']}</td></tr><tr><td>실제 알림·문자 수신</td><td>사용자 확인 완료</td></tr></table><p><a href="automatic-contact-record.json">자동 연락 원본 기록·실제 문자 내용</a></p></div>
<h2>앱 화면과 상황별 사진</h2><div class="grid">{cards}</div><p><a href="README.md">시험 범위와 파일 설명</a> · <a href="manifest.json">원본 파일 해시</a></p></main></html>'''
(out / 'index.html').write_text(page, encoding='utf-8')

names = [name for name, _, _ in items] + ['normal-response.mp4', 'normal-response-record.json', 'automatic-contact-record.json', 'README.md', 'index.html']
manifest = []
for name in names:
    path = out / name
    if path.suffix == '.png':
        with Image.open(path) as picture:
            picture.verify()
    data = path.read_bytes()
    manifest.append({'file': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
(out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
archive = root / 'releases/StillWatch-Scenario-Captures-2026-10-09.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as bundle:
    for name in names + ['manifest.json']:
        bundle.write(out / name, 'StillWatch-Scenario-Captures/' + name)
with zipfile.ZipFile(archive) as bundle:
    if bundle.testzip() is not None: raise RuntimeError('ZIP validation failed')
print(json.dumps({'screenshots': len(items), 'videos': 1, 'files': len(names)+1, 'zipBytes': archive.stat().st_size, 'zip': str(archive)}, ensure_ascii=True))
