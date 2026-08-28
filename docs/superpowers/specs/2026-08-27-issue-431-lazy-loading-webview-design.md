# Issue #431 Angular 22 번들·lazy loading WebView 검증 설계

## 문제

Issue #23은 기존 Angular production 산출물을 Capacitor `webDir`로 재사용하는
foundation을 고정했고, #430은 WebView와 브라우저가 같은 API·인증 전송 계층을
사용하도록 만들었다. 다음 회귀 위험은 route-level lazy loading이나 hashed asset
경로가 native 정적 자산 복사 과정에서 사라지는 경우와 좁은 mobile viewport에서
초기 화면이 가로로 넘치는 경우다. 새 화면이나 native plugin을 추가하지 않고 현재
Angular 22 산출물을 검사 가능한 계약으로 고정한다.

## 목표와 비목표

### 목표

1. `calendar`, `appointments`, `portal`, `management` 네 root route가 기존
   `loadChildren` 경계를 유지하고 production output에 독립 lazy chunk로
   남아 있는지 확인한다.
2. `dist/appointment-frontend/browser/index.html`의 module script, preload,
   stylesheet가 모두 같은 `webDir` 아래 파일을 참조하는지 확인한다.
3. `angular.json`의 initial/anyComponentStyle budget이 유지되고 production
   build가 budget 초과 없이 종료되는지 기계적으로 검증한다.
4. 320px·375px·393px·430px viewport에서 app shell의 초기 진입과 calendar,
   appointments lazy navigation이 horizontal overflow 없이 완료되는지
   Playwright로 검증한다.

### 비목표

- route 재구성, 새 bundler, runtime loader 또는 새 UI abstraction
- iOS/Android native build와 실제 WebView cookie/network 검증(#24)
- Safe Area·키보드·gesture 보완(#26)
- PWA Service Worker와 offline cache(#25)
- native bridge/deep link(#27)

## 기존 경계

| 경계 | 현재 구현 | 이번 slice의 책임 |
|---|---|---|
| root route | `src/app/app.routes.ts`의 네 `loadChildren` | source contract와 output marker로 네 lazy root 보존 확인 |
| child route | calendar/management의 `loadComponent`, appointments/portal의 child component | route 파일을 변경하지 않고 production chunk 포함 여부 확인 |
| build | `@angular/build:application`, production default | `npm run build`와 후속 bundle contract 실행 |
| native asset | Capacitor `webDir=dist/appointment-frontend/browser` | index·local reference·hashed chunk read-back |
| mobile layout | `App`의 `mobile-layout`·bottom nav·기존 responsive SCSS | 새 CSS 없이 Playwright viewport overflow 계약 |

## 선택한 설계

### 1. build contract script

`frontend/appointment-frontend/scripts/validate-mobile-bundle.mjs`를 추가하고
`npm run bundle:verify`로 호출한다. script는 현재 working directory를 frontend
root로 보고 다음 순서로 fail-fast한다.

1. `dist/appointment-frontend/browser/index.html`과 `angular.json`을 읽는다.
2. index의 `<script src>`, `<link rel="modulepreload">`, stylesheet `href`를
   상대 경로로만 허용하고 파일 존재를 확인한다.
3. 초기 참조 파일의 raw byte 합계를 계산하고 production `initial` budget의
   `maximumError` 이하인지 확인한다. `anyComponentStyle` budget 항목도
   설정에 존재하는지 확인하며 실제 component style 초과 판단은 Angular build에
   위임한다.
4. 네 route source 파일에서 `loadChildren` 또는 child `loadComponent` 동적
   import 경계를 확인한다.
5. production JS chunk에서 `CALENDAR_ROUTES`, `APPOINTMENT_ROUTES`,
   `PATIENT_PORTAL_ROUTES`, `MANAGEMENT_ROUTES` marker가 main 외 lazy file에
   각각 존재하는지 확인한다. marker가 사라지는 bundler 변경은 조용히 통과시키지
   않고 contract 실패로 처리한다.
6. `index.html`, local asset 수, initial bytes, route-to-chunk file 목록을
   JSON 한 줄로 출력한다. 출력에는 인증값이나 환경 비밀을 포함하지 않는다.

이 검사는 Capacitor가 생성한 native project를 다시 만들지 않으며, build 결과와
`webDir` 경계만 읽는다. 해시 파일명은 고정하지 않고 index와 chunk 내용으로
검증하므로 rebuild마다 달라지는 hash를 허용한다.

### 2. mobile lazy navigation smoke

기존 Playwright 설정과 backend mock 경계를 재사용해
`e2e/mobile-lazy-routes.spec.ts`를 추가한다.

- `page.setViewportSize`에 `[320, 375, 393, 430]`을 순차 적용한다.
- `/calendar` 진입 후 오늘/toolbar가 보이고 redirect된 `/calendar/week/:date`
  URL이 안정화되는지 확인한다.
- `/appointments`로 이동해 예약 관리 route가 렌더링되고, `/portal/login`으로
  이동해 환자 로그인 route가 렌더링되는지 확인한다. 각 이동 뒤
  `document.documentElement.scrollWidth <= clientWidth`를 검사한다.
- 각 test는 API 응답을 광범위하게 재구현하지 않고 기존 route가 필요로 하는
  `**/api/**`에 빈 성공 응답을 제공한다. 인증·API payload 계약은 #430 및
  `patient-portal.spec.ts`/`workforce-auth.spec.ts`의 기존 테스트가 소유한다.
- `performance.getEntriesByType('resource')`에서 `/chunk-` JavaScript가
  로드된 사실을 확인해 navigation이 app shell만 재사용하는 false positive를
  막는다.

## 실패와 복구

| 실패 | 판정 | 복구 |
|---|---|---|
| index 참조 파일 누락 | P1, WebView가 시작하지 못함 | build output 또는 `webDir` 경계를 수정하고 build·bundle contract 재실행 |
| route marker가 main 외 chunk에 없음 | P1, lazy loading 경계 회귀 | route source/Angular build 설정을 복구하고 route E2E 재실행 |
| initial budget 초과 | P1, 모바일 초기 로딩 예산 위반 | 새 dependency/UI를 추가하지 않고 기존 bundle 원인을 분리; 설계 변경은 별도 이슈 |
| 320px viewport overflow | P1, 모바일 caller 회귀 | 기존 responsive shell의 최소 CSS만 수정하고 네 viewport E2E 재실행 |
| native build/device 미실행 | N/A, #24 범위 | browser/build 결과를 native PASS로 승격하지 않고 #24에서 toolchain 증거 수집 |

## 호환성·재사용 규칙

- Angular CLI의 production build와 Capacitor `webDir`를 그대로 사용한다.
- `TenantApiClient`, auth scope, XSRF/CORS 계층을 우회하는 route fixture를
  만들지 않는다.
- 새 npm dependency를 추가하지 않는다.
- 기존 Angular Material·responsive SCSS·Playwright를 재사용한다.
- build hash, Node 22, Angular 22 계약은 현재 package/CI 설정을 따른다.

## 수용 기준

- [ ] 네 root route의 source lazy 경계와 production lazy chunk marker가 모두
  contract script에서 확인된다.
- [ ] `webDir` index의 local script/preload/style reference가 모두 존재한다.
- [ ] initial/anyComponentStyle budget 설정이 유지되고 production build가
  성공한다.
- [ ] 320·375·393·430px에서 calendar 초기 진입과 appointments/portal lazy
  navigation이 가로 overflow 없이 성공한다.
- [ ] frontend unit/contract, TypeScript, production build, browser E2E와
  `git diff --check`가 통과한다.

## DoD

구현 review에는 7-Tier(P0/P1/P2/P3) 표, Kotlin/TypeScript/Angular pattern
확인, 변경 파일과 native N/A 경계를 기록한다. lesson에는 해시 산출물 검증,
viewport contract, route marker 선택 이유와 후속 #24 경계를 남긴다. PR은
선행 #433을 base로 하고 전체 Epic #13 완료 전에는 병합하지 않는다.
