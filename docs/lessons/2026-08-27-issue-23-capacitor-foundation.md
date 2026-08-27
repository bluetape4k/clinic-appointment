# Issue #23 Capacitor foundation 구현 lesson

## 결정

- Angular 22 production output을 그대로 Capacitor thin shell의 입력으로
  재사용한다.
- `@capacitor/core`, `@capacitor/cli`, `@capacitor/ios`, `@capacitor/android`는
  모두 `8.5.0`으로 고정했다.
- `capacitor.config.ts`의 `webDir`는 Angular application builder가 실제로
  만드는 `dist/appointment-frontend/browser`로 고정했다.
- API origin, CORS, cookie·CSRF, native storage, PWA와 typed bridge는 새로
  만들지 않고 각각 #430·#431·#26·#25·#27·#24의 후속 책임으로 남겼다.
- Capacitor가 생성한 Android 예시 테스트의 package와 기대 application ID를
  `io.bluetape4k.clinic.appointment`에 맞춰 foundation이 잘못된 template
  계약을 전파하지 않도록 했다.
- frontend `package.json`에 Capacitor CLI의 Node `>=22.0.0` prerequisite를
  `engines.node`로 선언하고 두 README에 Node 22 toolchain 기준을 노출했다.
- Android WebView/auth 데이터가 backup·restore로 복원되지 않도록
  `allowBackup=false`, legacy/modern backup rule을 추가하고 FileProvider root를
  앱 전용 `Pictures/` 경로로 축소했다. 선택적 `google-services.json`이 없을 때만
  plugin을 건너뛰고 malformed 설정 오류는 숨기지 않도록 Gradle 조건을 고정했다.

## 재현 명령과 결과

작업 경로는 `frontend/appointment-frontend`다.

| 명령 | 결과 |
|---|---|
| `npm ls @capacitor/core @capacitor/cli @capacitor/ios @capacitor/android --depth=0` | 네 package 모두 `8.5.0` |
| `npm run build` | 성공, initial `624.18 kB`, lazy chunk 생성 |
| `npx cap add ios` | 성공, `ios/App/App/public`에 bundle 복사 |
| `npx cap add android` | 성공, `android/app/src/main/assets/public`에 bundle 복사 |
| `npm run cap:sync` | 성공, Angular bundle과 Capacitor config 동기화 |
| `npx cap sync` 재실행 | 성공, 두 번째 실행도 동일한 asset 경계 유지 |
| `npx cap config` | appId·appName·webDir read-back 성공 |
| `npx cap doctor` | iOS·Android 모두 `looking great` |
| `npm test -- --watch=false` | 45 files, 327 tests 통과 |
| `npx tsc --noEmit -p tsconfig.app.json` | 성공 |
| `npm run test:e2e` | Chromium 10개 통과 |
| `npm install --package-lock-only --ignore-scripts` | `engines.node`가 lockfile root에 반영됨 |
| `xmllint --noout` (Android manifest/backup rules/FileProvider XML) | 성공 |
| `git check-ignore -v` (signing/Google service files) | `.jks`, `.keystore`, `google-services.json` 무시 |
| `git diff --check` | 성공 |
| Korean terminology audit | README 2개와 plan/review 모두 findings 0 |
| `npm audit --omit=dev --audit-level=moderate` | runtime dependency 취약점 0 |

## 검증 경계와 미해결 항목

- 현재 호스트는 `node v26.7.0`, `npm 11.19.0`이며 저장소 Gradle toolchain은
  Node 22.22.3/npm 11.12.0이다. Capacitor CLI의 Node `>=22.0.0` 조건은
  만족하지만, 최종 CI 증거는 고정된 Node 22 환경에서 다시 수집해야 한다.
- `xcodebuild`는 full Xcode가 아닌 Command Line Tools만 활성화되어 실패했고,
  `adb`, `sdkmanager`, `xcrun simctl`은 호스트에 없다. 따라서 이번 결과는
  platform 생성·config·sync와 browser 계약까지이며 native build나 실기기
  smoke의 증거가 아니다. 해당 검증은 #24의 SDK/CI 환경에서 수행한다.
- `npm run docs:verify`는 기존 source contract 세 건(`tenant-scoped portal
  client URL`, `tenant-scoped patient auth URL`, `legacy staff unscoped
  appointment URL`)을 찾지 못해 실패했다. 이 branch는
  `frontend/appointment-frontend/src`를 변경하지 않았고, 잔여 tenant-aware
  직원 계약은 #295 범위이므로 #23에서 임의로 수정하지 않는다.
- 전체 `npm audit`는 Capacitor CLI의 `xcode`가 `uuid <11.1.1`을 끌어오는
  moderate 3건을 보고한다. runtime audit은 0건이며, audit이 제안하는
  `--force` downgrade는 고정한 CLI 호환선과 재현성을 훼손하므로 적용하지
  않았다. runtime 취약점이 없고 CLI는 개발 전용이므로 이 slice에서는 수용하며,
  다음 dependency maintenance 때 upstream 갱신 가능성을 다시 평가하는 P2 보류
  항목으로 기록한다.

## 재사용 원칙

이번 slice에서 새로 만든 것은 Capacitor가 기존 Angular 산출물을 소비하기
위한 경계뿐이다. 기존 route lazy loading, responsive shell,
`TenantApiClient`, patient cookie/XSRF, workforce Bearer scope와 browser test를
변경하지 않았다. native API를 직접 호출하거나 인증 토큰을 native storage로
복제하는 구현은 후속 typed boundary가 생길 때까지 금지한다.
