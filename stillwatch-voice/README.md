# StillWatch · 움직임 정지 후 AI 음성 확인

`18_DevDay_Codex해커톤.zip`의 `사전준비/Codex_당일스펙.md`, 실행 계획과 ESPectre 준비 자료를 바탕으로 재구축했습니다. 압축파일에는 완성된 대시보드 코드가 없어 기존 로컬 음성 시제품에 출입·기준선·그래프·MQTT·사건 보관 기능을 구현했습니다. 추출한 원본 자료는 상위 폴더 `devday-source`에 있습니다.

## 실행

Node.js 22 이상이 필요합니다. 처음 복사한 PC에서는 이 폴더에서 `npm ci`를 실행하고 `./start.ps1` 또는 `npm start`로 시작하세요. 이 PC는 의존성이 설치되어 있습니다. [로컬 대시보드](http://127.0.0.1:3210/)로 접속합니다.

서버는 PC 내부 주소에만 바인딩합니다. ESPectre에는 PC의 수집기가 연결합니다. ESP에서 `127.0.0.1:3210`으로 직접 보내는 구조가 아닙니다.

## 키 없이 전체 흐름 시험

1. **동작 시험 · AI 연결 없음**과 **화면의 시험 버튼**을 선택하고 **입장 등록 · 작업 시작**을 누릅니다. 한 명이 등록됩니다.
2. **움직임 정지**를 누르면 기본 10초 정지 관찰 뒤 응답 확인이 시작됩니다. 기본 응답 제한시간은 25초입니다. 빠른 시연은 정지 2초·응답 10초로 설정하고 기준선 관찰을 끄세요.
3. 일반·모호한 시험 응답은 기록만 남깁니다. **괜찮습니다 · 응답 버튼**으로 확인합니다.
4. **도와주세요** 또는 제한시간 종료는 담당자 화면에 확인 요청을 등록합니다. **담당자 확인**으로 처리합니다.
5. **퇴장 −1**로 등록 인원이 0명이 되면 EMPTY가 됩니다. 이 상태에서 움직임 평가가 3회 연속 들어오면 미등록 출입 확인을 기록하며 구조 경보는 발생시키지 않습니다.
6. 이력에서 사건별 대화·신고 참고 초안·원본 사실을 열어보거나 JSON으로 내려받습니다.

시험 응답 분류는 지정된 값이며 AI 추론이 아닙니다. 시험 그래프의 0.85/0.08 점수도 시각화용 예시 값입니다. 실제 센서 모드에서는 센서가 제공한 점수만 표시합니다.

## 실제 AI 음성

왼쪽 **실제 AI 음성 연결 설정**을 열고 API 키를 입력·저장하세요. 키는 이 PC의 `.env`에 저장하며 브라우저로 다시 반환하지 않습니다. `.env`의 `OPENAI_API_KEY`에 직접 입력한 뒤 연결 설정을 새로고침해도 됩니다.

**실제 AI 음성 대화**를 선택하고 작업을 시작하면 마이크 권한을 요청합니다. 노트북 마이크·스피커를 사용합니다. 평소에는 오디오 트랙이 꺼져 있고, 정지 확인 중에만 WebRTC로 OpenAI Realtime에 연결합니다. 실제 API 시험 시 응답 제한시간을 25초 이상으로 두면 연결·질문·응답 시간을 확보하기 쉽습니다.

AI는 “움직임이 감지되지 않습니다. 도움이 필요하신가요?”라고 묻고 실제 발화를 해석합니다.

| 발화 분류 | 다음 동작 |
|---|---|
| responded · 작업 상황 설명 | 발화 기록, 확인 버튼 안내 |
| unclear · 의미가 모호함 | 짧게 추가 질문, 제한시간 유지 |
| help_requested · 명시적 도움 요청 | 담당자 화면에 즉시 등록 |

Realtime 모델 기본값은 `gpt-realtime-2.1`, 전사 모델은 `gpt-4o-mini-transcribe`입니다. `.env`의 `OPENAI_REALTIME_MODEL`로 음성 모델을 변경할 수 있습니다. 실제 모드의 경보에는 Responses API로 사실 기반 요약·신고 참고 초안도 요청합니다. 기본 보고 모델은 `gpt-4.1-mini`이며 `OPENAI_REPORT_MODEL`로 변경할 수 있습니다. 요청은 사건당 한 번, 최대 3초입니다. JSON Schema와 원본 필드 일치 검사를 통과하지 못하거나 API가 실패하면 기본 문구를 유지합니다. 요약 문장은 실제 신고 전 사람이 검토해야 합니다.

마이크 권한을 준비한 탭을 유지해야 합니다. 브라우저나 서버가 종료되면 감시·음성 확인이 중단됩니다. 현재 실제 API 키는 설정되지 않아 실제 인식·발화·소음 환경의 정확도는 검증하지 못했습니다.

## 기준선과 경보 원칙

- 입장·인원 변경 후 유효한 관찰 60초를 수집합니다. WORKING에서의 최대 연속 정지 L에 `clamp(ceil(1.5 × L), 10, 30)`을 적용합니다. 수집 중에도 기본 정지 기준으로 경보가 작동합니다. 연결 단절·응답 확인 시간은 관찰 60초에서 제외합니다.
- 이 계산은 임시 규칙이며 학습된 AI 모델이 아닙니다. 여러 명이 등록되면 공간 전체 움직임의 기준선이므로 개인별 상태를 구분할 수 없습니다.
- 장치의 idle은 움직임 미감지이며 부재·쓰러짐·의식 상태를 확정하지 않습니다. 실제 입력이 5초 동안 없거나 센서가 준비되지 않으면 SENSOR_LOST로 표시하며 정지 시계를 초기화합니다.
- 이미 시작한 응답 확인은 센서 단절·AI 실패·추가 질문·움직임 복귀로 해제하거나 연장하지 않습니다. 시작 시 정한 제한시간은 서버가 관리합니다.
- 작업자 버튼은 기한 안의 확인 요청을 처리합니다. 이미 담당자에게 등록한 경보는 담당자 확인으로 처리합니다. 이는 원본 스펙의 움직임 복귀 자동 해제·구조 경보 작업자 해제와 다른 구현 선택입니다. 주변 사람의 움직임만으로 사건이 사라지는 것을 막습니다.
- 담당자 요청은 이 대시보드에 표시됩니다. 외부 SMS·전화·119 신고는 연결하지 않았습니다.

내부 상태 WATCHING/CHECKING/ALERTED는 스펙의 WORKING/STILL_CHECK/RESCUE에 대응합니다. `/api/state`의 phase에서 스펙 이름과 SENSOR_LOST를 확인할 수 있습니다.

## 실제 ESP32와 MQTT

### ESPectre 직접 연결

COM4의 보드는 클래식 ESP32-D0WD-V3·4MB입니다. 공식 ESPectre Native 3.0.0을 설치했고 설치 전 전체 백업은 `hardware/esp32-com4-before-espectre.bin`에 보관했습니다. 압축파일의 RuView 이미지들은 S3/C6용이므로 이 보드에 설치하지 않았습니다.

왼쪽 **ESP32 연결**에서 USB 상태를 확인하고 PC와 통신 가능한 2.4GHz Wi-Fi를 설정하세요. IP를 받아 자동 연결하며 실패하면 IP로 다시 연결합니다. Native Direct API의 TCP 62587과 SSE 이벤트를 사용합니다. 보정 중·감지 비활성화·CSI 수집 중·중복 이벤트는 움직임 데이터로 소비하지 않습니다.

USB 비밀번호는 PC 파일·로그에 저장하지 않지만 ESP32 자체에는 재접속용으로 저장됩니다. PC에는 장치 IP·COM만 `hardware/device.json`에 저장됩니다. USB helper는 pyserial이 필요하며 이 PC에는 `hardware-tools/python`에 설치되어 있습니다. 다른 PC에서는 `STILLWATCH_PYTHON`으로 Python 실행 경로를 지정할 수 있습니다.

**실제 보드에서 안정적인 ready=true와 연속 움직임 이벤트를 확보한 상태는 아닙니다.** USB 연결·펌웨어 설치와 소프트웨어 수집기 검증까지 진행했으며 실제 감지 범위·오탐·센서→음성 전체 흐름은 장치 배치와 네트워크에서 추가 확인해야 합니다. 임시 StillWatch-ESP 핫스폿은 꺼둔 상태입니다.

### MQTT·Tasmota

로컬 또는 사설 IPv4 브로커를 준비하고 **MQTT · 버튼·LED·부저 연결**에 브로커 주소, ESPectre 16자리 장치 ID, Tasmota 토픽을 입력합니다. ESPectre도 같은 브로커로 발행하도록 설정해야 합니다. 기본 Tasmota 토픽은 stillwatch_ctrl입니다.

| 입력·출력 | 처리 |
|---|---|
| espectre/v1/devices/{id}/motion | 준비·온라인 상태에서 최신 비보관 메시지만 소비 |
| .../health, .../sensing | 온라인·보정·준비 여부 확인 |
| stat/stillwatch_ctrl/RESULT | Button1 SINGLE 입장, DOUBLE 퇴장 / Button3 SINGLE 퇴장 / Button2 SINGLE 응답 |
| tele/stillwatch_ctrl/LWT | 컨트롤러 연결 여부 |
| cmnd/stillwatch_ctrl/POWER1, POWER2 | 확인 요청·경보 중 LED·부저 ON, 처리 시 OFF |
| stat/stillwatch_ctrl/POWER1, POWER2 | 명령 응답 확인, 2초 후 한 번 재시도, 실패 표시 |

브로커와 Tasmota 장치는 앱에 포함되어 있지 않습니다. MQTT 어댑터는 구현·자동 검사했지만 실제 MQTT 장비의 LED·부저는 아직 검증하지 못했습니다. 직접 센서 연결과 MQTT 중 한 입력만 감시에 사용합니다. 별도 PC 수집기는 직접 센서 모드에서 `POST /api/sensor`에 `{"motion":false,"score":0.1}`을 전달할 수 있습니다.

## 기록과 검증

사건·대화·이벤트는 `data/events.json`에 원자적으로 저장합니다. 최대 사건 100개·이벤트 500개·전역 대화 80개를 보관하며 사건별 대화는 사건에 함께 남깁니다. 새 작업과 재시작 후에도 이력을 볼 수 있습니다. 재시작은 감시를 자동 재개하지 않으며, 미처리 사건은 중단된 사건으로 표시합니다. 키·대화 기록·장치 설정·펌웨어는 `.gitignore`에서 제외했습니다.

`npm test`는 상태 전이·HTTP·Direct SSE·출입·기준선·사건 보관·보고서 실패·MQTT 준비 조건과 출력 응답 검사를 실행합니다. 이 PC에서 `node test/browser-check.mjs`는 번들 Playwright로 데스크톱·모바일 화면과 사용자 흐름을 확인합니다. 음성 연결 검사는 마이크와 WebRTC를 모의하고 실제 로컬 상태 머신을 사용하므로 실제 AI 음성 품질 시험을 대체하지 않습니다.

평가를 위한 추가 검증은 실제 보드의 연속 감지, 작업자 발화별 분류·지연·실패율, 현장 정지 기준 비교, 물리 출력 응답과 도입 비용 확인입니다. 현재 비용·토큰 절감 수치를 측정한 것으로 주장하지 않습니다. AI 연결은 정지 확인 때만 열고, 보고 요약은 사건당 한 번 요청하도록 구현했습니다.

## 공식 참고

- [Realtime WebRTC](https://developers.openai.com/api/docs/guides/voice-webrtc)
- [Realtime 음성 대화](https://developers.openai.com/api/docs/guides/realtime-conversations)
- [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [MQTT.js](https://github.com/mqttjs/MQTT.js)
- [ESPectre 3.0 API](https://github.com/francescopace/espectre/blob/3.0.0/docs/API.md)
