# Issue #24 iOS/Android WebView 통합 검증 구현 계획

> **For agentic workers:** 이 계획은 Epic #13의 마지막 stacked PR slice다. PR base는
> `feat/issue-27-native-webview-bridge`이며, Epic 전체 완료 전에는 병합하지 않는다.
> 각 단계는 fresh evidence를 남기고, native runner가 없으면 성공으로 위장하지 않는다.

**목표:** exact commit에서 iOS/Android Capacitor build·launch·deep-link smoke를
재현할 수 있는 CI 경로와 로컬 환경 probe를 추가하고, Playwright mobile profile을
native와 분리된 browser 회귀 증거로 수집한다.

**구조:** Node 기반 environment/report validator는 toolchain과 artifact 계약을
결정론적으로 검사한다. Playwright 설정은 iPhone/Pixel profile을 별도 project로
노출한다. `native-webview-ci.yml`은 exact ref를 checkout하고 Android emulator와
iOS simulator에서 build/install/launch/openURL을 순차 실행한 뒤 platform manifest를
artifact로 업로드한다. standalone workflow가 기본 브랜치에 등록되기 전에는 동일 native
job을 포함한 feature ref의 `frontend-ci.yml` compatibility dispatch를 사용한다.

**기술:** Angular 22, Capacitor 8.5.0/`@capacitor/app` 8.1.1, Node 22, Playwright,
Android Gradle/SDK, Xcode `xcodebuild`/`simctl`, GitHub Actions.

## 파일 책임과 범위

- Modify: `frontend/appointment-frontend/package.json` — environment/report 검증
  scripts를 등록한다.
- Create: `frontend/appointment-frontend/scripts/validate-native-webview-environment.mjs`
  — xcodebuild/simctl/adb/sdkmanager/Java/Git command availability와 version을
  secret 없이 JSON으로 출력한다.
- Create: `frontend/appointment-frontend/scripts/validate-native-webview-environment.test.mjs`
  — injected command runner로 available/missing/error 상태를 검증한다.
- Create: `frontend/appointment-frontend/scripts/create-native-webview-report.mjs`
  — platform, exact commit, toolchain, command, result를 고정된 manifest로 쓴다.
- Create: `frontend/appointment-frontend/scripts/create-native-webview-report.test.mjs`
  — 필수 필드, 실패 결과, secret/raw output 거부를 검증한다.
- Create: `frontend/appointment-frontend/scripts/validate-native-webview-workflow.mjs`
  — workflow_dispatch ref, exact checkout, platform jobs, smoke commands와 artifact
  upload marker를 검증한다.
- Create: `frontend/appointment-frontend/scripts/validate-native-webview-workflow.test.mjs`
  — workflow marker 누락과 정상 workflow를 검증한다.
- Modify: `frontend/appointment-frontend/playwright.config.ts` — `mobile-ios`와
  `mobile-android` project를 공식 device profile로 추가한다.
- Create: `frontend/appointment-frontend/e2e/mobile-webview-contract.spec.ts` —
  mobile viewport에서 auth/session, tenant API request, lazy route, deep-link
  browser fallback, Safe Area/keyboard/overflow 계약을 검증한다.
- Create: `.github/workflows/native-webview-ci.yml` — workflow dispatch `ref`와
  `expected_sha`를 exact commit으로 고정하고 Android/iOS build-smoke 및 report
  artifact를 수행한다.
- Modify: `frontend/appointment-frontend/README.md`, `README.ko.md` — native CI
  실행 명령, artifact schema, browser/native 증거 경계를 문서화한다.
- Create: `docs/superpowers/reviews/2026-08-27-issue-24-native-webview-validation-implementation-review.ko.md`
  — 7-Tier review와 P0/P1/P2 판정을 기록한다.
- Create: `docs/lessons/2026-08-27-issue-24-native-webview-validation.md` —
  native toolchain 부재를 성공으로 오인하지 않는 재발 방지 규칙을 기록한다.

## 계약 추적성

| 수용 기준                                                    | 구현 task | fresh proof                                               |
| ------------------------------------------------------------ | --------- | --------------------------------------------------------- |
| toolchain 상태를 구조화하고 missing을 fail-closed            | 1         | environment unit/CLI test                                 |
| report에 exact commit/platform/toolchain/command/result 기록 | 2, 4      | report contract test와 CI artifact                        |
| mobile browser에서 route/auth/API/viewport 계약 유지         | 3         | Playwright iPhone/Pixel projects                          |
| Android build/install/launch/deep-link smoke                 | 4         | Android job 및 artifact                                   |
| iOS build/install/launch/openURL smoke                       | 4         | iOS job 및 artifact                                       |
| docs/7-Tier/Kotlin/assertions evidence와 final delivery      | 5, 6      | review, `:appointment-api:build`, exact-head CI/read-back |

## 위험·롤백

| 위험                                  | 조기 signal                                 | 완화·rollback                                                                      | 재실행 지점 |
| ------------------------------------- | ------------------------------------------- | ---------------------------------------------------------------------------------- | ----------- |
| hosted runner SDK/Xcode 버전 drift    | report toolchain version 불일치             | runner image와 command를 artifact에 기록하고 compatibility failure를 그대로 남긴다 | Task 4      |
| emulator/simulator boot timeout       | `adb devices` 또는 `simctl bootstatus` 실패 | timeout과 상태를 artifact로 업로드하고 job을 실패시킨다                            | Task 4      |
| deep-link intent가 다른 앱으로 라우팅 | package/bundle launch 결과 불일치           | explicit package/bundle과 exact scheme/host를 사용하고 process check를 요구한다    | Task 4      |
| browser profile을 native PASS로 집계  | mobile tests만 green                        | CI jobs와 DoD 항목을 분리하고 native artifact 없이는 #24를 미완료로 둔다           | Task 3, 5   |
| report에 credential/raw log 포함      | contract test에서 forbidden term 발견       | 허용된 metadata만 입력받고 secret/raw output field를 거부한다                      | Task 2      |
| current host에 toolchain 없음         | environment probe `available=false`         | 로컬 결과는 PENDING, hosted runner dispatch를 별도 증거로 기다린다                 | Task 1, 6   |

## Task 1 — environment probe를 TDD로 구현한다

**Files:** `scripts/validate-native-webview-environment.mjs`,
`scripts/validate-native-webview-environment.test.mjs`, `package.json`.

- [x] **Step 1: missing/available command 상태의 RED 테스트를 작성한다**

```js
const result = collectNativeEnvironment({
  commandRunner: (command) =>
    command === "xcodebuild"
      ? { status: 0, stdout: "Xcode 16.4\\n" }
      : { status: 127, stdout: "", stderr: "not found" },
});
assert.equal(result.commands.xcodebuild.available, true);
assert.equal(result.commands.adb.available, false);
assert.equal(result.targets.ios, true);
assert.equal(result.targets.android, false);
```

- [x] **Step 2: RED를 관찰한다**

Run: `npm run test:native:environment`

Expected: export된 `collectNativeEnvironment`가 없어 실패한다.

- [x] **Step 3: 최소 probe와 CLI script를 구현한다**

각 command를 한 번 실행하고 exit status와 첫 번째 안전한 metadata line만 저장한다.
`xcodebuild`와 `xcrun`은 iOS target, `adb`와 `sdkmanager`는 Android target으로
묶는다. `available`이 false여도 process 자체는 성공하며 `targets`가 false를 반환한다.

- [x] **Step 4: GREEN과 CLI 출력을 확인한다**

Run: `npm run test:native:environment && npm run native:environment`

Expected: unit tests PASS, 현재 호스트는 full native target이 없음을 JSON으로 명시한다.

- [x] **Step 5: commit**

`git add frontend/appointment-frontend/package.json frontend/appointment-frontend/scripts`
후 Korean Lore commit을 만든다.

## Task 2 — CI artifact report 계약을 TDD로 고정한다

**Files:** `scripts/create-native-webview-report.mjs`, its test.

- [x] **Step 1: report schema RED 테스트를 작성한다**

필수 필드 `platform`, `commit`, `toolchain`, `commands`, `result`, `generatedAt`가
없으면 실패하고, `result`는 `passed` 또는 `failed`만 허용한다. `token`, `password`,
`secret`, `raw_output`가 metadata에 포함되면 거부한다.

- [x] **Step 2: RED를 관찰한다**

Run: `npm run test:native:report`

Expected: report builder module이 없어 실패한다.

- [x] **Step 3: deterministic report builder를 구현한다**

환경변수와 `git rev-parse HEAD`를 읽어 JSON을 출력하고, command 결과는 exit code와
짧은 label만 기록한다. raw stdout/stderr와 credential 값은 저장하지 않는다. output
경로는 caller가 지정하며 parent directory를 새로 만들지 않는다.

- [x] **Step 4: GREEN과 forbidden-field 검사를 확인한다**

Run: `npm run test:native:report`

Expected: valid passed/failed report와 금지 필드 rejection이 PASS한다.

## Task 3 — mobile browser profile과 contract를 연결한다

**Files:** `playwright.config.ts`, `e2e/mobile-webview-contract.spec.ts`.

- [x] **Step 1: mobile contract RED 테스트를 작성한다**

iPhone/Pixel project에서 tenant fixture를 설치하고 `/calendar`, `/appointments`,
appointment detail과 deep-link 대상 route를 확인한다. request URL의 tenant path와
workforce Bearer scope를 확인하고, 320/375px viewport에서 `scrollWidth <= clientWidth`,
focus element bounds와 safe-area/keyboard 경계를 검증한다. native plugin callback이나
browser storage에 인증 자료가 생기지 않는 것도 확인한다. patient cookie/CSRF와
cross-origin credentials는 기존 `api-origin-contract.spec.ts`의 Chromium contract로
별도 검증한다.

- [x] **Step 2: RED를 관찰한다**

Run: `npx playwright test e2e/mobile-webview-contract.spec.ts --workers=1`

Expected: project 또는 spec이 없어 실패한다.

- [x] **Step 3: 공식 Playwright device project를 추가하고 기존 fixture를 재사용한다**

`devices['iPhone 13']`와 `devices['Pixel 5']`를 각각 `mobile-ios`/`mobile-android`로
등록하고 mobile contract spec만 각 project에 연결한다. 새 테스트는 기존 API route
fixture와 Angular route를 사용하며 native plugin을 mock하거나 browser 결과를 native
결과로 명명하지 않는다.

- [x] **Step 4: GREEN과 전체 browser 회귀를 확인한다**

Run: `npx playwright test e2e/mobile-webview-contract.spec.ts --project=mobile-ios --project=mobile-android --workers=1`

Expected: 두 profile의 contract가 PASS한다. 이어 `npx playwright test --workers=1`로
기존 Chromium 20건 이상도 PASS한다.

## Task 4 — exact-ref native CI workflow를 구현한다

**Files:** `.github/workflows/native-webview-ci.yml`.

- [x] **Step 1: workflow contract RED static test를 추가한다**

`workflow_dispatch.inputs.ref`/`expected_sha`, `actions/checkout`의 exact ref,
checkout SHA 검증, Android/iOS job, `native-webview-report.json` upload,
`npm run cap:sync`가 없으면 실패하는 Node contract test를 추가한다.

- [x] **Step 2: RED를 관찰한다**

Run: `npm run test:native:workflow`

Expected: workflow file 또는 required marker가 없어 실패한다.

- [x] **Step 3: Android job을 구현한다**

`ubuntu-22.04`에서 Node 22/npm ci, Android job 전용 Java 21, `npm run cap:sync`,
Android emulator runner를 순서대로 실행한다. APK를 install하고 `MainActivity`를
launch한 뒤 URI만 전달하는 단일 `adb shell am start -W -a
android.intent.action.VIEW -d 'io.bluetape4k.clinic.appointment://open/tenant-default/calendar?view=week&date=2026-08-27'`
호출로 deep link를 발행한다. package/process 확인 뒤 report를 만들고 APK와 report를
upload한다. 어느 단계든 실패하면 report `failed`와 job failure를 남긴다.

- [x] **Step 4: iOS job을 구현한다**

`macos-latest`에서 Node 22/npm ci, `npm run cap:sync`,
`xcodebuild -project ios/App/App.xcodeproj -scheme App -sdk iphonesimulator
-destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`를
실행한다. iPhone simulator를 boot하고 `.app`을 install/launch한 뒤
`xcrun simctl openurl booted 'io.bluetape4k.clinic.appointment://open/tenant-default/calendar?view=week&date=2026-08-27'`
를 발행한다. bundle id/process 확인 뒤 report와 `.app` metadata를 upload한다.

- [x] **Step 5: workflow static contract와 YAML lint를 GREEN으로 확인한다**

Run: `npm run test:native:workflow` 및 `actionlint .github/workflows/frontend-ci.yml .github/workflows/native-webview-ci.yml`

Expected: contract와 actionlint가 PASS한다.

## Task 5 — 문서·review·lesson 및 ecosystem 회귀를 갱신한다

**Files:** README 두 locale, review, lesson.

- [x] **Step 1: Korean README에 exact dispatch/증거 경계를 추가한다**

local probe, browser profile, Android/iOS workflow dispatch 명령과 artifact 필드,
현재 host의 N/A 조건을 실제 script 이름과 일치하게 기록한다.

- [x] **Step 2: 7-Tier review와 lesson을 작성한다**

Performance, Stability, Security, Operator/Ops, Developer/API, User/Caller,
Main-session integration 관점에서 P0/P1=0을 목표로 하고, native CI가 아직 실행되지
않으면 P2/PENDING으로 명시한다. `bluetape-kotlin-patterns`는 Kotlin source 변경
N/A를 기록하고 `./gradlew :appointment-api:build`의 `bluetape4k-assertions`
회귀 경계를 근거로 남긴다.

- [x] **Step 3: docs/terminology/read-back을 확인한다**

Run: `npm run docs:verify`, `node ../../scripts/audit-korean-terms.mjs` 또는
저장소 표준 audit, `npx prettier --check ...`, `git diff --check`.

## Task 6 — 통합 검증과 stacked PR delivery를 완료한다

- [x] **Step 1: local frontend/backend proof**

`npm run test:native:environment`, `npm run test:native:report`,
`npm run test:native:workflow`, mobile/Chromium Playwright, `npm run build`,
`npx tsc --noEmit -p tsconfig.app.json`, `npm run cap:sync`,
`npx cap doctor`, `./gradlew :appointment-api:build`, `npm audit --omit=dev
--audit-level=moderate`, `git diff --check`를 순차 실행한다.

- [x] **Step 2: native workflow dispatch**

push 후 `HEAD_SHA=$(git rev-parse HEAD)`를 구하고
standalone workflow가 기본 브랜치에 올라가기 전에는
`gh workflow run frontend-ci.yml --ref feat/issue-24-native-webview-validation`로
compatibility 경로를 실행한다. 이후 standalone workflow에서는
`gh workflow run native-webview-ci.yml --ref feat/issue-24-native-webview-validation
-f ref="$HEAD_SHA" -f expected_sha="$HEAD_SHA"`로 exact head를 실행한다. iOS/Android
job과 report artifact의 commit을 read-back하며, runner가 없거나 실패하면 결과를
PENDING/FAIL로 기록하고 성공으로 대체하지 않는다. 최종 compatibility run의 두 native
report와 job conclusion이 dispatch exact head에서 `passed`임을 확인하고 Issue/PR live
evidence에 run ID와 artifact를 기록한다.

- [x] **Step 3: final 7-Tier and PR body**

정확한 base `379a52fca753a6094f4bf136f54cfeb67e620685`, head SHA, CI URL, report,
Issue #24 metadata를 한국어 PR body의 마지막 `## DoD Status`에 기록한다. Epic 전체
완료 전 merge unchecked를 유지한다.

- [x] **Step 4: commit, push, live read-back, workflow receipt**

Korean Lore commit 후 remote SHA, PR labels/assignee/milestone, CI conclusion, Issue
body, workflow `completion-check`와 immutable live report를 read-back한다.
