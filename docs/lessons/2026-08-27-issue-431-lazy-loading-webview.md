# Issue #431 Angular lazy bundle·WebView 검증 lesson

## 재사용 원칙

이번 slice의 핵심은 Capacitor 전용 bundler나 route loader를 새로 만드는 것이
아니라, 이미 저장소가 사용하는 Angular production build 결과를 `webDir` 경계에서
다시 읽어 검증하는 것이다.

- `app.routes.ts`의 `loadChildren`와 feature route의 `loadComponent`를 변경하지
  않고 source contract로 보존한다.
- Angular CLI가 생성한 `index.html`의 script/modulepreload/stylesheet reference와
  실제 같은 디렉터리의 파일을 연결한다.
- 해시 파일명을 고정하지 않고 route export marker를 main 외 JavaScript chunk에서
  찾는다. 따라서 rebuild마다 hash가 바뀌어도 검증은 재사용된다.
- initial budget의 실제 초과 판정은 Angular CLI에 맡기고, validator는 설정 존재와
  index 참조 raw byte 합계를 확인한다. 책임을 중복 구현하지 않는다.
- mobile E2E는 기존 app shell, workforce handoff, `**/api/**` route mock,
  Playwright 설정을 재사용하며 API/auth 구현을 다시 만들지 않는다.

## Contract 규칙

`npm run bundle:verify`는 다음을 fail-closed로 판단한다.

1. `dist/appointment-frontend/browser/index.html`과 production `angular.json`
   budget이 존재한다.
2. index의 local script/modulepreload/stylesheet reference가 존재하고 scheme,
   query/hash, backslash, `..` traversal을 사용하지 않는다.
3. `calendar`, `appointments`, `portal`, `management`가 source dynamic import와
   main 외 semantic marker를 모두 가진다.
4. JSON report는 route-to-chunk, initial bytes, budget, failures만 포함하고
   인증값·cookie·환경 비밀은 포함하지 않는다.

fixture negative case에서 missing reference, traversal/query, marker drift와
initial budget 초과를 각각 고정하면 산출물 누락을 성공으로 오인하지 않는다.

## Mobile viewport 규칙과 한계

320·375·393·430px은 현재 mobile shell이 실제로 소비하는 좁은 viewport 집합이다.
각 폭에서 calendar의 `/calendar/week/:date` redirect, appointments/portal lazy
navigation, local `chunk-*.js` resource, document-level horizontal overflow를
확인한다. child 내부에 의도된 scroll container가 있더라도 WebView 전체 폭이
넘는지는 별도로 판단한다.

이 smoke는 Chromium browser 산출물 증거다. Android/iOS WebView engine,
cookie/SameSite, safe area, keyboard, bridge와 native lifecycle은 각각 #24/#26/#27의
후속 신뢰 경계이며 여기서 PASS로 승격하지 않는다.

## 후속 적용

- Angular builder나 route export marker를 변경하면 `validate-mobile-bundle.mjs`와
  fixture를 함께 갱신하고 `npm run bundle:verify`를 재실행한다.
- 화면·dependency 추가로 initial bytes가 budget에 가까워지면 원인별 별도 issue를
  만들고 budget을 임의로 늘리지 않는다.
- Capacitor native sync/실기기 검증을 추가할 때는 browser E2E 결과를 복사하지 않고
  #24의 SDK·emulator/device 증거를 별도로 수집한다.

## 검증 결과

- bundle fixture 4 tests, frontend unit 340 tests, targeted 4/full 16 Chromium
  scenarios, TypeScript, production build와 docs contract가 통과했다.
- 현재 산출물 initial raw 합계는 `624621` bytes이며 `1MB` initial budget 아래다.
- native SDK/device 및 실제 WebView cookie/bridge는 실행하지 않았고 후속 issue로
  추적한다.
