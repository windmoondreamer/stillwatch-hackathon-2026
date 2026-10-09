# StillWatch · 2026 Codex 해커톤

움직임이 감지되지 않는 단독 작업자에게 음성으로 상태를 확인하고, 정상 확인이 없으면 관리자에게 알린 뒤 등록된 시험 연락처로 연락하는 시제품입니다. ESP32의 움직임 신호, Android 작업자·관리자 앱, AWS 상태 관리, OpenAI 음성 해석과 연락 문안 작성을 연결합니다.

## 현재 개발분

| 폴더 | 내용 |
|---|---|
| [`stillwatch-android`](stillwatch-android/PILOT.md) | Android 0.3.0 앱, AWS 백엔드·배포 템플릿, 상태·응답·연락 흐름 테스트 |
| [`stillwatch-voice`](stillwatch-voice/README.md) | 로컬 Node.js 대시보드, 브라우저 음성 확인, ESPectre·MQTT 연결, 초기 작업자 단말 |
| [`voice-background-probe`](voice-background-probe/README.md) | Android 화면 잠금·백그라운드 음성 동작을 확인한 진단 앱 소스 |
| [`STILLWATCH_PLAN.md`](STILLWATCH_PLAN.md) | 시스템 구성과 확인·연락 절차 설계 |

현재 구현 상태는 Android의 [PILOT.md](stillwatch-android/PILOT.md)를 기준으로 봐주세요. 로컬 대시보드 문서와 기존 UI 0.2.0 문서는 각 버전의 개발 기록입니다.

## 실행과 검증

### Android / AWS

JDK 17 이상, Android SDK 35, Node.js 22 이상이 필요합니다.

```powershell
cd stillwatch-android
Copy-Item aws-config.example.properties aws-config.properties
# aws-config.properties에 본인의 배포 결과를 입력합니다.
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
node --test aws/domain.test.mjs aws/flow.test.mjs aws/native.test.mjs aws/s3-sign.test.mjs
```

관리자 앱과 작업자 시험 앱을 함께 설치하려면 작업자 빌드에 `-PworkerPilot=true`를 사용합니다. AWS 배포와 ESP32 설정은 [PILOT.md](stillwatch-android/PILOT.md)를 참고합니다. 앱의 로그인에는 해당 배포의 Cognito 계정이 필요합니다.

### 로컬 대시보드

```powershell
cd stillwatch-voice
npm ci
Copy-Item .env.example .env
npm test
npm start
```

브라우저에서 `http://127.0.0.1:3210`을 엽니다. AI 연결 없이 동작 시험을 할 수 있으며, 실제 음성 기능에는 본인의 OpenAI API 키와 마이크 권한이 필요합니다.

## 공개 소스에 포함한 정보

2026-10-09 개발 폴더의 별도 사본입니다. 원본 개발·실기기 테스트는 다른 세션에서 계속될 수 있으므로 이후 수정은 별도 커밋으로 반영해야 합니다.

실제 시험 수신번호는 공개 사본 전체에서 `010-0000-0000` / `+821000000000`으로 치환했습니다. **이 값은 발송용 번호가 아닙니다.** 실제 배포 전 승인받은 시험 수신번호를 백엔드의 허용 번호 검사, 기본값, 배포 템플릿과 일치하도록 설정한 뒤 `node aws/build-template.mjs`로 생성물을 갱신해야 합니다. 기존 운영 AWS나 설치된 앱의 설정은 이 사본으로 변경하지 않았습니다.

API 키, AWS 인증정보, 로그인 세션, 개인 장치 설정, 원본 시험 로그, 촬영 자료, APK와 펌웨어 바이너리, 빌드 캐시·설치된 의존성은 포함하지 않습니다. 문서에 나오는 APK·로컬 기록은 별도 개발 환경의 파일이며 이 저장소의 다운로드 파일이 아닙니다. APK는 소스로 빌드할 수 있습니다.

## 검증 범위

정상 응답 시 미발송 연락 취소, 무응답 시 관리자 알림과 시험 문자 전달을 검증했습니다. 실제 ESP32의 안정적인 연속 CSI 입력과 현장 감지 정확도는 추가 검증이 필요합니다. 실제 119 신고 접수·출동 연동은 구현·검증하지 않았습니다. 세부 시험 결과와 제한은 각 프로젝트 문서를 참고하세요. 공개 사본의 자동 검증 결과와 대시보드의 기존 테스트 실패 1건은 [VALIDATION.md](VALIDATION.md)에 기록했습니다.

## 외부 구성요소

ESPectre Native 3.0.0을 센서 연결에 사용합니다. `stillwatch-voice/hardware`의 ESPectre 참조·전송 코드는 [Francesco Pace의 ESPectre](https://github.com/francescopace/espectre)에서 가져온 것이며 원본 저작권 표시를 유지합니다. 해당 코드의 GPL-3.0-only 라이선스는 [hardware/LICENSE](stillwatch-voice/hardware/LICENSE)에 포함했습니다. 이 저장소 전체에 새로운 단일 라이선스를 지정하지 않았으며, 외부 코드에는 해당 원본 조건이 적용됩니다.
