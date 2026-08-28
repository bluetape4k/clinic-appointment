# Issue #24 iOS/Android WebView 통합 검증 설계

## 문제와 목표

Epic #13의 마지막 slice로, #23·#430·#431·#26·#25·#27에서 고정한 Capacitor
identity, API/인증, lazy route, 모바일 viewport, PWA, typed deep-link bridge가 실제
iOS WKWebView와 Android WebView에서도 같은 결과를 내는지 증명한다. 브라우저의
Playwright mobile profile은 빠른 회귀 신호로 사용하되 native 검증으로 승격하지 않는다.

이번 slice의 목표는 다음 네 가지다.

1. iOS와 Android 프로젝트를 exact commit에서 재현하고 platform/toolchain/build 결과를
   CI artifact에 남긴다.
2. 두 플랫폼에서 앱 launch와 `io.bluetape4k.clinic.appointment://open/...` deep-link
   intent를 smoke 수준으로 확인한다.
3. 기존 `TenantApiClient`, `API_AUTH_SCOPE`, patient cookie/XSRF, workforce Bearer,
   `TenantContextService`, Safe Area와 lazy route 계약을 browser/mobile profile 및
   native static/build 경계에서 재사용한다.
4. 현재 호스트에 full Xcode/Android SDK가 없을 때 브라우저 성공을 native PASS로
   오인하지 않고, CI 또는 전용 호스트 결과가 없으면 Issue #24를 PENDING으로 남긴다.

## 현재 근거와 제약

| 근거                                    | 확인 결과                                                                                                                       |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `frontend/appointment-frontend/android` | Capacitor Android project, `MainActivity`, manifest deep-link filter, Gradle wrapper가 존재한다.                                |
| `frontend/appointment-frontend/ios/App` | Capacitor iOS project와 SPM package, `Info.plist` URL scheme이 존재한다.                                                        |
| `frontend/appointment-frontend/src/app` | 기존 Angular route, auth/session, viewport, PWA, typed native bridge를 그대로 재사용한다.                                       |
| `frontend/appointment-frontend/e2e`     | Playwright는 현재 Chromium project만 있고 native 대체가 아니다.                                                                 |
| 현재 macOS                              | `xcodebuild`는 CommandLineTools만 활성화되어 full Xcode가 없고, `adb`·`sdkmanager`·iOS simulator device도 없다.                 |
| CI                                      | 기존 `pull_request` job은 `develop` base에만 자동 실행되므로 stacked feature base에서는 exact ref workflow dispatch가 필요하다. |

## 선택한 접근

### A안 — 플랫폼별 CI build/smoke workflow + 로컬 browser contract (채택)

`.github/workflows/native-webview-ci.yml`을 canonical workflow로 두고 workflow dispatch의
`ref`를 exact commit으로 고정한다. 이 workflow가 기본 브랜치에 등록되기 전에는 동일한
native job을 포함한 feature ref의 `frontend-ci.yml` `workflow_dispatch`를 compatibility
경로로 사용한다. Android job은 Node/npm으로 `cap sync`한 뒤 hosted Ubuntu의 Android SDK와
Gradle wrapper로 debug APK를 만들고 emulator에서 설치·launch·custom-scheme intent를
수행한다. iOS job은 hosted macOS에서 `cap sync` 후 `xcodebuild`로 simulator
build/install/launch/openURL smoke를 수행한다. 각 job은 platform, toolchain, commit,
command, 결과를 하나의 artifact manifest로 업로드한다.

로컬에서는 `native:environment` probe가 toolchain/runner 상태를 구조화해 출력하고,
Playwright는 iPhone/Pixel device profile로 route, cookie/CSRF 전송, Safe Area, keyboard
focus, lazy route, overflow를 검증한다. 이 결과는 native job과 별도로 기록한다.

### B안 — 로컬에서 SDK를 설치해 실기기만 검증

개발자 장비에 full Xcode, Android SDK와 emulator를 설치하면 즉시 확인할 수 있지만,
환경이 개인 장비에 종속되고 exact commit·toolchain artifact가 남지 않는다. 대규모
시스템 변경과 라이선스/디바이스 준비가 필요한 현재 호스트에서는 채택하지 않는다.

### C안 — Playwright mobile profile만으로 완료

빠르고 재현 가능하지만 browser engine은 WKWebView/Android WebView의 cookie, intent,
process lifecycle과 다르다. Issue #24의 명시적 native smoke 조건을 충족하지 못하므로
대체 수단으로 채택하지 않는다.

## 구성 요소와 데이터 흐름

1. `npm run cap:sync`가 Angular production output을 iOS/Android `public`/assets에
   복사한다.
2. CI가 `GITHUB_SHA`와 입력 `ref`를 비교하고, platform toolchain 버전과 build 결과를
   `native-webview-manifest.json`에 기록한다.
3. Android runner는 APK를 emulator에 설치하고 앱 package를 launch한 뒤 custom-scheme
   VIEW intent를 URI 단일 호출로 발행한다. iOS runner는 simulator app을 설치하고 launch한 뒤
   `simctl openurl`을 발행한다.
4. smoke 명령은 process/package launch, URL dispatch 명령과 exit status를 증명한다.
   backend 인증 성공을 임의로 주장하지 않으며, browser fixture와 API contract는
   기존 Playwright/Angular 테스트에서 별도로 증명한다.
5. 어떤 toolchain이든 없거나 command가 실패하면 job이 성공으로 위장되지 않고 artifact와
   로그를 남긴 채 실패한다. 로컬 probe는 `available=false`를 반환한다.

## 실패 모드와 안전 경계

| 실패 모드                            | 방지/관측 동작                                                                                                               |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| exact ref와 checkout SHA 불일치      | workflow 첫 단계에서 `git rev-parse HEAD`와 입력 ref를 비교하고 즉시 실패한다.                                               |
| `cap sync`가 stale web assets를 사용 | sync 전 production build, 후 manifest hash와 `public/index.html` 존재를 검사한다.                                            |
| Android emulator가 boot되지 않음     | emulator action timeout과 `adb devices`를 artifact로 남기고 job을 실패시킨다.                                                |
| iOS simulator destination/SDK 불일치 | `xcodebuild -showdestinations`와 `xcrun simctl list`를 기록하고 build/openURL 실패를 그대로 반환한다.                        |
| browser 결과를 native PASS로 오인    | workflow job과 Playwright project를 분리하고 문서/PR DoD에서 각각의 결과를 별도 집계한다.                                    |
| cookie/CORS/CSRF 정책 drift          | 기존 `TenantApiClient`·interceptor 계약과 Playwright request assertions를 재사용하고 새 native storage 경로를 만들지 않는다. |
| 현재 호스트에 toolchain 없음         | `native:environment` probe와 CI handoff를 PENDING으로 기록하며 Epic closeout을 진행하지 않는다.                              |

## 호환성과 범위

- Capacitor core/CLI/iOS/Android `8.5.0`, `@capacitor/app` `8.1.1`, Angular 22,
  Node 22, Java 25의 현재 catalog를 유지한다. 새 runtime dependency는 추가하지 않는다.
  Android hosted Gradle smoke만 parser 호환성을 위해 Java 21 toolchain을 사용하고,
  저장소의 일반 JVM/production 계약은 변경하지 않는다.
- Android는 기존 `io.bluetape4k.clinic.appointment` package와 `custom_url_scheme`
  manifest를 사용한다. iOS는 기존 `App` scheme과 `CFBundleURLTypes`를 사용한다.
- backend endpoint, auth model, patient JWT/cookie 저장 정책, route 구조, native UI
  재작성은 변경하지 않는다.
- Kotlin production/test source는 변경하지 않는다. `$bluetape-kotlin-patterns`의
  Kotlin checklist와 `bluetape4k-assertions` 회귀 경계는 `:appointment-api:build`
  및 기존 contract test로 확인한다.

## 수용 기준

1. exact commit에서 Android debug build와 Android emulator launch/deep-link smoke가
   성공하거나, 환경 부재/실패가 정확한 artifact로 남는다.
2. exact commit에서 iOS simulator build와 launch/openURL smoke가 성공하거나,
   환경 부재/실패가 정확한 artifact로 남는다.
3. Playwright iPhone/Pixel profile에서 320px 이상 overflow 없음, keyboard focus,
   Safe Area, lazy route, deep-link/browser bridge, API credentials/CSRF 계약을
   검증한다.
4. platform/toolchain/commit/test result manifest가 CI artifact에 업로드된다.
5. frontend unit/contract, TypeScript, production build, browser E2E, native static,
   `git diff --check`가 통과한다.
6. native runtime 증거가 없으면 완료 조건을 체크하지 않고 Issue #24와 Epic #13을
   PENDING으로 유지한다. 이번 exact head에서는 hosted iOS/Android report가 모두
   `result=passed`이고 report commit이 checkout SHA와 일치한다.

## DoD와 후속

- DoD: workflow, environment probe, mobile browser contract, native static/build/smoke
  evidence, Korean README/spec/plan/review/lesson, 7-Tier review, exact-head CI와
  Issue/PR read-back을 모두 기록한다.
- 잔여 운영 경계: standalone workflow를 기본 브랜치에 등록하면 canonical `ref`와
  `expected_sha` dispatch로 전환한다. Epic #13 merge/closeout은 모든 child 완료 후
  별도 fresh approval에서만 수행한다.
- 제외: push notification, offline mutation queue, native auth/cookie storage, 새
  backend endpoint, Ionic 등 framework 교체.
