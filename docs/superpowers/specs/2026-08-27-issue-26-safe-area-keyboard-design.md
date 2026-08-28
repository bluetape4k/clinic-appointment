# 모바일 Safe Area·키보드·뷰포트 보완 설계

## 목표

Epic #13의 네 번째 stacked slice로서 기존 Angular 22·Angular Material·Capacitor
WebView 셸을 재사용하고, iOS·Android WebView에서 Safe Area, 소프트 키보드,
orientation/viewport resize, 입력 focus 흐름이 가려지지 않는 계약을 브라우저에서
재현 가능하게 검증한다. native SDK·플러그인·release signing은 후속 #27·#24 범위로
남긴다.

## 현재 근거

- `src/index.html`은 `viewport-fit=cover`를 이미 선언한다.
- `app.scss`의 staff mobile shell은 `100vh`, 내부 scroll container, 하단 nav와
  `env(safe-area-inset-bottom)`을 사용한다.
- 환자 포털 shell과 인증/예약 폼은 독립적인 scroll 경계를 가지며 입력 요소가 많다.
- #431은 Angular production lazy bundle과 320·375·393·430px viewport 계약을
  이미 고정했으므로 이 slice는 그 위에서 viewport 높이와 focus 동작만 보완한다.
- Capacitor `8.5.0` core/iOS/Android/CLI와 기존 generated native project를
  유지한다. 새 plugin 의존성은 추가하지 않는다.

## 선택한 접근

### A안 — CSS 동적 뷰포트 + 재사용 가능한 Angular focus/viewport directive (채택)

`100dvh`를 기본으로 사용하되 WebView의 `visualViewport`가 제공하는 실제 높이와
키보드 inset을 standalone directive가 host CSS custom property로 반영한다. directive는
기존 staff mobile layout과 portal root에만 부착하고, `focusin` 시 대상 요소를
`scrollIntoView({ block: 'center', inline: 'nearest' })`로 한 번 정렬한다. CSS의
`scroll-padding`과 Safe Area `env()`를 함께 사용하여 하단 nav와 키보드가 form action을
가리지 않게 한다. API·도메인 모델·native bridge는 건드리지 않는다.

장점은 새 runtime dependency 없이 Angular lifecycle에서 listener를 정리하고,
브라우저 테스트와 실제 WebView가 같은 CSS 계약을 공유한다는 점이다. `visualViewport`
미지원 환경은 `100dvh`/`innerHeight` fallback으로 정상 동작한다.

### B안 — Capacitor Keyboard/StatusBar plugin 추가

네이티브 keyboard 이벤트와 status-bar inset을 직접 수신하는 방식이다. 실제 iOS·Android
SDK 증거가 없는 현재 호스트에서 plugin 설정과 permission/manifest 차이를 검증할 수
없고, #27의 native device 계약과 중복되므로 채택하지 않는다.

### C안 — 각 페이지의 form CSS만 개별 수정

auth/예약/관리 페이지마다 padding과 높이를 복제하는 방식이다. 새 form이나 lazy route가
추가될 때 누락되기 쉽고, portal/staff의 scroll 경계가 서로 달라 동일한 keyboard 계약을
보장하지 못하므로 채택하지 않는다.

## 컴포넌트와 데이터 흐름

1. `App` template의 `.portal-root`와 `.mobile-layout`에 `appMobileViewport`를
   선언한다.
2. directive는 `window.visualViewport.resize|scroll`과 host `focusin`을 구독한다.
   높이, `keyboard-inset`을 픽셀 custom property로 갱신하고 destroy 시 listener를
   제거한다.
3. `app.scss`, portal shell/auth/appointment form의 공통 스타일은
   `var(--mobile-viewport-height, 100dvh)`와 `env(safe-area-inset-*)`를 사용한다.
4. Playwright는 desktop browser에서 320·375·393·430px portrait와 짧은 landscape
   viewport를 순회하고, focus 후 요소/submit action의 가시성·overflow·touch target을
   측정한다. native build/device 검증은 결과에 포함하지 않고 #27/#24로 연결한다.

## 오류·호환성 정책

- `window`, `visualViewport`, `requestAnimationFrame`이 없는 단위 테스트 환경에서는
  no-op fallback을 사용한다.
- 높이 값은 0 이하를 무시하고 `innerHeight` 또는 CSS fallback을 유지한다.
- focus 대상이 host 밖이거나 `HTMLElement`가 아니면 아무 동작도 하지 않는다.
- listener 예외가 Angular change detection을 깨뜨리지 않도록 동기 계산만 수행하고,
  임의의 network/native 호출은 하지 않는다.
- keyboard inset은 Safe Area bottom과 합산하지 않고, scroll padding에만 보정값으로
  사용하여 이중 여백을 방지한다.

## 수용 기준

1. staff mobile shell과 patient portal/auth가 `100dvh`/visualViewport 높이를 사용하고
   Safe Area top/bottom을 보존한다.
2. focus된 input이 320·375·393·430px portrait 및 짧은 landscape viewport에서
   scroll 경계 안으로 이동하고, submit/action 요소가 가려지지 않는다.
3. bottom nav와 주요 button의 실제 CSS 최소 터치 높이가 44px 이상이며 기존
   desktop layout에는 영향을 주지 않는다.
4. visualViewport resize/scroll listener가 directive destroy 후 남지 않는다.
5. 새 npm/native dependency 없이 기존 Angular/Capacitor 경계를 재사용한다.
6. bundle contract, frontend unit/contract, TypeScript, production build, browser E2E,
   docs contract, diff check가 통과한다.

## 범위 제외와 후속

- Xcode/Android SDK build, iOS keyboard accessory, Android back button, status-bar/
  keyboard Capacitor plugin, real device orientation/IME evidence는 #27/#24에서
  별도로 수행한다.
- Angular Material 컴포넌트를 새 UI 라이브러리로 교체하지 않는다.
- API/JWT/cookie 계약과 backend Kotlin 코드는 변경하지 않는다. 따라서 이 slice의
  Kotlin production/test 및 `bluetape4k-assertions` 직접 적용은 N/A이며, #430의
  backend assertion 계약은 그대로 재사용한다.

## 실패 모드와 완화

| 실패 모드 | 완화 및 검증 |
|---|---|
| WebView가 layout viewport만 resize하여 키보드가 content를 덮음 | `visualViewport.height`와 keyboard inset을 CSS 변수로 갱신하고 focus E2E에서 action 가시성을 측정 |
| iOS Safe Area와 keyboard inset을 중복 적용 | bottom nav는 `env()`만, scroll padding은 keyboard inset만 사용하도록 스타일 계약과 회귀 테스트 고정 |
| route destroy 뒤 viewport listener가 남아 메모리/중복 scroll 발생 | directive 단위 테스트에서 add/remove listener 균형 확인 |
| 좁은 화면에서 기존 inline form이 수평 overflow | portrait·landscape E2E의 `scrollWidth/clientWidth` 검사와 form grid breakpoint 유지 |
| native device 차이를 browser 통과로 오판 | README·lesson·Issue #26에 native 검증 경계를 명시하고 #27/#24로 연결 |

## 완료 정의

- 구현·테스트·문서가 이 설계의 수용 기준과 traceability를 가진다.
- 최종 7-Tier review에서 P0/P1이 0이고 P2/P3는 수정 또는 후속 이슈로 기록된다.
- exact-head PR CI와 local evidence를 Issue #26/PR에 live read-back한다.
- Epic stacked train 규칙에 따라 PR은 열어 둔 채 merge하지 않고, 다음 #25의 base로
  exact head를 전달한다.
