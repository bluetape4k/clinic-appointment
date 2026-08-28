# Issue #430 API·인증 전송 계약 구현 계획

> 이 계획은 #430 한 slice만 다룬다. 기준은 #23 PR #432의
> `feat/issue-23-capacitor-foundation`이며, #430 PR은 그 위에 쌓고 병합하지
> 않는다. #24 실기기 검증과 #27 native bridge는 후속 slice다.

## 목표와 고정 경계

- **목표:** browser와 Capacitor WebView가 동일한 `TenantApiClient`·auth scope를
  사용하면서 API origin, credentials, CORS, CSRF 계약을 재현 가능하게 검증한다.
- **frontend:** Angular 22, Capacitor 8.5.0, existing `HttpXsrfTokenExtractor`,
  `TenantContextService`, `AuthService`, `PatientAuthService`, `SessionStateService` 재사용
- **backend:** Spring Security 7 CORS integration과 immutable
  `@ConfigurationProperties`; 새 third-party dependency 없음
- **기준 base:** `c2275ff9dc16c6e64829ffb4da9015331a84be0a`
- **작업 branch/worktree:** `feat/issue-430-api-auth-contract`,
  `.worktrees/issue-430-api-auth-contract`
- **승인:** 사용자 2026-08-27 `승인` 후 실행
- **변경하지 않음:** JWT 발급/검증, patient cookie 속성, offline queue, push, native
  secure storage/cookie bridge, #23 PR merge

## 파일 책임 지도

### Frontend

- Create `frontend/appointment-frontend/src/app/core/api/api-endpoint.ts` — typed
  environment/runtime origin validation, tenant-aware URL composition helpers
- Create `frontend/appointment-frontend/src/app/core/interceptors/xsrf.interceptor.ts` —
  patient unsafe request cross-origin XSRF header reuse
- Modify `frontend/appointment-frontend/src/environments/environment.ts` —
  `apiOrigin`/`apiBasePath` development defaults
- Modify `frontend/appointment-frontend/src/environments/environment.prod.ts` —
  production explicit origin contract
- Modify `frontend/appointment-frontend/src/app/core/api/tenant-api-client.ts` —
  endpoint helper and patient credentials invariant
- Modify `frontend/appointment-frontend/src/app/app.config.ts` — interceptor registration
- Modify `frontend/appointment-frontend/src/app/core/api/tenant-api-client.spec.ts` —
  origin/credentials regressions
- Create `frontend/appointment-frontend/src/app/core/api/api-endpoint.spec.ts`
- Create `frontend/appointment-frontend/src/app/core/interceptors/xsrf.interceptor.spec.ts`
- Modify `frontend/appointment-frontend/src/app/core/api/tenant-api-contract.spec.ts` —
  no raw API origin/storage bypass
- Modify `scripts/validate-frontend-contract.mjs` — verify shared `TenantApiClient`
  transport and auth scopes instead of stale raw `environment.apiUrl` strings
- Create `frontend/appointment-frontend/e2e/api-origin-contract.spec.ts` — absolute API
  origin and patient XSRF request evidence
- Modify `frontend/appointment-frontend/README.md` and `README.ko.md` — origin/CORS/
  cookie/CSRF setup and #24/#27 boundary

### Backend

- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ApiCorsProperties.kt`
  — immutable `scheduling.security.cors` contract and validation
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ApiCorsConfiguration.kt`
  — always-available source with `/api/**` mapping only when enabled
- Modify `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
  — `.cors {}` before authentication/CSRF rules in protected and no-op chains
- Modify `appointment-api/src/main/resources/application.yml` — disabled-by-default
  properties and operator comments
- Modify `appointment-api/src/test/resources/application-test.yml` only if a test
  needs an explicit enabled profile; retain disabled baseline otherwise
- Create `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ApiCorsPropertiesTest.kt`
- Create `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ApiCorsConfigurationTest.kt`

### Durable artifacts

- Create `docs/superpowers/reviews/2026-08-27-issue-430-api-auth-contract-spec-review.ko.md`
- Create `docs/superpowers/reviews/2026-08-27-issue-430-api-auth-contract-plan-review.ko.md`
- Create `docs/superpowers/reviews/2026-08-27-issue-430-api-auth-contract-implementation-review.ko.md`
- Create `docs/lessons/2026-08-27-issue-430-api-auth-contract.md`
- Preserve official Angular/Spring CORS research in the `bluetape4k-wiki` repository
  under `research/2026-08-27-clinic-appointment-api-auth-cors.md` when the code contract
  is frozen.

## Task 0 — receipt, topology, and clean baseline

- [x] Verify workflow run `20260827T060254Z-9b1e2453`, lane `main-issue-430`, component
  `issue-430`, and current receipt checksum with `bluetape-flow.py verify`.
- [x] Register combined `module-build`/`module-unit` checks in the component topology
  so backend CORS compile/test cannot be omitted; each evidence entry still names the
  separate frontend and backend commands.
- [x] Verify feature worktree base SHA is #23 head and root dirty files are
  outside this worktree.
- [x] Fresh frontend baseline: `npm ci`; `npm test -- --watch=false` — 45 files,
  327 tests, 0 failures.
- [x] Fresh backend baseline: `./gradlew :appointment-api:compileKotlin` and the
  focused existing security/property tests. Record failures before implementation.

## Task 1 — spec/plan review and commits

- [x] Write this spec and plan from current source, issue #430, #295 anchors, and official
  Angular/Spring references.
- [x] Run six perspective review for spec and plan: performance, stability, security,
  operator, developer/API, user/caller; then main-session integration. See
  `docs/superpowers/reviews/2026-08-27-issue-430-api-auth-contract-plan-review.ko.md`.
- [x] Run `audit-korean-terms.mjs` on spec/plan/review artifacts and `git diff --check`.
- [x] Commit spec, plan, and review with a Korean Lore message before implementation.

## Task 2 — RED frontend endpoint and XSRF contracts

- [x] Add failing endpoint tests for empty/same-origin, trailing slash, runtime override,
  credentials/path rejection, production/native HTTPS enforcement, and tenant URL output.
- [x] Add failing `TenantApiClient` tests for patient credentials default/explicit false,
  workforce no-cookie behavior, and no patient token storage.
- [x] Add failing XSRF interceptor tests for patient unsafe cross-origin header, safe
  method/scope skip, missing token, and caller header preservation.
- [x] Run only the focused Vitest files and record RED failures tied to missing symbols or
  behavior. Do not weaken assertions to make the test pass.

## Task 3 — GREEN frontend implementation

- [x] Implement typed endpoint configuration without adding dependencies. Runtime override
  reads only `globalThis.__CLINIC_API_CONFIG__` and is validated like environment input.
- [x] Keep empty origin as browser same-origin/proxy (including production same-origin);
  require explicit HTTPS origin on native and production non-empty values; reject
  credentials, path, query, fragment, wildcard, and unsupported schemes.
- [x] Keep `TenantApiClient` responsible for tenant encoding and auth context. Default
  patient cookie requests to `withCredentials=true`; reject explicit false to prevent
  silent cookie omission.
- [x] Register custom XSRF interceptor alongside existing auth/error interceptors. Use
  Angular's `HttpXsrfTokenExtractor`; do not read `document.cookie` or storage directly.
- [x] Run focused tests GREEN, then frontend build, TypeScript, full unit tests: focused
  24/24, full 47 files/340 tests, production build, and `tsc --noEmit` passed.

## Task 4 — RED/GREEN backend CORS contract

- [x] Add failing property tests for defaults, enabled origin requirement, wildcard
  rejection, HTTPS/localhost rule, and invalid origin syntax.
- [x] Add failing source tests for `/api/**` mapping, methods/headers/exposed headers,
  credentials, and maxAge.
- [x] Implement immutable `ApiCorsProperties` with explicit defaults and
  `@EnableConfigurationProperties` configuration. The source bean is always present
  so Spring Security contexts remain valid; it registers the `/api/**` mapping only
  when `scheduling.security.cors.enabled=true`.
- [x] Add `.cors {}` to protected and no-op chains without changing CSRF/auth rules.
- [x] Run focused CORS tests, `:appointment-api:compileKotlin`, and `:appointment-api:test`:
  focused CORS 6 tests, security context 3 tests, and full API 906 tests (3 skipped)
  passed.

## Task 5 — E2E and documentation

- [x] Add Playwright contract that injects a runtime HTTPS origin, sets an XSRF cookie,
  routes absolute `/api/{tenantCode}` login/session/mutation calls, and asserts
  `withCredentials`, `X-XSRF-TOKEN`, tenant path, and existing patient failure state.
- [x] Keep existing browser proxy scenarios unchanged and run complete `npm run test:e2e`:
  12 Chromium scenarios passed, including the new absolute-origin login/mutation/logout
  contract.
- [x] Update both Korean frontend README variants and API `application.yml` with explicit
  production/native origin/CORS setup, no patient JWT storage, and SameSite limitation.
- [x] Run docs validator and Korean terminology audit on every changed Korean artifact.

## Task 6 — 7-Tier review, fix, and lesson

- [x] Inspect final diff per module slice. Record Tier 1 performance, Tier 2 stability,
  Tier 3 security, Tier 4 ops, Tier 5 developer/API, Tier 6 caller, and main-session
  integration findings with file/line evidence in
  `docs/superpowers/reviews/2026-08-27-issue-430-api-auth-contract-implementation-review.ko.md`.
- [x] Run production concurrency quick scan on changed Kotlin roots and document each hit
  as intentional/N/A; no new blocking calls or broad catches are allowed.
- [x] Apply Kotlin final checklist KT-FIN-01..11 and frontend contract checks. All new
  Kotlin tests use `io.bluetape4k.assertions` (no JUnit/AssertJ/Kluent assertions).
- [x] Fix all P0/P1 findings and rerun affected lanes until `P0 = 0, P1 = 0`. Defer only
  evidence-backed P2/P3 with a linked issue or explicit #24/#27 boundary.
- [x] Write lesson with reusable ecosystem patterns, SameSite boundary, and exact test
  evidence in `docs/lessons/2026-08-27-issue-430-api-auth-contract.md`.

## Task 7 — PR, CI, and workflow receipts

- [ ] Commit implementation/docs/review/lesson with Korean Lore messages. Verify no root
  dirty files or unrelated worktree changes are staged.
- [ ] Create PR from `feat/issue-430-api-auth-contract` to
  `feat/issue-23-capacitor-foundation` only when branch, repository, base, and head are
  explicitly authorized by the stacked train context. Do not target `develop`.
- [ ] PR body includes Korean scope, #13/#430 links, stack/base/head SHAs, changed files,
  test matrix, 7-Tier table, DoD checklist, known #24/#27 boundary, and
  `Required checks: X/Y; N/A: N; Blocked: 0`.
- [ ] Push branch, poll exact-head CI until every required check is terminal, and reread
  PR body/metadata/checks live. No merge or auto-merge in this slice.
- [ ] Complete workflow checks in order: `check-result` for spec/plan/frontend/backend/
  E2E/review/diff, component evidence, lane-complete, completion-check, complete, and
  live-report-create. Use receipt checksum as `--expected-head`.

## Test and rollback matrix

| Contract | RED proof | GREEN proof | Rollback |
|---|---|---|---|
| origin normalization | focused endpoint tests fail | endpoint/unit/E2E pass | remove endpoint helper and restore `/api` client call |
| patient XSRF | interceptor tests fail | custom interceptor + browser header pass | unregister custom interceptor; built-in same-origin path remains |
| patient credentials | explicit-false/default tests fail | client tests and portal tests pass | restore caller-provided default, retain scope tests |
| backend CORS | properties/source tests fail | focused CORS + API module tests pass | set `enabled=false` to remove `/api/**` mapping while retaining the empty source required by Security |
| docs/contract | validator/audit fails on stale claims | docs validator, audit, diff-check pass | revert prose only; no runtime behavior change |

## Stop conditions

- Stop and record `BLOCKED` if native HTTPS origin cannot be supplied without adding an
  unauthorized bridge or if existing `SameSite=Strict` semantics require #24/#27 policy.
- Stop PR creation if any P0/P1 remains, required test cannot run without classifying an
  external infrastructure failure, or exact-head CI is not terminal.
- Keep PR open and merge-ready only. Full Epic #13 merge remains gated by one final fresh
  user approval after all child issues complete.
