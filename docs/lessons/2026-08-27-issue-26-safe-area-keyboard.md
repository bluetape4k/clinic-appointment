# Issue #26 모바일 Safe Area·키보드·viewport lesson

## 재사용 원칙

이번 slice는 새로운 mobile UI나 Capacitor plugin을 추가하는 일이 아니라, 이미
작동하는 Angular standalone shell과 Capacitor `webDir`가 같은 viewport 계약을
소비하도록 연결하는 일이었다.

- `src/app/shared/index.ts`의 기존 barrel 경계에 `MobileViewportDirective`를 export하고
  portal root·staff mobile layout에서 재사용했다.
- `100dvh`와 `window.innerHeight`를 fallback으로 두고, 지원하는 WebView에서는
  `visualViewport.height`와 keyboard inset을 `--mobile-viewport-height`·
  `--mobile-keyboard-inset` CSS 변수로 공유했다.
- patient auth, portal shell, appointment form과 기존 Angular Material bottom nav는
  공통 Safe Area·scroll padding을 소비한다. 페이지마다 viewport 계산을 복제하지 않았다.
- 기존 `bluetape4k-assertions`는 #430 Kotlin backend 계약의 책임으로 남겨 두고,
  frontend에는 이미 설치된 Vitest·Playwright assertion만 사용했다. Kotlin 파일이
  없는 slice에서 JVM assertion dependency를 억지로 끌어오지 않았다.

## Contract 규칙

`appMobileViewport`는 다음 계약을 지킨다.

1. host의 실제 visual viewport 높이를 양의 정수 pixel CSS 변수로 기록한다.
2. layout viewport와 visual viewport 차이를 keyboard inset으로 기록하고,
   Safe Area bottom과 별도로 `scroll-padding`에만 합산한다.
3. host 내부 `focusin`을 한 번 중앙으로 정렬하고, host 밖 target은 무시한다.
4. `visualViewport`와 `requestAnimationFrame`이 없는 테스트·브라우저에서는 no-op/
   동기 fallback을 사용한다.
5. destroy 시 `resize`·`scroll` listener를 모두 제거한다.

## 검증 규칙

- unit은 CSS 변수 초기/resize 값, focus scroll 호출, listener 제거를 확인한다.
- browser contract는 320·375·393·430px portrait에서 visual viewport·keyboard padding·
  focused input·submit target을 확인하고, 667×375 landscape에서 bottom nav와 overflow를
  확인한다.
- `npm run build`와 `npm run bundle:verify`는 기존 lazy route와 initial bundle budget을
  함께 보호한다. 이번 산출물 initial raw 합계는 `622912` bytes이며 `1MB` budget 아래다.
- `npm run docs:verify`와 Korean artifact audit는 README/spec/plan/review/lesson의
  source contract와 언어 경계를 확인한다.

## 실패와 후속

- Chromium의 visual viewport 조작은 실제 iOS/Android IME·orientation·status bar를
  재현하지 않는다. 이를 PASS로 확대하지 않고 native SDK/device evidence를 #27/#24에
  남긴다.
- visualViewport 이벤트가 고빈도로 발생하는 환경의 성능 측정은 후속 운영 evidence다.
  현재 handler는 CSS 변수 계산만 수행하고 network/native side effect가 없다.
- Angular Material이나 새 route가 추가되어 44px 규칙을 벗어나면 공통 global selector와
  mobile E2E를 먼저 갱신하고, 페이지별 중복 CSS를 만들지 않는다.

## 결과

- 새 npm/native dependency 없이 기존 ecosystem 경계를 재사용했다.
- directive unit 2건, frontend unit 342건, targeted browser 11건, production build,
  TypeScript, bundle/docs validator가 통과했다.
- 다음 stacked slice는 #25이며, #26 PR은 Epic #13 전체 완료 전 merge하지 않는다.
