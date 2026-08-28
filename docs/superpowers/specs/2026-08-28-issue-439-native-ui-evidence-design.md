# Issue #439 native UI 증거 설계

## 문서 계약

- 독자: `clinic-appointment`의 frontend/native CI 유지보수 담당자
- 목적: browser contract와 native launch smoke만으로는 증명하지 못하는 실제
  WebView UI 상호작용을 두 simulator에서 재현 가능하게 고정한다.
- 기준: Issue [#439](https://github.com/bluetape4k/clinic-appointment/issues/439),
  `origin/develop@e6937e02a202106f3927ccf71c47f8f0c38ce952`, 기존 Issue #24 설계
- 확정 사실: canonical workflow는 `.github/workflows/native-webview-ci.yml`,
  frontend는 Angular/Capacitor, Android는 `MainActivity`의 WebView, iOS는
  `CAPBridgeViewController`를 root로 사용한다.
- 미확정/외부 의존: 이 호스트에는 full Xcode와 Android SDK/emulator가 없어
  simulator 실행 결과는 exact-head CI에서 확정한다.

## 문제와 목표

현재 native workflow는 APK/app을 build·install·launch하고 custom-scheme deep link를
확인한다. Playwright mobile profile은 CSS viewport 계약을 확인하지만 실제 native
coordinate, Safe Area, software keyboard와 simulator orientation을 증명하지 않는다.
따라서 기존 Angular shell과 Capacitor bundle을 그대로 실행하는 test-only harness를
추가한다.

완료 후 각 플랫폼은 다음을 증명한다.

1. portrait에서 bottom tab을 실제 native input으로 tap하면 대상 route의 title이 바뀐다.
2. touch target의 최소 경계를 확인한다. 기존 CSS 계약의 44px 이상을 유지하고,
   native test에서는 실제 접근 가능한 element bounds를 측정한다.
3. focus 가능한 날짜 input을 열고 active element와 visual viewport 상태를 기록한다.
4. landscape 전환 뒤 Safe Area/content와 bottom navigation의 overflow를 검사한다.
5. exact commit, runner, device profile, viewport/orientation, interaction 결과,
   report·JUnit/XCResult·screenshot artifact를 저장한다.

## 설계 경계

### Android

- 기존 `android/app`과 `MainActivity`를 실행한다.
- `androidTest`에만 `androidx.test.espresso:espresso-web`과
  `androidx.test.uiautomator:uiautomator`를 추가한다. production runtime dependency와
  Capacitor plugin은 변경하지 않는다.
- Espresso-Web은 DOM title/active input/viewport를 확인하고, UiAutomator의
  `UiDevice`는 WebView accessibility node bounds 중심을 실제 tap한다. DOM script로
  확인한 geometry만으로 성공시키지 않는다.
- `ActivityScenarioRule<MainActivity>`로 activity lifecycle을 관리하고, API 호출이
  실패해도 route shell이 유지되는 기존 컴포넌트 경계를 이용한다. 인증 token/fixture를
  저장하지 않는다.

### iOS

- built-in XCTest UI target `AppUITests`를 `App.xcodeproj`에 추가한다. 외부 UI
  dependency는 사용하지 않는다.
- `XCUIApplication`으로 app을 launch하고 `links["예약 관리"]`를 tap한다.
- `예약 목록` title, `시작일` text field focus, keyboard query, bottom navigation container
  frame과
  portrait/landscape frame을 확인한다. `XCUIDevice.shared.orientation`은 테스트가
  완료된 뒤 portrait로 복원한다.
- 기존 `CAPBridgeViewController`와 production scene lifecycle은 변경하지 않는다.

### 공통 report

기존 schema v1을 깨지 않고 schema v2로 확장한다.

```json
{
  "schemaVersion": 2,
  "generatedAt": "2026-08-28T05:00:00Z",
  "platform": "android",
  "commit": "<40-char-lowercase-sha>",
  "toolchain": {"runner":"ubuntu-22.04","java":"21"},
  "device": {"profile":"pixel_5","viewport":"1080x1920","orientations":["portrait","landscape"]},
  "commands": ["cap:sync","connectedDebugAndroidTest"],
  "interactions": [
    {"name":"bottom-tab-route","result":"passed"},
    {"name":"focus-keyboard-viewport","result":"passed"},
    {"name":"orientation-safe-area","result":"passed"}
  ],
  "artifacts": ["artifacts/android-ui/screenshot.png","artifacts/android-ui/test-results.xml"],
  "result": "passed"
}
```

`device`, `interactions`, `artifacts`는 bounded string/list/object만 허용한다.
경로는 repository-relative safe path로 제한하며 `token`, `password`, `secret`,
raw output과 credential를 거부한다. report writer는 기존 `flag: 'wx'`를 유지한다.

## 대안과 결정

| 대안 | 결정 | 이유 |
|---|---|---|
| Playwright만 확장 | 거부 | browser CSS viewport는 native accessibility/tap/Safe Area 증거가 아니다. |
| Espresso-Web `webClick()`만 사용 | 거부 | JS bridge click은 실제 device coordinate tap을 증명하지 않는다. |
| production에 test hook/token 주입 | 거부 | auth·API 계약과 secret 경계를 변경하고 #439 범위를 넓힌다. |
| iOS third-party driver 도입 | 거부 | built-in XCTest가 target 내에서 충분하고 production dependency를 늘리지 않는다. |
| desktop landscape에서도 bottom tab을 강제 노출 | 보류 | 현재 breakpoint는 기존 의도일 수 있으며, native test는 landscape frame/overflow를 먼저 관찰한다. |

## 수용 기준 추적

| Issue 조건 | 구현 증거 | 검증 |
|---|---|---|
| Android bottom tab/touch target | `NativeWebViewUiTest` | connected Android instrumentation |
| iOS bottom tab/Safe Area | `AppUITests` | `xcodebuild test` simulator |
| portrait·landscape·keyboard/focus | 두 test와 `interactions` | report schema v2 artifact |
| exact-head CI/report artifact | canonical·mirrored workflow | workflow validator/actionlint/CI |
| 7-Tier·한국어 문서 | plan/review/lesson | writer term audit/read-back |

## 위험과 완화

| 위험 | 신호 | 완화 |
|---|---|---|
| WebView accessibility tree가 label을 노출하지 않음 | UiAutomator node 없음 | `aria`/label 계약을 먼저 검사하고, 필요한 경우 최소한의 semantic attribute만 추가한다. |
| API origin이 simulator에서 연결되지 않음 | route shell이 error로 중단 | unguarded `/calendar`·`/appointments` shell을 사용하고, auth fixture나 fake origin은 추가하지 않는다. |
| landscape에서 desktop breakpoint가 bottom nav를 숨김 | tab node가 사라짐 | portrait에서 tap을 증명하고 landscape에서는 viewport/Safe Area/overflow를 증명한다. breakpoint 변경은 별도 issue로 분리한다. |
| local toolchain 부재 | `xcodebuild`/`adb` 명령 없음 | local source/validator/compile 가능한 부분을 실행하고, exact-head CI를 merge gate로 둔다. |
| report가 실패에서도 생성되지 않음 | artifact missing | `if: always()` report/write/upload와 별도 result enforcement를 유지한다. |

## 7-Tier 설계 검토 입력

- Requirements: #439의 native UI gap만 다루고 #24 launch smoke/browser contract를
  대체하지 않는다.
- Architecture: 기존 Angular/Capacitor/WebView를 재사용하며 test-only harness만 추가한다.
- Security: report redaction과 no-token 정책을 유지한다.
- Operations: exact SHA·device·orientation·artifact를 report에 남긴다.
- Test: RED/GREEN report/workflow contract와 native instrumentation/XCTest를 분리한다.
- Documentation: 모든 reader-facing artifact는 repository-local Korean 정책을 따른다.
- Integration: canonical `native-webview-ci.yml`과 `frontend-ci.yml`의 native 경로를
  같은 schema/명령으로 검증한다.

## 범위 N/A

Kotlin 파일을 변경하지 않으므로 `$bluetape-kotlin-patterns`의 Kotlin 구현 행과
`bluetape4k-assertions` 도입은 트리거되지 않는다. 이 판단은 Android 테스트가 Java,
iOS 테스트가 Swift, report/validator가 Node.js인 실제 변경 scope에 근거한다.

## 구현 read-back

- Android `NativeWebViewUiTest`와 iOS `AppUITests`를 추가하고, Angular shell의 nav·date
  input에 최소 accessibility label을 부여했다. iOS WebView link accessibility frame이
  42px로 관찰된 후 link glyph frame을 target 판정에 직접 사용하지 않고, nav container의
  44px frame·실제 tap·CSS target 검사를 조합하도록 보정했다. bottom label에도 44px 최소
  폭을 유지해 CSS surface를 정렬했다. route/API/auth production 경계는 그대로 둔다.
- canonical `native-webview-ci.yml`과 mirrored `frontend-ci.yml` 모두
  `connectedDebugAndroidTest`, `xcodebuild test`, schema v2 report, JUnit/XCResult와
  screenshot upload, 실패 결과 gate를 갖는다. mirrored marker parity는 Node contract
  test로 검증한다.
- report v2는 exact commit, bounded device/viewport/orientation, fixed interaction
  result와 safe artifact path를 보존한다. 기존 v1 fixture는 계속 허용한다.
- 로컬 Angular/browser/contract/static project 검증은 통과했지만 이 호스트의
  Android SDK/emulator와 iOS Simulator runtime이 없어 native 계측은 PENDING이다.
  이 상태를 hosted exact-head CI 전에는 PASS로 승격하지 않는다.
