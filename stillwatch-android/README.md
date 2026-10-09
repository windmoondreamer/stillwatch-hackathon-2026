# StillWatch Android · AWS 음성 통합 0.3.0

**현재 설치 파일·AWS 배포·실기기 검증·사용 방법은 [PILOT.md](PILOT.md)를 확인하세요.**

아래는 사용자 제공 ZIP의 기존 0.2.0 안내입니다. 배포 전/체험 모드에 관한 설명은 당시 기록이며, 현재 상태는 위 문서가 기준입니다.

---

## 기존 UI 0.2.0 안내

야간에 실내 설비 구역에서 혼자 점검하는 작업자와, 다른 곳에 있는 안전관리자를 위한 앱입니다. ESP32 움직임 정보와 등록 휴대폰의 Wi-Fi 연결 상태를 각각 관리합니다. 휴대폰 연결은 재실 추정이며, 실제 사람의 위치를 증명하지 않습니다.

## 바로 실행

`StillWatch-UI-updated.apk`를 Android 8.0 이상 휴대폰에 설치합니다. 개발용 APK이므로 설치 출처 허용이 필요할 수 있습니다. 시작 화면에서 **안전관리자** 또는 **작업자** 체험을 선택합니다. 체험 데이터와 실제 현장 알림을 화면에서 구분합니다. 체험 변경 사항은 앱을 종료하면 사라집니다.

- 관리자: 홈에서 사건 확인 → 알림 상세 → 관리자 확인 → 현장 확인 결과를 적고 종료.
- 작업자: 홈에서 **본인 확인 요청 열기** → **괜찮아요** 응답. 관리자는 이 응답을 대신할 수 없습니다.
- 도면: 관리자 **현장 도면·움직임 기록** 또는 **설정 → 대피 안내**에서 PDF·PNG·JPEG 선택 → 위치·경로 편집 → 확인 후 저장. 최대 10MB, PDF는 첫 페이지를 표시합니다. 도면을 누르면 ESP32 위치와 주·대체 대피 경로 점을 지정합니다.
- 설정: 구역·대피·장치·연락 대상·확인 시간 섹션에서 현장 Wi-Fi 이름·선택적 BSSID, 움직임 미감지 시간 T1, 본인 응답 대기 시간 T2, 구역 안내를 저장합니다. 체험에서 감시 중·본인 확인·현장 확인·감시 불가를 전환할 수 있습니다.
- 휴대폰: 작업자는 로그인 후 본인 이름으로 휴대폰을 등록합니다. 관리자는 등록 목록에서 구역의 작업자 휴대폰을 지정합니다. 작업자 앱의 **Wi-Fi 연결 확인 켜기**를 최초 한 번 실행하면 연결 상태를 자동 전송합니다. 매 작업 시 수동 입장 버튼은 없습니다.

## 실제 연결 구성

`ESP32 → AWS IoT Core → Lambda → DynamoDB → FCM → Android`

도면 파일은 비공개 S3에, 위치·경로와 휴대폰 지정은 DynamoDB에 저장합니다. 앱 로그인은 Cognito 공개 클라이언트의 OAuth PKCE를 사용합니다. AWS 프로젝트의 팀 멤버와 앱 작업자·관리자 로그인은 별개입니다. 앱 역할은 Cognito `MANAGER` / `WORKER` 그룹으로 서버에서 정합니다.

작업자 휴대폰은 앱이 확인한 Wi-Fi 상태를 약 15초마다 전송합니다. 45초 동안 갱신되지 않으면 휴대폰 상태를 **미확인**으로 취급합니다. SSID를 읽으려면 Android 위치 권한과 위치 서비스가 필요할 수 있습니다. GPS 좌표는 수집하지 않습니다. 백그라운드 확인 중에는 지속 알림을 표시합니다. 전원 관리·권한 철회·강제 종료 시 실제 기종에서 별도 시험이 필요합니다.

유효한 센서 정보가 없으면 움직임 없음으로 판단하지 않고 **감시 불가**로 표시합니다. 기본 센서 시간 제한은 30초입니다. T1 후 작업자 확인 요청을 생성하고 T2 무응답이면 관리자 경고를 생성합니다. 휴대폰 연결 해제나 센서 복구는 진행 중인 현장 확인을 자동 종료하지 않습니다. 서버의 보조 점검은 1분 간격이므로 센서가 끊긴 경우 판정·재시도에 최대 약 1분의 추가 지연이 있습니다.

## 소스 빌드

Android Studio에서 이 폴더를 열고 JDK 17 이상, Android SDK API 35를 준비합니다. SDK 경로는 Android Studio가 `local.properties`에 만듭니다. PowerShell에서:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Node.js 22 이상에서 서버 검사와 템플릿 재생성:

```powershell
node --test aws/domain.test.mjs aws/s3-sign.test.mjs
node aws/build-template.mjs
```

## AWS 배포 준비

선택된 프로젝트는 **Array of Sunshine**, CLI 프로필은 **aws-dev**, 리전은 **ap-southeast-2 (Sydney)** 입니다. 이 버전에서는 실제 리소스를 생성하지 않았습니다. `aws/template.json`은 25개 리소스를 생성하는 CloudFormation 템플릿입니다. `aws/deploy.ps1`을 실행하면 실제 리소스와 비용이 발생합니다. 데이터 테이블·도면 버킷·Cognito 사용자 풀은 스택을 삭제해도 보존하도록 설정했습니다.

1. AWS CLI 로그인과 AWS Settings의 결제·지출 상태를 확인합니다.
2. 실제 Android 푸시를 사용할 Firebase 프로젝트의 앱 `kr.stillwatch.app`을 등록합니다. Firebase 공개 설정은 `aws-config.example.properties`를 복사한 `aws-config.properties`에 기입합니다. 서비스 계정의 비공개 JSON은 APK나 Git에 넣지 않고 Sydney의 Secrets Manager에 보관합니다.
3. 아래 배포 명령을 실행하면 API·Cognito 공개 설정이 `aws-config.properties`에 저장됩니다. Firebase 비밀 정보가 아직 없으면 해당 인수를 생략할 수 있지만 푸시는 전송되지 않습니다.

```powershell
.\aws\deploy.ps1 -StackName stillwatch-dev -Profile aws-dev -FirebaseSecretName 'existing-secret-name'
```

4. 생성한 Cognito 사용자 풀에서 관리자·작업자를 초대하고 각각 `MANAGER`·`WORKER` 그룹에 넣습니다. `aws-config.properties`의 Firebase 공개 설정을 채우고 APK를 다시 빌드합니다.
5. 실제 휴대폰 로그인·등록 → 관리자 구역 지정 → 작업자 Wi-Fi 연결 확인 활성화 → ESP32 신호 수신 → 작업자·관리자 푸시를 순서대로 시험합니다.

## ESP32 인터페이스

앱의 현장 Wi-Fi 설정은 Espressif Android provisioning SDK의 **SoftAP / Security 1**을 사용합니다. ESP32 펌웨어가 ESP-IDF 프로비저닝과 장치별 PoP를 지원해야 합니다. 휴대폰을 장치의 설정 Wi-Fi에 연결한 뒤, 앱에서 장치 확인 → 장치가 검색한 현장 Wi-Fi 선택 → 비밀번호 전달을 수행합니다. Wi-Fi 비밀번호는 앱 설정에 저장하지 않습니다. 이 작업은 AWS 서버가 대신하지 않습니다.

ESP32가 IoT Core로 신호를 전송하려면 펌웨어에 별도의 장치 인증서·개인 키·IoT 엔드포인트 설정이 필요합니다. 개인 키를 소스에 공유하지 않습니다. MQTT 클라이언트 ID는 템플릿의 DeviceId와 같아야 합니다. 인증서를 생성한 Thing과 전용 IoT 정책에 연결해야 합니다.

- MQTT topic: `stillwatch/telemetry/stillwatch-room-01`
- 페이로드: `aws/telemetry.example.json` 참고. 실제 UTC 측정 시각, 부팅별 bootId, 증가하는 seq, valid, motion, motionScore를 보냅니다.
- 권장 시험 전송 간격: 5초 이하. 움직임 판정·CSI 수집·노이즈 보정은 ESP32 펌웨어의 책임이며 이 소스는 펌웨어를 변경하거나 플래시하지 않습니다.

## 구현 범위와 검증 한계

포함: Android 화면·수동 도면 확인 편집·작업자 휴대폰 등록/지정·Wi-Fi 상태 전송·Cognito 연결 코드·FCM 수신/전송 코드·S3 도면 API·AWS 배포 템플릿·상태 판정 테스트.

아직 실제 연결 시험 전: ESP32 하드웨어 프로비저닝/CSI 감지, 실제 프로젝트 배포, Cognito 로그인, S3 업로드, FCM 푸시, 휴대폰의 장시간 백그라운드 Wi-Fi 확인. 현재 APK에는 실제 AWS/Firebase 연결 설정이 없습니다.

미구현: 도면의 방·출구 자동 인식, 다중 구역/층·PDF 페이지 선택, 인증 없는 대피도 안내 페이지, Wi-Fi 캡티브 포털, 배포용 서명·스토어 출시, 기기 재부팅 후 자동 감시 재개. 가스 센서와 정확한 사람 위치 측정은 포함하지 않습니다. 일산화탄소 사고 예방·진단 장치로 표현하지 않습니다.

디자이너 UI 반영 내용은 `UI-UPDATE.md`, 검증 결과는 `QA.md`를 참고합니다. 테스트용 디버그 앱이며 현장 안전 설비로 사용하기 전에는 실제 조건에서 전체 연결 시험이 필요합니다.
