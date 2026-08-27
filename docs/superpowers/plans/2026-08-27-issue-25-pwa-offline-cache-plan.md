# PWA·Service Worker·오프라인 캐시 구현 계획

> **For agentic workers:** 이 계획은 #26 exact head 위의 Type A stacked worktree에서 순서대로 실행한다. 각 gate는 workflow receipt에 기록하고 PR은 merge하지 않는다.

**목표:** Angular PWA app shell과 제한된 public master-data offline cache를 추가하되, tenant/auth 데이터와 예약 mutation은 영속 캐시에서 배제하고 사용자에게 offline/update 상태를 명시한다.

**구조:** `@angular/pwa` schematic이 생성하는 Angular Service Worker 산출물을 현재 Angular application builder와 Capacitor `webDir`에 연결한다. `PwaStatusService`는 `SwUpdate`·online/offline 이벤트를 signal로 공유하고, `pwaNetworkInterceptor`는 auth/credentials request를 `ngsw-bypass`로 우회하며 mutation을 no-store/network-only로 고정한다. `ngsw-config.json` data group은 `/api/public/master-data/**`만 허용한다.

**기술:** Angular 22 standalone, TypeScript 6, `@angular/service-worker` 22.1.x, `@angular/pwa` 22.1.x, SCSS, Vitest, Playwright, Capacitor 8.5.0.

## 설계 traceability

| 수용 기준 | 구현/검증 단계 |
|---|---|
| manifest·installability metadata | Task 2, Task 5 manifest/build contract |
| versioned app shell·update | Task 2–3, Task 5 production artifact/E2E |
| safe public read-only cache | Task 2 config, Task 3 interceptor/config tests |
| auth·cookie·JWT·mutation 비캐시 | Task 1 RED, Task 3 GREEN, Task 5 offline E2E |
| offline/online/update/reset UX | Task 1–4 service/App tests, Task 5 browser contract |
| 새 dependency 최소화·재사용 | Task 2 package diff, Task 6 review/lesson |
| native 경계 정직성 | Task 6 README/Issue/PR, #27/#24 handoff |

## Task 1: RED — PWA 상태·network policy 계약 테스트

**Files:**

- Create: `frontend/appointment-frontend/src/app/core/services/pwa-status.service.spec.ts`
- Create: `frontend/appointment-frontend/src/app/core/interceptors/pwa-network.interceptor.spec.ts`
- Modify: `frontend/appointment-frontend/src/app/app.spec.ts`

- [ ] **Step 1: 상태 service 실패 테스트를 먼저 작성한다**

  `SwUpdate` optional double과 fake `Window` event를 사용해 online/offline signal,
  `VERSION_READY`, update apply, `ngsw:` cache reset, 외부 cache 보존을 명시한다.

- [ ] **Step 2: network interceptor 실패 테스트를 먼저 작성한다**

  auth-scoped GET은 `ngsw-bypass`, mutation은 `Cache-Control: no-store`를 요구하고,
  `navigator.onLine=false`인 POST는 next handler를 호출하지 않고 status 0 오류를
  반환하도록 고정한다. `API_AUTH_SCOPE='none'`인 public GET은 bypass하지 않는다.

- [ ] **Step 3: App status region 실패 테스트를 작성한다**

  offline signal과 update available signal에 따라 `data-pwa-status`, update/reset
  action이 렌더링되는지 확인한다.

- [ ] **Step 4: RED를 확인한다**

  Run: `cd frontend/appointment-frontend && npm test -- --watch=false --include='src/app/core/services/pwa-status.service.spec.ts' --include='src/app/core/interceptors/pwa-network.interceptor.spec.ts'`

  Expected: service/interceptor/symbol이 없어서 실패한다. 즉시 통과하면 테스트가
  구현을 우회하지 않는지 먼저 확인한다.

## Task 2: Angular PWA wiring·manifest·config

**Files:**

- Modify: `frontend/appointment-frontend/package.json`
- Modify: `frontend/appointment-frontend/package-lock.json`
- Modify: `frontend/appointment-frontend/angular.json`
- Modify: `frontend/appointment-frontend/src/app/app.config.ts`
- Modify: `frontend/appointment-frontend/src/index.html`
- Create: `frontend/appointment-frontend/ngsw-config.json`
- Create: `frontend/appointment-frontend/public/manifest.webmanifest`
- Create: `frontend/appointment-frontend/public/icons/icon.svg`

- [ ] **Step 1: Angular 22.1 계열 dependency를 추가한다**

  `@angular/pwa@22.1.5`는 schematic/tooling dependency로, `@angular/service-worker@22.1.3`는 runtime dependency로 추가한다. Angular core/CLI와 major/minor 경계를 섞지 않는다. lockfile은 `npm install`로 갱신한다.

- [ ] **Step 2: service worker registration을 production에만 연결한다**

  `app.config.ts`에 `provideServiceWorker('ngsw-worker.js', { enabled: environment.production, registrationStrategy: 'registerWhenStable:30000' })`를 추가한다. unit/test와 development에서는 worker를 활성화하지 않는다.

- [ ] **Step 3: build option과 manifest metadata를 연결한다**

  `angular.json` build options에 `serviceWorker: 'ngsw-config.json'`을 추가하고,
  `index.html`에 `manifest.webmanifest`, `theme-color`, `apple-mobile-web-app-capable`
  metadata를 추가한다. public icon은 텍스트/색상만 담은 안전한 SVG로 두고 binary/icon
  generator dependency는 만들지 않는다.

- [ ] **Step 4: `ngsw-config.json`을 좁은 계약으로 작성한다**

  app-shell asset group은 index·hashed JS/CSS·favicon·manifest·icons를 prefetch한다.
  data group은 `/api/public/master-data/**` 하나만 `freshness`, bounded `maxSize`와
  짧은 `maxAge`로 선언한다. `/api/**`는 navigation fallback에서 제외하고 auth,
  patient, appointment, admin 경로를 config에 넣지 않는다.

- [ ] **Step 5: manifest/config RED를 확인한다**

  Run: `cd frontend/appointment-frontend && npm run build`

  Expected: 아직 status/interceptor 테스트는 실패하지만 PWA artifact generation은
  config schema 또는 missing dependency 오류 없이 진행돼야 한다.

## Task 3: GREEN — status service·network interceptor 구현

**Files:**

- Create: `frontend/appointment-frontend/src/app/core/services/pwa-status.service.ts`
- Create: `frontend/appointment-frontend/src/app/core/interceptors/pwa-network.interceptor.ts`
- Modify: `frontend/appointment-frontend/src/app/core/interceptors/index.ts` (존재 시)
- Modify: `frontend/appointment-frontend/src/app/app.config.ts`

- [ ] **Step 1: `PwaStatusService`를 구현한다**

  `DOCUMENT`와 optional `SwUpdate`를 주입하고 online/offline, `VERSION_READY`,
  `activateUpdate`, `resetCache`를 signal 기반으로 제공한다. reset은 `caches.keys()` 중
  `ngsw:` prefix만 지우고 외부 cache를 건드리지 않는다. 실패는 notice signal에 담아
  UI가 사용자에게 표시할 수 있게 한다.

- [ ] **Step 2: `pwaNetworkInterceptor`를 구현한다**

  `API_AUTH_SCOPE !== 'none'` 또는 `withCredentials` 요청에는 `ngsw-bypass: true`를
  추가한다. POST/PUT/PATCH/DELETE에는 `Cache-Control: no-store`와 `Pragma: no-cache`를
  추가한다. navigator online이 명시적으로 false이면 mutation을 status 0
  `HttpErrorResponse`로 종료한다. queue·background sync·성공 위조는 구현하지 않는다.

- [ ] **Step 3: interceptor 순서를 고정한다**

  `withInterceptors`에서 `pwaNetworkInterceptor`를 auth/xsrf/error 앞에 둔다. API
  context와 기존 `TenantApiClient`가 전달하는 cookie/bearer 경계를 재사용한다.

- [ ] **Step 4: GREEN을 확인한다**

  Run: `cd frontend/appointment-frontend && npm test -- --watch=false --include='src/app/core/services/pwa-status.service.spec.ts' --include='src/app/core/interceptors/pwa-network.interceptor.spec.ts'`

  Expected: 상태, cache reset, update, bypass, no-store, offline mutation 계약이 모두
  통과한다.

## Task 4: App UX·status region 통합

**Files:**

- Modify: `frontend/appointment-frontend/src/app/app.ts`
- Modify: `frontend/appointment-frontend/src/app/app.html`
- Modify: `frontend/appointment-frontend/src/app/app.scss`
- Modify: `frontend/appointment-frontend/src/app/app.spec.ts`

- [ ] **Step 1: App이 status service를 재사용한다**

  app root에 `PwaStatusService`를 inject하고 offline/update 상태를 template에 노출한다.
  `role=status`, `aria-live=polite`, `data-pwa-status` marker를 유지한다.

- [ ] **Step 2: update/reset action을 연결한다**

  update available일 때 `새 버전 적용`, offline/update 상태일 때 `캐시 초기화` action을
  표시한다. action은 service method를 호출하고 disabled/loading 상태를 제공한다.

- [ ] **Step 3: 기존 shell layout을 보존한다**

  status region은 toolbar/nav를 밀어내지 않는 작은 block으로 두고 기존
  `appMobileViewport`, Angular Material, Safe Area CSS 변수와 충돌하지 않게 한다.

- [ ] **Step 4: app/service unit을 재실행한다**

  Run: `cd frontend/appointment-frontend && npm test -- --watch=false`

  Expected: 기존 전체 unit과 신규 PWA 상태/App 계약이 통과한다.

## Task 5: manifest·offline browser contract

**Files:**

- Create: `frontend/appointment-frontend/scripts/validate-pwa-contract.mjs`
- Create: `frontend/appointment-frontend/scripts/validate-pwa-contract.test.mjs`
- Modify: `frontend/appointment-frontend/package.json`
- Modify: `frontend/appointment-frontend/e2e/patient-portal.spec.ts`
- Modify: `frontend/appointment-frontend/e2e/mobile-lazy-routes.spec.ts`

- [ ] **Step 1: static contract validator를 RED-GREEN으로 작성한다**

  `dist/appointment-frontend/browser/manifest.webmanifest`, `ngsw.json`, `ngsw-worker.js`
  존재·JSON schema·start/display/theme/icons·assetGroups/dataGroups·`/api/**` navigation
  exclusion을 검사한다. forbidden auth/patient/appointment/admin patterns가 data group에
  없고 public pattern이 하나뿐인지 확인한다.

- [ ] **Step 2: package script를 추가한다**

  `npm run pwa:verify`가 build 산출물을 검사하고, `npm run test:pwa`가 validator
  node:test를 실행하도록 한다. existing `bundle:verify`와 겹치는 route logic은 복제하지
  않고 PWA artifact만 검사한다.

- [ ] **Step 3: browser E2E를 추가한다**

  production preview에서 manifest link/metadata와 `data-pwa-status` marker를 확인한다.
  context offline 상태에서 mutation submit이 성공 문구나 fake response를 만들지 않고
  status 0 경계를 유지하는 시나리오를 추가한다. service worker update/reset은 API
  mock이 아닌 deterministic `caches`/`SwUpdate` unit contract로 검증한다.

- [ ] **Step 4: targeted GREEN을 확인한다**

  ```bash
  cd frontend/appointment-frontend
  npm run test:pwa
  npm run build
  npm run pwa:verify
  npm run test:e2e -- e2e/patient-portal.spec.ts e2e/mobile-lazy-routes.spec.ts
  ```

  Expected: PWA artifact, config boundary, offline mutation browser contract가 통과한다.
  실기기 WebView installability로 해석하지 않는다.

## Task 6: 문서·7-Tier review·로컬 전체 검증

**Files:**

- Modify: `frontend/appointment-frontend/README.md`
- Modify: `frontend/appointment-frontend/README.ko.md`
- Create: `docs/lessons/2026-08-27-issue-25-pwa-offline-cache.md`
- Create: `docs/superpowers/reviews/2026-08-27-issue-25-pwa-offline-cache-implementation-review.ko.md`

- [ ] **Step 1: README와 lesson을 갱신한다**

  HTTPS 운영/localhost 개발 예외, install/update/reset 조작, public master-data cache
  boundary, auth/mutation network-only 정책, native #27/#24 제외 범위를 Korean-only
  규칙에 맞게 기록한다.

- [ ] **Step 2: 7-Tier review를 기록한다**

  Performance, Stability, Security, Operator/Ops, Developer/API, User/Caller,
  Main-session integration 관점으로 최신 diff를 검토한다. P0=0/P1=0을 필수로 하고
  P2/P3는 수정하거나 public backend/native 후속으로 명시한다. Kotlin production/test와
  `bluetape4k-assertions`는 변경 scope가 TypeScript/JSON/SCSS임을 근거로 N/A 기록하고
  #430의 backend assertion 계약을 유지한다.

- [ ] **Step 3: 로컬 전체 검증을 실행한다**

  ```bash
  cd frontend/appointment-frontend
  npm run test:pwa
  npm run test:bundle
  npm run build
  npm run bundle:verify
  npm run pwa:verify
  npm test -- --watch=false
  npx tsc --noEmit -p tsconfig.app.json
  npm run test:e2e
  npm run docs:verify
  git diff --check
  ```

- [ ] **Step 4: writer/terminology gate를 통과한다**

  `audit-korean-terms.mjs`와 Prettier를 변경 문서/소스에 실행하고 `TBD|TODO`를
  제거한다. SPW-01..05 및 config forbidden-path 결과를 review evidence에 남긴다.

## Task 7: workflow receipt·commit·stacked PR

**Files:**

- Modify: `.bluetape` via `bluetape-flow.py` only
- Update: live Issue #25 and PR body/metadata

- [ ] **Step 1: required checks를 순서대로 기록한다**

  `spec`, `plan`, `module-build`, `module-unit`, `typescript`, `browser-e2e`, `review`,
  `diff-check`를 exact head로 `check-result`에 기록하고 component/lane evidence를
  부착한다.

- [ ] **Step 2: Lore commit으로 저장하고 push한다**

  변경된 frontend/docs만 stage하고 Korean Lore trailers를 포함한 commit을 만든다.
  root의 unrelated dirty `angular.json`, `appointment-event/README.ko.md`, `.superpowers`,
  `.workflow-inputs`는 stage하지 않는다.

- [ ] **Step 3: PR을 #26 exact head 위에 생성한다**

  base `feat/issue-26-pwa-offline-cache`의 parent는
  `feat/issue-26-safe-area-keyboard` exact head `bd27c0c6...`이며, PR head는
  `feat/issue-25-pwa-offline-cache`다. title/body/labels/assignee/milestone을 live
  read-back하고 exact-head CI 두 workflow를 dispatch한다. PR은 merge하지 않는다.

- [ ] **Step 4: Issue #25/PR handoff를 완료한다**

  Issue checklist, PR URL, full head SHA, CI URLs, local metrics, receipt/live report와
  #27/#24 native 후속 unchecked 경계를 업데이트한다. 생성된 PR 번호와 exact-head CI
  URL은 live 결과로 기록하고 추정하지 않는다. Epic 전체 완료 전에는 any merge를 수행하지 않는다.
