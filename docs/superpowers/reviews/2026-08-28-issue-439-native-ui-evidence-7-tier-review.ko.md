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
- 검증 상태: 코드·계약·browser·정적 native project 검증과 iOS hosted 계측은 통과했다.
  exact-head hosted run `33204869723`에서 Android는 AOSP ATD의 persistent
  `com.android.phone`/`system_server` churn으로 `RootViewWithoutFocusException`과
  `MainActivity STOPPED`가 발생해 native acceptance가 `BLOCKED`다.

## 모듈별 결과

| 모듈/경계 | 7-Tier 결과 | 근거 | 미확인/후속 |
|---|---|---|---|
| `frontend/appointment-frontend` Angular/Node | PASS | report/workflow contract, 52 files·387 tests, production build, 27 browser E2E 통과 | 없음 |
| Android Capacitor UI | BLOCKED (hosted) | `NativeWebViewUiTest`, Espresso-Web·UiAutomator test-only dependency, AOSP ATD API34 report/JUnit/artifact 생성 | run `33204869723`에서 `RootViewWithoutFocusException`; `MainActivity STOPPED`와 system service churn 원인 read-back |
| iOS Capacitor UI | PASS | `AppUITests` target/scheme, hosted XCTest와 report·XCResult·screenshot의 세 interaction 통과 | 없음 |
| native report/workflow | PASS (Android 결과 gate 제외) | schema v2 bounds/redaction, canonical validator 13 tests, mirrored workflow parity test, actionlint, 양 플랫폼 artifact capture | Android result gate는 hosted 실패를 정확히 전파함 |
| `$bluetape-kotlin-patterns` / `bluetape4k-assertions` | N/A (scope) | 변경 파일에 Kotlin이 없고 Android 테스트는 Java, iOS 테스트는 Swift, contract는 Node.js다. 무관한 Kotlin dependency나 raw assertion 치환을 추가하지 않았다. | 기존 Kotlin 모듈은 이번 이슈 범위 밖 |

## 7-Tier 판정

| Tier | 검토 질문 | 판정 | 증적 |
|---|---|---|---|
| 1. Performance | native test/report가 불필요한 반복·무제한 출력을 만들지 않는가 | PASS | 단일 browser worker, emulator timeout, report list/object bound, raw output 미수집 |
| 2. Stability | exact ref와 실패 전파, orientation 복원이 보장되는가 | BLOCKED (Android hosted) | checkout SHA 검증, `if: always()` artifact 수집, 별도 result gate와 iOS tearDown은 통과했으나 Android system service churn이 WebView root focus를 잃게 함 |
| 3. Security | credential·token·DOM raw output이 artifact로 유출되지 않는가 | PASS | report forbidden-term/unknown-field 테스트, safe relative path, production auth/API 변경 없음 |
| 4. Operator/Ops | 실패를 재현하고 결과를 복구할 수 있는가 | PASS | platform별 report·JUnit/XCResult·screenshot·Android logcat/window hierarchy upload, device/profile/viewport 기록, actionlint |
| 5. Developer/API | test-only contract와 public script가 작고 명확한가 | PASS | schema v1 호환·v2 확장, validator fail-closed, README 명령과 artifact 예시 |
| 6. User/Caller | 실제 mobile 사용 흐름과 접근성 경계가 유지되는가 | BLOCKED (Android hosted) | iOS 세 interaction과 정적 Android contract는 통과했지만 Android native Espresso 진입 전 root focus를 잃음 |
| 7. Main-session integration | canonical/mirrored workflow와 issue/document 증거가 수렴하는가 | BLOCKED | 두 workflow parity와 문서는 통과했으나 exact-head Android acceptance와 PR gate가 미완료 |

## 검증 명령과 결과

```text
npm run test:native:report                         PASS (8 tests)
npm run test:native:workflow                       PASS (13 tests)
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
native:environment                                 PASS (local targets.ios=false, targets.android=false)
Native WebView CI 33204869723                      Browser PASS, iOS PASS, Android FAIL (RootViewWithoutFocusException)
Android report/JUnit/logcat/window hierarchy       ARTIFACTS PRESERVED (AOSP ATD API34, exact commit 3ced6c02)
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

코드 검토에서 P0/P1/P2/P3 finding은 0건이다. 다만 Android hosted 계측은 네 차례
동일한 시스템 서비스 churn으로 실패했고, 최신 report/JUnit/logcat/window hierarchy를
보존한 채 receipt를 `BLOCKED`로 전환했다. Android runner 환경이 복구되면 동일한
exact-head 계약으로 native acceptance를 재검증해야 하며, 그 전에는 PR merge-ready나
Issue 완료를 선언하지 않는다.
