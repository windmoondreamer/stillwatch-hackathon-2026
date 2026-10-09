# 공개 소스 사본 검증 · 2026-10-09

소스 사본 생성 시각은 **2026-10-09 16:38:59 KST**입니다. 원본 폴더에서 이어지는 개발 변경은 이 사본 이후의 별도 반영 대상입니다. 검증은 원본과 분리된 폴더에서 실행했습니다.

| 확인 | 결과 |
|---|---|
| Android `:app:assembleDebug` | 성공 |
| Android `:app:testDebugUnitTest` | 16개 통과, 실패·오류 없음 |
| Android `:app:lintDebug` | 성공 |
| AWS `domain`, `flow`, `native`, `s3-sign` 테스트 | 46개 통과 |
| 로컬 대시보드 `node --test test/*.test.mjs` | 35개 중 34개 통과, 1개 실패 |
| 공개 파일의 OpenAI 키·AWS 접근키·GitHub 토큰·JWT·개인키·실제 휴대전화번호 패턴 검사 | 발견 없음. 테스트의 명시적인 가짜 키는 예외 처리 |

## 확인된 기존 실패

`stillwatch-voice/test/contact.test.mjs`의 `SMS failure is retained; idle handset and lost device never count as delivery`가 155행에서 `TypeError: Cannot read properties of null (reading 'id')`로 실패했습니다. 이 항목만 원본 개발 폴더에서 다시 실행했을 때에도 같은 오류가 발생했습니다. 공개 사본에는 이 기존 상태를 유지했습니다.

검증 명령:

```powershell
# stillwatch-android
.\gradlew.bat --offline :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
node --test aws/domain.test.mjs aws/flow.test.mjs aws/native.test.mjs aws/s3-sign.test.mjs

# stillwatch-voice
npm ci --ignore-scripts --no-audit --no-fund
node --test test/*.test.mjs
node --test --test-name-pattern="SMS failure is retained" test/contact.test.mjs
```

이 확인은 공개 소스의 빌드·자동 테스트에 관한 결과입니다. 실제 AWS·센서·휴대기기의 검증 범위는 [PILOT.md](stillwatch-android/PILOT.md)를 참고하세요. 운영 환경에 새로 배포하거나 시험 문자를 발송하는 동작은 수행하지 않았습니다.
