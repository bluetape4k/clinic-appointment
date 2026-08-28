# Issue #439 native UI 증거 구현 계획

## 계획 계약

- 독자: 단일 개발자가 native harness와 CI를 유지보수할 때 참고하는 구현 문서
- 승인: 2026-08-28 현재 thread의 `승인, $bluetape-kotlin-patterns 지침을 잘 지켜라`
- 기준: `origin/develop@e6937e02a202106f3927ccf71c47f8f0c38ce952`
- branch/worktree: `feat/issue-439-native-ui-evidence` /
  `.worktrees/issue-439-native-ui-evidence`
- stop condition: report/workflow contract, Android/iOS native test, exact-head CI,
  docs/7-Tier review가 모두 증명되면 PR merge-ready로 멈춘다. 동일한 외부 native
  blocker가 반복되어 안전한 수리가 불가능하면 receipt와 문서를 `BLOCKED`로 수렴하고
  merge는 fresh approval 전까지 수행하지 않는다.

## 구현 순서

### 1. Report schema v2를 RED/GREEN으로 고정

대상: `frontend/appointment-frontend/scripts/create-native-webview-report.test.mjs`,
`create-native-webview-report.mjs`.

1. 기존 v1 입력이 그대로 동작하는 회귀를 먼저 실행한다.
2. `device`, `interactions`, `artifacts`, `schemaVersion=2` 기대를 추가해 RED를
   확인한다.
3. bounded object/list/path, interaction result, forbidden content를 최소 구현한다.
4. v1 입력을 허용할지 결정한다. canonical workflow는 v2를 만들고, validator는 v2
   필수 필드를 검증한다. 기존 v1 fixture는 명시적인 migration test로 유지한다.

### 2. Native workflow contract validator 확장

대상: `validate-native-webview-workflow.mjs`와 test.

- Android 단계에 `connectedDebugAndroidTest`, JUnit XML, screenshot, orientation/
  keyboard evidence와 report 입력을 추가한다.
- iOS 단계에 `xcodebuild test`, `.xcresult`, simulator screenshot과 report 입력을
  추가한다.
- exact SHA checkout/검증, `set -eu`/`set -euo pipefail`, `if: always()`, artifact
  upload와 별도 result enforcement를 유지한다.
- canonical `.github/workflows/native-webview-ci.yml`과 mirrored
  `.github/workflows/frontend-ci.yml`를 동일한 명령·schema로 맞춘다.

### 3. Android instrumentation UI test

대상: `frontend/appointment-frontend/android/app/build.gradle`,
`android/variables.gradle`, `android/app/src/androidTest/...`.

- test-only `espresso-web`와 `uiautomator` stable dependency를 추가한다.
- `ActivityScenarioRule<MainActivity>`로 app을 시작하고 테스트 초기에만 `RESUMED` 상태를
  확인한다. native tap·Espresso·orientation 시퀀스에서는 `ActivityScenario`를 다시
  전환하지 않는다.
- `UiDevice`로 `예약 관리` accessibility node 중심을 실제 tap하고 route title을
  Espresso-Web으로 확인한다.
- 날짜 input focus 후 active element와 visual viewport를 확인하고, portrait→landscape
  전환 뒤 WebView/content bounds 및 overflow를 검사한다.
- 모든 실패는 instrumentation exit와 JUnit XML에 남기며 credential/raw DOM dump는
  report에 넣지 않는다.

### 4. iOS XCTest UI target

대상: `frontend/appointment-frontend/ios/App/App.xcodeproj/project.pbxproj`,
`ios/App/App.xcodeproj/xcshareddata/xcschemes/App.xcscheme`,
`ios/App/AppUITests/AppUITests.swift`.

- `AppUITests` UI testing bundle target, source group, Debug/Release configuration,
  shared scheme을 추가한다.
- `XCUIApplication`으로 app을 실행하고 `links["예약 관리"]`를 tap한다.
- `예약 목록`, `시작일` text field, keyboard query와 native accessibility group frame을
  확인한다.
- portrait/landscape 전환에서 safe-area/content와 accessibility group frame을 확인하고
  테스트 종료 때 orientation을 portrait로 복원한다.

### 5. Artifact/report wiring

- Android: `artifacts/native-android-ui/` 아래 report, JUnit XML, screenshot, APK를
  upload한다.
- iOS: `artifacts/native-ios-ui/` 아래 report, `.xcresult` bundle, screenshot을
  upload한다.
- report의 `device.viewport`는 `adb wm size` 또는 simulator profile의 실제 viewport를
  기록하고, interaction 이름은 고정 enum으로 제한한다.

### 6. 문서·review·lesson

- 이 계획과 설계 문서에 구현 결과·명령·known gap을 read-back한다.
- `docs/superpowers/reviews/2026-08-28-issue-439-native-ui-evidence-7-tier-review.ko.md`
  에 7개 tier와 P0~P3 finding을 기록한다.
- reusable failure/repair가 있으면
  `docs/lessons/2026-08-28-issue-439-native-ui-evidence.md`에 재발 방지 규칙을
  기록하고, 단순히 기존 규칙을 재사용했으면 checklist에 N/A 근거를 남긴다.
- 관련 README/Issue metadata가 stale하면 같은 변경에서 갱신한다.

## 수용 기준 → 검증 명령

| 조건 | 검증 |
|---|---|
| report v2 exact commit/device/interaction/artifact | `npm run test:native:report` |
| workflow fail-closed contract | `npm run test:native:workflow` 및 `actionlint` |
| Angular/browser regression | `npm test -- --watch=false`, `npm run test:e2e` |
| Android UI | `cd android && ./gradlew connectedDebugAndroidTest` (CI) |
| iOS UI | `xcodebuild test -project ios/App/App.xcodeproj -scheme App -destination ...` (CI) |
| docs/terms | `git diff --check`, `audit-korean-terms.mjs` |
| exact-head | workflow dispatch inputs `ref=<head branch>`, `expected_sha=<head SHA>` |

## 위험·rollback

- Android dependency가 test bundle에서만 해석되지 않으면 dependency를 제거하고
  existing smoke로 되돌린 뒤 #439를 PENDING으로 둔다.
- iOS PBX/scheme이 CI에서 parse되지 않으면 target/scheme commit을 revert하고
  report/workflow v2만 유지하지 않는다. native UI acceptance가 미충족이기 때문이다.
- 실제 landscape에서 bottom tab이 desktop breakpoint로 사라지면 breakpoint를
  바꾸지 않고 결과를 report에 기록한다. UX 변경은 별도 issue다.
- API origin/auth failure가 UI shell을 깨면 fake token/endpoint를 추가하지 않고
  test-only network fixture 여부를 별도 설계·승인 대상으로 분리한다.
- AOSP ATD에서 persistent `com.android.phone`가 재시작하며 `system_server` contention과
  `MainActivity STOPPED`를 유발하면 테스트 lifecycle을 더 재시도하지 않는다. 로그·JUnit·
  window hierarchy를 보존하고 외부 runner 환경을 복구한 뒤 fresh exact-head run을 다시
  수행한다.

## 리뷰 포인트

- 7-Tier: 요구사항, architecture/reuse, security, operations, test, documentation,
  integration을 각각 독립적으로 판정한다.
- Kotlin: 변경 파일에 `.kt`가 없으므로 `$bluetape-kotlin-patterns` 구현 row와
  `bluetape4k-assertions` 사용은 N/A다. Android Java test에서 raw assertion을
  bluetape assertion으로 바꾸기 위해 Kotlin dependency를 추가하지 않는다.
- Ecosystem: 기존 Capacitor bundle, Angular routes, report writer, workflow artifact
  경계를 재사용하고 새 production layer를 만들지 않는다.

## 실행 결과 read-back

- report schema v2와 canonical/mirrored workflow contract를 구현했다. 기존 schema v1
  입력 회귀와 v2의 device·interaction·artifact bounds/redaction을 Node test로
  고정했다.
- Android `NativeWebViewUiTest`는 UiAutomator coordinate tap과 Espresso-Web 보조
  검사를 사용하고, iOS `AppUITests`는 built-in XCTest UI target/scheme으로 같은
  상호작용 경계를 확인한다. 첫 hosted iOS run에서 WebView link accessibility frame이
  42px로 관찰되어 link glyph frame을 target 판정에서 분리하고 `role="group"` wrapper의
  44px frame과 실제 tap을 확인하도록 보정했으며, 두 harness 모두 production runtime
  dependency를 늘리지 않는다. Android의 중복 `ActivityScenario` 전환은 제거했지만
  exact-head run `33204869723`에서도 AOSP 시스템 서비스 churn으로 root focus를 잃어
  hosted acceptance가 blocked로 남았다.
- Angular template에는 native accessibility tree가 읽을 수 있는 nav/content/date
  label만 추가했으며 route/API/auth 동작은 변경하지 않았다. README 두 파일은 schema v2
  artifact와 실패 시 수집 경계를 반영했다.
- 통과: `npm run test:native:report` (8), `npm run test:native:workflow` (13),
  `npm test -- --watch=false` (52 files/387 tests), `npm run test:e2e -- --workers=1`
  (27), production build, `cap:sync`, `cap doctor`, `npm run docs:verify`, `actionlint`.
- 정적 통과: `xcodebuild -list`에서 `App`/`AppUITests` target, `swiftc -parse`.
  exact-head run `33204869723`은 iOS report·XCResult·screenshot과 세 interaction을
  통과했지만 Android report·JUnit·screenshot·window hierarchy·logcat은
  `RootViewWithoutFocusException`과 `MainActivity STOPPED`를 기록했다.
- 따라서 Android native acceptance와 그에 종속된 PR gate는 `BLOCKED`이며, 동일한
  시스템 서비스 churn이 해소된 runner에서만 fresh exact-head proof를 재개한다.
- 재사용 판단: `$bluetape-kotlin-patterns`와 `bluetape4k-assertions`는 변경 scope에
  Kotlin 파일이 없어 N/A다. Java/Swift/Node 테스트에 Kotlin assertion dependency를
  억지로 추가하지 않았다.
