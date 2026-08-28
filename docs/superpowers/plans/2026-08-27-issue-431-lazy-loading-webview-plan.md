# Issue #431 Angular 22 번들·lazy loading WebView 검증 구현 계획

> **For agentic workers:** 이 계획은 현재 stacked base에서 순서대로 실행한다. 각 단계의 RED·GREEN 증거를 남기고, 전체 Epic #13이 끝나기 전에는 merge하지 않는다.

**목표:** 기존 Angular 22 route-level lazy loading과 Capacitor `webDir` production 산출물을 기계적으로 검증하고 320px 이상 mobile viewport navigation 회귀를 고정한다.

**아키텍처:** route source와 hashed production output을 별도 contract script로 읽는다. Angular CLI가 소유한 budget 판정은 그대로 사용하고, script는 index local references·route marker·initial bytes를 추가로 확인한다. Playwright는 기존 API mock과 app shell을 재사용해 calendar 초기 진입과 appointments/portal lazy navigation의 viewport 계약만 검증한다.

**기술 스택:** Angular 22, TypeScript 6, Node built-in `node:test`, Playwright Chromium, Capacitor 8.5.0 `webDir`.

---

## 파일 구조와 책임

- 생성: `frontend/appointment-frontend/scripts/validate-mobile-bundle.mjs` — source/output/budget contract와 JSON report
- 생성: `frontend/appointment-frontend/scripts/validate-mobile-bundle.test.mjs` — fixture 기반 contract 회귀 테스트
- 수정: `frontend/appointment-frontend/package.json` — `bundle:verify`, `test:bundle` 명령
- 생성: `frontend/appointment-frontend/e2e/mobile-lazy-routes.spec.ts` — 320/375/393/430px lazy navigation smoke
- 수정: `frontend/appointment-frontend/README.md`, `frontend/appointment-frontend/README.ko.md` — WebView bundle 검증 명령과 native N/A 경계
- 생성: `docs/superpowers/specs/2026-08-27-issue-431-lazy-loading-webview-design.md` — 승인된 계약
- 생성: `docs/superpowers/plans/2026-08-27-issue-431-lazy-loading-webview-plan.md` — 실행 계획
- 생성: `docs/superpowers/reviews/2026-08-27-issue-431-lazy-loading-webview-implementation-review.ko.md` — 7-Tier 결과
- 생성: `docs/lessons/2026-08-27-issue-431-lazy-loading-webview.md` — 재사용 가능한 검증 규칙

`app.routes.ts`와 feature route 파일은 변경하지 않는다. 이미 존재하는 `loadChildren`·`loadComponent`와 responsive shell을 검증 대상으로만 사용한다.

## Task 1: 설계·계획 artifact와 workflow evidence 고정

**파일:**

- `docs/superpowers/specs/2026-08-27-issue-431-lazy-loading-webview-design.md`
- `docs/superpowers/plans/2026-08-27-issue-431-lazy-loading-webview-plan.md`

- [x] **Step 1: source와 baseline을 다시 읽는다**

  실행:

  ```bash
  npm ci
  npm test -- --watch=false
  npm run build
  ```

  기대: baseline frontend unit 340 tests, production build 성공, `dist/appointment-frontend/browser/index.html`과 hashed lazy JS가 생성된다.

- [x] **Step 2: spec/plan traceability를 점검한다**

  `app.routes.ts`, 네 feature route, `angular.json`, `capacitor.config.ts`, `playwright.config.ts`의 실제 경계와 spec/plan의 파일·명령·제외 범위를 대조한다. 누락된 acceptance 항목은 구현 전에 문서에 반영한다.

- [x] **Step 3: workflow topology를 갱신한다**

  `spec`, `plan`, `module-build`, `module-unit`, `typescript`, `browser-e2e`, `review`, `diff-check`를 #431 component의 필수 gate로 유지하고 각 artifact를 receipt evidence에 연결한다.

## Task 2: bundle contract를 RED-GREEN으로 추가

**파일:**

- 생성: `frontend/appointment-frontend/scripts/validate-mobile-bundle.mjs`
- 생성: `frontend/appointment-frontend/scripts/validate-mobile-bundle.test.mjs`
- 수정: `frontend/appointment-frontend/package.json`

- [x] **Step 1: failing fixture test를 먼저 작성한다**

  `node:test` fixture는 다음을 검증해야 한다.

  ```js
  test("valid bundle exposes every lazy route and local index asset", async () => {
    const report = validateMobileBundle({ root: fixtureRoot });
    assert.equal(report.ok, true);
    assert.deepEqual(report.lazyRoutes.sort(), [
      "appointments",
      "calendar",
      "management",
      "portal",
    ]);
  });

  test("missing index reference fails closed", async () => {
    await writeFixture({ missing: "chunk-calendar.js" });
    assert.throws(
      () => validateMobileBundle({ root: fixtureRoot }),
      /missing local asset/,
    );
  });

  test("route marker and initial budget drift fail closed", async () => {
    await writeFixture({
      withoutMarker: "MANAGEMENT_ROUTES",
      initialBytes: 1_100_000,
    });
    assert.throws(
      () => validateMobileBundle({ root: fixtureRoot }),
      /lazy route marker|initial budget/,
    );
  });
  ```

  fixture에는 `angular.json`의 `initial=1MB`, `anyComponentStyle=8kB`, 네 source route의 dynamic import, main 외 hashed lazy JS marker, index local references를 실제 문자열로 작성한다.

- [x] **Step 2: RED를 확인한다**

  실행: `npm run test:bundle`

  기대: script가 아직 없으므로 import/contract 오류로 실패한다. 이 결과를 `issue-431-evidence-*`에 기록한다.

- [x] **Step 3: 최소 구현을 작성한다**

  `validateMobileBundle({ root = process.cwd(), distDir, angularConfig })`를 export한다. 구현은 다음 순서를 지킨다.
  1. `dist/appointment-frontend/browser/index.html`, `angular.json`, 네 route source를 읽고 존재를 확인한다.
  2. index의 `script[src]`, `link[rel=modulepreload][href]`, stylesheet `link[href]`를 추출해 absolute URL·`..` traversal을 거부하고 local 파일 존재를 확인한다.
  3. 중복을 제거한 초기 local JS/CSS byte 합계를 구해 `initial.maximumError`보다 작거나 같은지 확인한다. `kB`/`MB` 단위를 decimal byte로 파싱한다.
  4. 네 root route에서 `loadChildren` dynamic import marker를, calendar/management child에서 `loadComponent` dynamic import marker를 확인한다.
  5. main을 제외한 `.js` 파일에서 `CALENDAR_ROUTES`, `APPOINTMENT_ROUTES`, `PATIENT_PORTAL_ROUTES`, `MANAGEMENT_ROUTES`를 각각 찾고 route name→chunk file을 report한다.
  6. `{ ok, index, initialBytes, initialBudgetBytes, lazyRoutes, routeChunks, referencedAssets, failures }`를 반환하고 CLI 실행 시 JSON을 출력하며 실패하면 exit code 1을 설정한다.

- [x] **Step 4: GREEN과 negative cases를 확인한다**

  실행:

  ```bash
  npm run test:bundle
  npm run build
  npm run bundle:verify
  ```

  기대: fixture test 전체 통과, Angular budget 통과, 실제 hashed output에서 네 lazy route와 local references가 보고된다.

## Task 3: mobile lazy navigation smoke를 추가

**파일:** `frontend/appointment-frontend/e2e/mobile-lazy-routes.spec.ts`

- [x] **Step 1: failing E2E를 작성한다**

  기존 Playwright `Page`와 `expect`를 사용해 API wildcard mock, viewport loop, route navigation helper를 작성한다. 각 viewport에서 다음을 실행한다.

  ```ts
  await page.setViewportSize({ width, height: 900 });
  await page.goto("/calendar");
  await expect(page.getByRole("button", { name: "오늘" })).toBeVisible();
  await expect(page).toHaveURL(/\/calendar\/week\/\d{4}-\d{2}-\d{2}$/);
  await page.getByRole("link", { name: "예약 관리" }).click();
  await expect(page).toHaveURL(/\/appointments$/);
  await page.goto("/portal/login");
  await expect(page.getByRole("button", { name: "로그인" })).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          document.documentElement.scrollWidth <=
          document.documentElement.clientWidth,
      ),
    )
    .toBe(true);
  ```

  navigation 뒤 `performance.getEntriesByType('resource')`의 local `/chunk-*.js` 수가 증가하거나 lazy route resource가 존재하는지 검사한다. API payload를 새로 정의하지 않고 기존 mock 응답 형태를 재사용한다.

- [x] **Step 2: RED를 확인한다**

  실행: `npm run test:e2e -- e2e/mobile-lazy-routes.spec.ts`

  기대: 아직 새 spec이 없으므로 지정 test가 발견되지 않거나 route/overflow assertion이 실패한다.

- [x] **Step 3: 최소 E2E 구현으로 GREEN을 만든다**

  API mock은 `**/api/**` GET/POST/PATCH/OPTIONS에 `200` 또는 `204`와 빈 성공 payload를 반환하고, workforce bootstrap은 기존 `__CLINIC_WORKFORCE_AUTH__` fixture를 사용하지 않는 browser shell 범위로 제한한다. `/management`는 role guard가 필요한 기존 workforce 테스트의 책임이므로 이번 E2E에 중복하지 않고 bundle contract에서 검증한다.

- [x] **Step 4: 네 viewport와 전체 E2E를 확인한다**

  실행:

  ```bash
  npm run test:e2e -- e2e/mobile-lazy-routes.spec.ts
  npm run test:e2e
  ```

  기대: 새 smoke와 기존 전체 Chromium suite가 모두 통과하고, `320/375/393/430`에서 overflow가 없다.

## Task 4: README와 계약 검증 연결

**파일:** `frontend/appointment-frontend/README.md`, `frontend/appointment-frontend/README.ko.md`

- [x] **Step 1: 검증 명령을 문서화한다**

  빌드 section에 `npm run bundle:verify`, 테스트 section에 `npm run test:bundle`을 추가하고, 네 lazy root route·Capacitor `webDir`·320px viewport 계약을 한국어로 설명한다.

- [x] **Step 2: 기존 계약 검증을 실행한다**

  실행: `npm run docs:verify`

  기대: Angular 22, tenant/API boundary, 기존 문서 source checks가 모두 통과한다. 실패하면 #431 diff가 만든 drift인지 분리한다.

## Task 5: 7-Tier review와 lesson

**파일:**

- 생성: `docs/superpowers/reviews/2026-08-27-issue-431-lazy-loading-webview-implementation-review.ko.md`
- 생성: `docs/lessons/2026-08-27-issue-431-lazy-loading-webview.md`

- [x] **Step 1: implementation diff를 검토한다**

  Performance, Stability, Security, Operator/Ops, Developer/API, User/Caller, Main-session integration 관점으로 file/line 증거를 기록한다. 새 dependency·runtime route 변경·native claim이 없는지 확인하고 P0/P1=0을 보장한다.

- [x] **Step 2: Kotlin pattern gate를 기록한다**

  이번 slice는 Kotlin production/test 파일을 변경하지 않으므로 `bluetape-kotlin-patterns` 적용은 N/A로 명시한다. TypeScript/Angular에서는 strict typing, no direct DOM mutation, existing router/API reuse, bounded test fixture를 확인한다.

- [x] **Step 3: lesson을 작성한다**

  hashed filename을 고정하지 않고 index reference와 semantic route marker를 검사하는 이유, Angular budget과 script 책임 분리, viewport smoke의 한계, native #24 경계를 기록한다.

## Task 6: 최종 검증·PR-ready evidence

- [x] **Step 1: module validation을 순서대로 실행한다**

  ```bash
  npm run test:bundle
  npm run bundle:verify
  npm test -- --watch=false
  npm run build
  npx tsc --noEmit -p tsconfig.app.json
  npm run docs:verify
  npm run test:e2e
  git diff --check
  ```

- [ ] **Step 2: workflow checks와 changed path를 기록한다**

  8개 required check 결과, local/CI exact head, `git status --porcelain=v1 -z`, `git diff --name-only <base>...HEAD`를 receipt evidence에 첨부한다.

- [ ] **Step 3: commit/push를 수행한다**

  ```bash
  git add frontend/appointment-frontend/scripts frontend/appointment-frontend/package.json frontend/appointment-frontend/e2e/mobile-lazy-routes.spec.ts frontend/appointment-frontend/README.md frontend/appointment-frontend/README.ko.md docs/superpowers/specs/2026-08-27-issue-431-lazy-loading-webview-design.md docs/superpowers/plans/2026-08-27-issue-431-lazy-loading-webview-plan.md docs/superpowers/reviews/2026-08-27-issue-431-lazy-loading-webview-implementation-review.ko.md docs/lessons/2026-08-27-issue-431-lazy-loading-webview.md
  git commit -m "모바일 WebView lazy bundle 계약을 고정한다"
  git push -u origin feat/issue-431-lazy-loading-webview
  ```

  commit에는 Lore trailers를 붙이고 native build/device를 검증했다고 주장하지 않는다.

- [ ] **Step 4: stacked PR과 issue를 갱신한다**

  PR base는 `feat/issue-430-api-auth-contract`, issue #431은 checklist·PR·exact-head CI evidence를 live read-back한다. PR body 마지막 section은 `## DoD Status`로 두고 Required checks를 실제 receipt/CI 결과와 일치시킨다. #432 다음 base가 되지만 Epic 전체 완료 전에는 merge하지 않는다.

## Rerun/rollback

- build output hash가 바뀌어도 `index.html` references와 semantic marker만 재검증한다.
- contract 실패 시 route source와 build output을 먼저 비교하고, route/UI 재작성이나 dependency 추가 없이 원인을 고친다.
- viewport 실패가 기존 shell 회귀이면 최소 SCSS 수정만 별도 plan으로 분리한다.
- native toolchain 부재는 #24 N/A로 남기며 browser 통과를 native PASS로 승격하지 않는다.

## Plan self-review

- Spec의 네 route source/output, index local assets, two budget types, four viewport, browser/build/unit/type/diff gates가 Task 2–4와 6에 모두 매핑되어 있다.
- placeholder·TBD·미정 함수명 없이 실제 파일·명령·marker를 고정했다.
- `validateMobileBundle` 함수명, `bundle:verify`/`test:bundle` script명, report 필드와 E2E route 경로가 모든 task에서 일관된다.
