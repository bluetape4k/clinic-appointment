# Issue #439 native UI 증거 lesson

## 배경

브라우저의 mobile viewport 테스트는 CSS와 route 회귀를 빠르게 잡지만, 실제
Capacitor WebView의 accessibility tree, native coordinate input, software keyboard,
Safe Area와 orientation을 증명하지 않는다. 기존 launch/deep-link smoke도 앱이 뜨는지와
scheme 전달만 보장하므로, UI 상호작용은 별도 계측 테스트로 고정해야 한다.

## 결정

1. Android는 `UiDevice`로 접근성 node의 실제 중심을 tap하고, Espresso-Web은 route
   title·active input·`visualViewport`를 보조 확인한다. DOM script click만으로 native
   tap을 대체하지 않는다.
2. iOS는 third-party driver를 추가하지 않고 built-in `XCUIApplication`/XCTest UI
   target으로 accessibility group frame, link tap, text field focus, keyboard 존재,
   portrait·landscape frame을 확인한다. WebView landmark가 native tree에 노출되지 않는
   경우에는 의미를 유지한 `role="group"` wrapper를 두고 link glyph frame과 hit area를
   혼동하지 않는다.
3. report schema v2는 `device`, `interactions`, `artifacts`를 bounded 값으로 추가하고
   v1 입력은 migration 경계로 유지한다. report는 safe relative path만 허용하고
   credential, token, password, secret, raw output을 거부한다.
4. native workflow는 테스트 실패와 artifact 수집을 분리한다. `if: always()`로
   report·JUnit/XCResult·screenshot을 먼저 보존하고, 별도 result gate가 실패를
   전파한다. `ref`와 exact SHA 검증은 canonical과 mirrored 경로에서 동일하게 유지한다.
5. native SDK가 없는 개발 호스트의 결과는 PASS로 추정하지 않는다. 정적 project parse와
   contract test는 통과로 기록하되, 실제 Emulator/Simulator 증거는 hosted exact-head
   CI에서만 완료한다.
6. Android `ActivityScenario`는 테스트 시작 시 한 번만 `RESUMED`를 보장한다. native tap
   뒤나 orientation 복귀 시 lifecycle 전환을 반복하면 instrumentation의 `EmptyActivity`가
   WebView root focus를 탈취할 수 있다. 중복 호출을 제거한 최신 head에서도 AOSP ATD의
   persistent `com.android.phone` 재시작과 `system_server` contention으로
   `RootViewWithoutFocusException`이 재현됐으므로, 실패 artifact를 보존하고 runner 환경
   복구 전에는 추가 추측 수정을 중단한다.

## 재발 방지

- 새 native 상호작용을 추가할 때 browser contract·launch smoke·native UI를 서로 다른
  증거로 유지하고, Issue acceptance마다 어느 계층의 테스트인지 표에 적는다.
- workflow report 필드를 넓힐 때 먼저 bounded schema 테스트를 RED로 만들고, GREEN 후
  canonical/mirrored workflow parity와 `actionlint`를 실행한다.
- 실패에서도 artifact가 남는지와 artifact 경로에 `..`, 절대 경로, credential 이름이
  없는지를 contract test로 고정한다.
- simulator/emulator가 없는 호스트에서는 environment probe의 target 값을 그대로
  기록하고, hosted receipt와 섞어 native PASS나 epic closeout을 선언하지 않는다.
- 이번 실행에서는 runtime receipt를 checklist보다 먼저 bootstrap한 순서 오류가 있었다.
  영향 범위를 checklist에 기록하고, 이후에는 checklist 생성·승인·run 초기화 순서를
  먼저 지킨다.
- hosted run `33204869723`은 Browser와 iOS XCTest가 통과했지만 Android API34 AOSP ATD가
  `MainActivity STOPPED`로 전환되어 Espresso root focus를 잃었다. 네 차례 hosted 재현과
  `test-results.xml`, `window-hierarchy.xml`, `logcat-live.txt` read-back을 근거로 receipt를
  `blocked`로 남긴다.

## 검증 증거

- Angular unit/contract 387건, browser E2E 27건, production build와 Capacitor sync 통과
- native report 8건, workflow 13건, bundle 4건, PWA 3건, bridge 3건 통과
- canonical/mirrored workflow `actionlint` 통과 및 iOS project target/scheme 정적 parse
- local `targets.ios=false`, `targets.android=false`; hosted run `33204869723`의 iOS는
  통과하고 Android는 `RootViewWithoutFocusException`으로 실패했으며 관련 artifact를 보존
