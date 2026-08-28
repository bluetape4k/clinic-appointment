# Issue #431 Angular lazy bundle·WebView 구현 7-Tier 검토

## 검토 범위와 기준

- 대상 branch: `feat/issue-431-lazy-loading-webview`
- stacked base: #430 PR #433 head `61824eb360fb564c80b0eb4754edb58cb7308259`
- 범위: 기존 Angular route/build와 Capacitor `webDir`를 읽는 bundle contract,
  fixture 회귀 테스트, 4개 mobile viewport lazy-navigation smoke, README 연결
- 제외: native build/device·cookie bridge(#24/#27), safe area/키보드(#26), PWA(#25)
- 기준: `bluetape-workflow`, `bluetape-full-feature`, `bluetape-writer`,
  `bluetape-kotlin-patterns` 및 모듈별 7-Tier review
- 병합 정책: Epic #13의 모든 stacked slice가 끝나기 전 PR을 병합하지 않는다.

## 7-Tier 결과

| Tier                        |  P0 |  P1 |  P2 |  P3 | 근거와 판정                                                                                                                                                                                                                                                 |
| --------------------------- | --: | --: | --: | --: | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Performance              |   0 |   0 |   1 |   0 | `scripts/validate-mobile-bundle.mjs`는 build 결과를 한 번 읽고 중복 참조를 `Map`으로 합산한다. 새 runtime polling·retry·dependency는 없다. native 초기 로딩 측정은 #24 범위라 N/A이며, 현재 initial `624,621` bytes가 `1,000,000` budget 아래임을 확인했다. |
| 2. Stability                |   0 |   0 |   1 |   0 | index local asset 누락·traversal·budget·lazy marker drift를 fail-closed로 보고한다. marker는 Angular 산출물 내용에 의존하므로 marker 이름을 바꾸는 bundler 변경은 후속 contract 갱신이 필요하다. native lifecycle은 변경하지 않았다.                        |
| 3. Security                 |   0 |   0 |   0 |   0 | `resolveLocalAsset`가 scheme, protocol-relative URL, query/hash, backslash, `..` traversal을 거부한다. JSON report에 token·cookie·환경 비밀을 넣지 않는다. API/auth transport는 #430의 기존 `TenantApiClient`/scope를 그대로 사용한다.                      |
| 4. Operator/Ops             |   0 |   0 |   1 |   0 | `npm run bundle:verify`가 Capacitor `webDir` 산출물을 재현 가능하게 점검하며, 오류는 JSON report와 비영(非零) exit로 노출한다. 실제 Xcode/Android SDK·device smoke는 #24로 분리한다.                                                                        |
| 5. Developer/API            |   0 |   0 |   0 |   0 | 새 npm dependency 없이 Node built-in `node:test`, Angular CLI build, 기존 Playwright 설정을 재사용한다. `bundle:verify`/`test:bundle`은 frontend module boundary에만 추가했고 route source를 재작성하지 않았다.                                             |
| 6. User/Caller              |   0 |   0 |   1 |   0 | `e2e/mobile-lazy-routes.spec.ts`가 320·375·393·430px에서 `/calendar` redirect, `/appointments`, `/portal/login`을 열고 horizontal overflow와 실제 `chunk-*.js` resource를 확인한다. 브라우저 smoke는 native WebView 성공을 의미하지 않는다.                 |
| 7. Main-session integration |   0 |   0 |   0 |   0 | workflow component `issue-431`에 `spec/plan/module-build/module-unit/typescript/browser-e2e/review/diff-check` gate를 등록하고, 문서·코드·review·lesson을 write scope 안에 유지한다. root의 unrelated dirty 파일은 수정하지 않았다.                         |

### 종합 판정

- **P0 = 0, P1 = 0, P2 = 4, P3 = 0**
- P2는 marker 유지보수 책임, native 측정 경계(#24), 운영 device 검증 경계,
  browser smoke를 native PASS로 승격하지 않는 경계다. 구현을 차단하는 결함은
  아니며 후속 issue의 책임으로 남긴다.
- `bluetape-kotlin-patterns`: 이번 변경은 Kotlin production/test 파일을
  수정하지 않아 Kotlin 적용은 N/A다. 기존 #430 backend 테스트와 공통 설정이
  `bluetape4k-assertions`를 사용하며, 이번 frontend slice가 이를 우회하거나
  중복 assertion library를 추가하지 않았다.

## 관점별 확인

### Developer/API

- Angular의 `loadChildren`·`loadComponent`, `@angular/build:application`,
  Capacitor `webDir`, 기존 `playwright.config.ts`를 재사용했다.
- validator는 source contract와 hashed output을 분리해 확인하며 파일 hash를
  고정하지 않는다.
- fixture는 실제 route marker·budget·index reference 문자열을 사용한다.

### User/Caller

- mobile shell의 기존 bottom navigation을 통해 lazy route를 연다.
- `/calendar`는 `week/:date` redirect와 오늘 버튼, `/appointments`는 예약 목록,
  `/portal/login`은 로그인 버튼을 확인한다.
- 모든 viewport에서 `document.documentElement.scrollWidth`가 client width를
  넘지 않았고, local lazy chunk resource가 관찰됐다.

### Performance/Stability/Security/Ops

- production build initial total은 `624.62 kB`이고 validator report의 raw 합계는
  `624621` bytes, initial budget은 `1000000` bytes다.
- route marker는 `chunk-Vk30K7-K.js`, `chunk-D7_wP8av.js`,
  `chunk-hzIDW7H4.js`, `chunk-BXWXd5ow.js`에서 확인됐다.
- traversal/query negative fixture가 fail-closed 동작을 고정한다.
- native SDK/device·cookie·bridge는 검증하지 않았으며 #24/#27의 성공 조건으로
  기록한다.

## 문서·작업 품질 gate

| Gate                         | 결과 | 증거                                                                                                      |
| ---------------------------- | ---- | --------------------------------------------------------------------------------------------------------- |
| SPW-01 Spec                  | PASS | `docs/superpowers/specs/2026-08-27-issue-431-lazy-loading-webview-design.md`                              |
| SPW-02 Plan                  | PASS | `docs/superpowers/plans/2026-08-27-issue-431-lazy-loading-webview-plan.md`; sync/throws traceability 반영 |
| SPW-03 Review                | PASS | 본 문서의 7-Tier·관점별 판정                                                                              |
| SPW-04 Lesson                | PASS | `docs/lessons/2026-08-27-issue-431-lazy-loading-webview.md`                                               |
| SPW-05 Korean artifact audit | PASS | `audit-korean-terms.mjs` 대상 문서 findings=0                                                             |

## 검증 증거

| 영역             | 명령                                                 | 결과                                                                        |
| ---------------- | ---------------------------------------------------- | --------------------------------------------------------------------------- |
| Bundle fixture   | `npm run test:bundle`                                | 4 tests passed, 0 failed                                                    |
| Production build | `npm run build`                                      | Angular production build passed; output `dist/appointment-frontend/browser` |
| Bundle contract  | `npm run bundle:verify`                              | `ok: true`, `initialBytes: 624621`, 4 lazy routes, `failures: []`           |
| Frontend unit    | `npm test -- --watch=false`                          | 47 files, 340 tests passed                                                  |
| TypeScript       | `npx tsc --noEmit -p tsconfig.app.json`              | passed                                                                      |
| Docs contract    | `npm run docs:verify`                                | `ok: true`, documentsChecked=10, sourceChecks=8, failures=[]                |
| Targeted browser | `npm run test:e2e -- e2e/mobile-lazy-routes.spec.ts` | 4 tests passed                                                              |
| Full browser     | `npm run test:e2e`                                   | 16 tests passed                                                             |
| Diff hygiene     | `git diff --check`                                   | passed                                                                      |

최종 PR exact-head CI와 GitHub metadata read-back은 implementation commit/push 뒤
추가한다. 이 문서가 native build/device PASS를 주장하지 않는 것은 의도된 경계다.

## 결론

**PASS — PR 생성·exact-head CI 대기 단계로 진행 가능.** 기존 Angular/Capacitor/
Playwright 경계를 재사용했고 P0/P1은 없다. PR은 다음 #26 slice의 base로만 사용하며
Epic #13 전체 완료 전에는 병합하지 않는다.
