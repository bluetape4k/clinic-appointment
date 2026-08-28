# Issue #439 native UI 증거 구현 7-Tier review

## 범위와 판정

- 대상 이슈: [#439](https://github.com/bluetape4k/clinic-appointment/issues/439)
- 대상 branch: `feat/issue-439-native-ui-evidence`
- 기준: `origin/develop@e6937e02a202106f3927ccf71c47f8f0c38ce952`
- 검토 범위: Angular mobile semantic hook, Android instrumentation UI test, iOS
  XCTest UI target, native report schema v2, canonical/mirrored workflow와 문서
- 기존 경계: browser mobile contract와 #24의 native launch/deep-link smoke를 대체하지
  않으며, 이번 변경은 실제 WebView UI 상호작용 증거만 보강한다.
- 판정: P0=0, P1=0, P2=0, P3=0
- 검증 상태: 코드·계약·browser·정적 native project 검증은 통과했다. 이 호스트에는
  Android SDK/emulator와 활성 iOS Simulator runtime이 없어 실제 계측 실행은
  `PENDING`이며, exact-head hosted CI가 최종 native 증거다.

## 모듈별 결과

| 모듈/경계 | 7-Tier 결과 | 근거 | 미확인/후속 |
|---|---|---|---|
| `frontend/appointment-frontend` Angular/Node | PASS | report/workflow contract, 52 files·387 tests, production build, 27 browser E2E 통과 | 없음 |
| Android Capacitor UI | PASS (정적) | `NativeWebViewUiTest`, Espresso-Web·UiAutomator test-only dependency, WebView accessibility hook | Android Emulator 계측 실행은 hosted exact-head CI 대기 |
| iOS Capacitor UI | PASS (정적) | `AppUITests` target/scheme, `xcodebuild -list`, Swift parse 통과 | Simulator runtime이 없는 호스트이므로 XCTest 실행은 hosted exact-head CI 대기 |
| native report/workflow | PASS | schema v2 bounds/redaction, canonical validator 8 tests, mirrored workflow parity test, actionlint | hosted artifact read-back 대기 |
| `$bluetape-kotlin-patterns` / `bluetape4k-assertions` | N/A (scope) | 변경 파일에 Kotlin이 없고 Android 테스트는 Java, iOS 테스트는 Swift, contract는 Node.js다. 무관한 Kotlin dependency나 raw assertion 치환을 추가하지 않았다. | 기존 Kotlin 모듈은 이번 이슈 범위 밖 |

## 7-Tier 판정

| Tier | 검토 질문 | 판정 | 증적 |
|---|---|---|---|
| 1. Performance | native test/report가 불필요한 반복·무제한 출력을 만들지 않는가 | PASS | 단일 browser worker, emulator timeout, report list/object bound, raw output 미수집 |
| 2. Stability | exact ref와 실패 전파, orientation 복원이 보장되는가 | PASS | checkout SHA 검증, `if: always()` artifact 수집, 별도 result gate, iOS tearDown/Android finally 복원 |
| 3. Security | credential·token·DOM raw output이 artifact로 유출되지 않는가 | PASS | report forbidden-term/unknown-field 테스트, safe relative path, production auth/API 변경 없음 |
| 4. Operator/Ops | 실패를 재현하고 결과를 복구할 수 있는가 | PASS | platform별 report·JUnit/XCResult·screenshot upload, device/profile/viewport 기록, actionlint |
| 5. Developer/API | test-only contract와 public script가 작고 명확한가 | PASS | schema v1 호환·v2 확장, validator fail-closed, README 명령과 artifact 예시 |
| 6. User/Caller | 실제 mobile 사용 흐름과 접근성 경계가 유지되는가 | PASS (정적) | native coordinate tap, accessibility group frame, 예약 route title, 날짜 focus/keyboard, CSS 44px target, portrait/landscape frame 검사 |
| 7. Main-session integration | canonical/mirrored workflow와 issue/document 증거가 수렴하는가 | PASS | 두 workflow parity test, Korean spec/plan/checklist/review/lesson, root dirty state 보존 |

## 검증 명령과 결과

```text
npm run test:native:report                         PASS (8 tests)
npm run test:native:workflow                       PASS (8 tests)
npm run native:workflow                            PASS (missing=[])
npm run test:bundle                                PASS (4 tests)
npm run test:pwa                                   PASS (3 tests)
npm run test:bridge                                PASS (3 tests)
npm test -- --watch=false                          PASS (52 files, 387 tests)
npm run test:e2e -- --workers=1                    PASS (27 tests)
npx ng build --configuration production             PASS
npm run cap:sync                                   PASS
npx cap doctor                                     PASS (iOS/Android looking great)
npm run docs:verify                                 PASS (10 documents, 8 source checks)
actionlint .github/workflows/native-webview-ci.yml .github/workflows/frontend-ci.yml PASS
DEVELOPER_DIR=... xcodebuild -list -project ...    PASS (App, AppUITests targets)
swiftc -parse .../AppUITests.swift                 PASS
native:environment                                 PASS (targets.ios=false, targets.android=false)
Android ./gradlew ... connectedDebugAndroidTest    PENDING (host SDK location unavailable)
iOS xcodebuild test                                PENDING (host simulator runtime unavailable)
```

## 재사용·범위 판정

- 기존 Angular route와 Capacitor bundle, `MainActivity` WebView, native workflow,
  report writer를 그대로 재사용했다. production UI 재작성, fake auth/token, 새 bridge,
  별도 browser driver는 추가하지 않았다.
- Android는 Espresso-Web으로 DOM 상태를 보조 확인하되 실제 입력은 UiAutomator
  coordinate tap으로 수행한다. iOS는 built-in XCTest만 사용한다.
- `bluetape4k-assertions`는 Kotlin/JVM assertion library이므로 Java instrumentation과
  Swift XCTest에 억지로 도입하지 않았다. 이 N/A는 사용 누락이 아니라 실제 변경 scope에
  따른 경계 판정이다.

## 최종 통합 판정

현재 diff는 P0/P1 blocker 없이 PR 생성·exact-head CI 요청이 가능한 상태다. 다만
Android Emulator와 iOS Simulator의 실제 상호작용·artifact는 hosted CI에서 fresh head로
확인하기 전까지 완료로 승격하지 않는다. CI가 실패하면 report를 보존한 상태에서 해당
플랫폼만 수정하고 native acceptance를 재검증한다.
