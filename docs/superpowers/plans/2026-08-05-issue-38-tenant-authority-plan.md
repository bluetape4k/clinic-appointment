# Unified Tenant Path Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` or the repository's approved task-by-task execution lane. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every appointment commitment endpoint to `/api/{tenantCode}/...` and make the path tenant plus verified JWT membership the single HTTP tenant authority.

**Architecture:** Keep the existing `TenantContextFilter`, `TenantAuthorizationManager`, `ActorContextResolver`, clinic membership checks, and internal `tenantGroupId`/Exposed keys. Replace the `/api/v2` Gateway-selected exception with tenant-aware route matchers and pass the path tenant explicitly into commitment actor resolution. Do not add a tenant header or database/key migration.

**Tech Stack:** Kotlin 2.3, Spring Boot 4 MVC/Security, JJWT, Springdoc OpenAPI, JUnit 5, MockK/bluetape4k assertions, Exposed JDBC, coroutine `ThreadContextElement`.

---

## File map and ownership

| Responsibility | Files | Planned result |
|---|---|---|
| Canonical slug parsing | Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantCodeRules.kt`; modify `TenantPathResolver.kt`, `JwtTokenParser.kt` | One lower-case ASCII tenant-code rule shared by path and JWT parsing; `v1`/`v2` remain reserved roots. |
| Commitment controllers | Modify `AdminAppointmentV2Controller.kt`, `CustomerAppointmentV2Controller.kt`, `AppointmentCommitmentQueryController.kt`; rename controller classes only if compilation proves no external reference | Class mappings use `/api/{tenantCode}` and every actor resolution receives the path tenant. |
| Actor boundary | Modify `AppointmentCommitmentHttpSupport.kt`, `ActorContextResolverTest.kt`, controller unit tests | Multi-tenant JWTs select one path tenant; `singleOrNull()` is removed; clinic claims remain fail-closed. |
| Security boundary | Modify `SecurityConfig.kt`, `AppointmentCommitmentSecurityIntegrationTest.kt`, `ProfileReevaluationEndpointSecurityTest.kt` | Commitment-specific patient/admin/read rules are tenant-aware and precede generic tenant writes. |
| Stable error routing | Modify `AppointmentCommitmentApiException.kt`, `AppointmentCommitmentExceptionResolutionTest.kt` | Error registry recognizes only canonical tenant commitment routes. |
| HTTP/OpenAPI tests | Modify `AppointmentCommitmentOpenApiTest.kt`, `NotificationOpenApiTest.kt`, `AppointmentCommitmentFeatureOffIntegrationTest.kt`, `TenantPathResolverTest.kt` | New paths and no legacy `/api/v2` operations are asserted. |
| Context lifecycle | Modify `TenantContextTest.kt` and, if needed, `TenantContextFilterTest.kt` | Thread-local cleanup and coroutine propagation use current Kotlin test idioms. |
| Public/internal docs | Modify `docs/requirements/architecture.md`, `docs/api/visit-commitment.md`, `docs/runbooks/visit-commitment-operations.md`, `docs/runbooks/profile-reevaluation.md`, `docs/runbooks/profile-reevaluation.ko.md`, `appointment-api/README.md`, `appointment-api/README.ko.md` | Active docs and bilingual module README describe one tenant path contract. Historical specs/plans remain immutable unless a test or active link requires correction. |

## Traceability matrix

| Spec acceptance criterion | Plan tasks |
|---|---|
| All commitment routes use `/api/{tenantCode}/...` | Tasks 1, 2, 3, 4, 6 |
| Multi-tenant JWT selects an allowed path tenant | Tasks 1, 2, 3 |
| 401/403/404 mismatch matrix | Tasks 1, 3, 4 |
| Role and clinic membership are not weakened | Tasks 2, 3 |
| TenantContext cleanup/coroutine propagation | Task 5 |
| Active OpenAPI/docs contain no `/api/v2` | Tasks 4, 6 |
| Existing tests and module tests pass | Task 7 |
| No internal key/FK or Exposed boundary change | Tasks 2, 6, 7 |

## Task 0: Capture the clean baseline

**Files:** No product changes. Evidence only in the workflow receipt and plan notes.

- [ ] **Step 1: Verify branch and baseline worktree**

Run:

```bash
git status --short --branch
git rev-parse HEAD origin/develop
```

Expected: branch `issue-38-tenant-authority` is based on `origin/develop`; only the approved spec commit and temporary workflow evidence are present.

- [ ] **Step 2: Run the existing tenant/security unit baseline**

Run sequentially through the repository context-mode Gradle helper:

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantContextFilterTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.TenantAuthorizationManagerTest' \
  --no-build-cache
```

Expected: current baseline remains green (previous fresh run: 24 tests passed). If it fails, stop and diagnose the baseline before writing red tests.

## Task 1: Lock the canonical tenant-code and route failure contracts

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantCodeRules.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathResolver.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParser.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathResolverTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParserTest.kt`

- [ ] **Step 1: Write failing canonical-slug tests**

Add tests proving that `tenant-a` resolves and that `Tenant-A`, `tenant a`, an empty segment, `v1`, and `v2` do not resolve. Add JWT tests proving `allowedTenants = ["tenant-a"]` parses and `allowedTenants = ["Tenant-A"]` is rejected. Use bluetape4k assertions and descriptive backtick test names.

- [ ] **Step 2: Run only the new tests and verify RED**

Run:

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest' \
  --no-build-cache
```

Expected: the new uppercase/space JWT and resolver cases fail against the current mixed-case regex; no unrelated failure is accepted.

- [ ] **Step 3: Implement one shared rule**

Implement the following shape without normalization:

```kotlin
internal object TenantCodeRules {
    private val CANONICAL = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val RESERVED_ROOTS = setOf("v1", "v2")

    fun isCanonical(value: String): Boolean =
        CANONICAL.matches(value) && value !in RESERVED_ROOTS
}
```

`TenantPathResolver.resolve` returns only a canonical first `/api/` segment. `JwtTokenParser` uses the same rule for every `allowedTenants` entry. Invalid values are rejected; they are never lower-cased implicitly.

- [ ] **Step 4: Run the focused tests GREEN**

Run the Task 1 command again. Expected: all path and JWT tests pass, including existing valid lower-case tenants.

## Task 2: Make commitment actor resolution path-scoped

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentCommitmentHttpSupport.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentV2Controller.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/CustomerAppointmentV2Controller.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentCommitmentQueryController.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ActorContextResolverTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentV2Test.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentRequestV2Test.kt`

- [ ] **Step 1: Add the multi-tenant resolver regression test**

Extend the actor test fixture to use `allowedTenants = setOf("tenant-a", "tenant-b")`. Prove `resolve(authentication, "tenant-b", 7L, ...)` succeeds and that `tenant-c` fails. Keep the existing clinic mismatch failure. This test must fail before the helper change because `resolveAppointmentActor` currently calls `singleOrNull()`.

- [ ] **Step 2: Change the helper contract**

Change the internal extension to this explicit signature:

```kotlin
internal fun ActorContextResolver.resolveAppointmentActor(
    authentication: Authentication?,
    tenantCode: String,
    request: HttpServletRequest,
): ActorContext
```

Resolve the authenticated principal, keep the selected `clinicId` membership check, and call `resolve(authentication, tenantCode, clinicId, correlationId)`. Do not accept a tenant header, body field, or internal tenant ID.

- [ ] **Step 3: Change all commitment controller mappings and call sites**

Use these class mappings:

```kotlin
@RequestMapping("/api/{tenantCode}")
// Query controller:
@RequestMapping("/api/{tenantCode}/appointments")
```

Add `@PathVariable tenantCode: String` to every commitment handler and pass it to `resolveAppointmentActor`. Keep resource suffixes unchanged so the new paths match the approved spec table. If class renaming is performed, rename `AdminAppointmentV2Controller`/`CustomerAppointmentV2Controller` and their direct unit-test references together; do not keep duplicate beans or legacy route aliases.

- [ ] **Step 4: Run controller/unit tests GREEN**

Run:

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.ActorContextResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.AdminAppointmentV2Test' \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.AppointmentRequestV2Test' \
  --no-build-cache
```

Expected: multi-tenant path selection passes, direct controller calls compile with the new path argument, and no service behavior changes.

## Task 3: Replace v2 Security matchers with tenant-aware commitment rules

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentSecurityIntegrationTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ProfileReevaluationEndpointSecurityTest.kt`

- [ ] **Step 1: Add failing authorization matrix cases**

Update the integration test paths to `/api/tenant-default/...` and add cases for:

```text
POST /api/tenant-default/appointment-requests: PATIENT + allowed tenant -> route authorization passes
POST /api/tenant-default/appointment-requests: ADMIN/SYSTEM -> 403
POST /api/tenant-other/appointment-requests: PATIENT allowed only tenant-default -> 403
POST /api/tenant-default/admin/appointments: PATIENT/SYSTEM -> 403
GET /api/tenant-default/appointments/7/commitment: ADMIN or PATIENT -> route authorization passes
GET /api/tenant-other/appointments/7/commitment: tenant mismatch -> 403
GET /api/missing/appointments/7/commitment: authenticated unknown tenant -> 404
```

Use the existing singleton infrastructure and `TestJwtProvider`; do not add `@Testcontainers` or a raw container.

- [ ] **Step 2: Run the matrix and verify RED**

Run only `AppointmentCommitmentSecurityIntegrationTest`. Expected: new tenant paths currently return 404 or fall through to generic role rules, proving the matcher/controller boundary is not yet migrated.

- [ ] **Step 3: Implement explicit matchers before generic tenant writes**

Remove every `/api/v2/**` matcher and add these rules before the broad `GET/POST/PATCH/DELETE /api/{tenantCode}/**` rules:

```kotlin
.requestMatchers(HttpMethod.POST, "/api/{tenantCode}/appointment-requests")
    .access(patientTenantAccess(tenantAuthorizationManager))
.requestMatchers(HttpMethod.POST,
    "/api/{tenantCode}/appointments/*/proposals/*/accept",
    "/api/{tenantCode}/appointments/*/proposals/*/decline",
).access(patientTenantAccess(tenantAuthorizationManager))
.requestMatchers(HttpMethod.GET, "/api/{tenantCode}/appointments/*/commitment")
    .access(commitmentReadTenantAccess(tenantAuthorizationManager))
.requestMatchers(HttpMethod.POST,
    "/api/{tenantCode}/appointments/*/approve",
    "/api/{tenantCode}/appointments/*/confirm",
    "/api/{tenantCode}/appointments/*/proposals/*/expire",
    "/api/{tenantCode}/appointments/*/cancel",
    "/api/{tenantCode}/appointments/*/change-proposals",
).access(commitmentAdminTenantAccess(tenantAuthorizationManager))
```

`patientTenantAccess` is `hasRole(PATIENT)` plus the tenant manager; `commitmentReadTenantAccess` is `hasAnyRole(ADMIN, PATIENT)` plus the manager; `commitmentAdminTenantAccess` is `hasRole(ADMIN)` plus the manager. Direct creation remains covered by the existing tenant-aware `/api/{tenantCode}/admin/**` rule. The specific admin rules are required so generic STAFF write access cannot reach commitment mutations.

- [ ] **Step 4: Run the integration matrix GREEN**

Run the Task 3 test command sequentially. Expected: status/error envelope matches the matrix and generic tenant routes retain their prior behavior.

## Task 4: Update stable commitment error routing and OpenAPI tests

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentCommitmentApiException.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentCommitmentExceptionResolutionTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentOpenApiTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/NotificationOpenApiTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentFeatureOffIntegrationTest.kt`

- [ ] **Step 1: Replace all v2 path fixtures with canonical tenant paths**

Use `tenant-default` in concrete MVC paths and `{tenantCode}` in OpenAPI paths. Update the local failing controller mapping in `AppointmentCommitmentExceptionResolutionTest` to `/api/{tenantCode}/appointments/{id}/commitment` and assert that `/api/v2/other` is not recognized.

- [ ] **Step 2: Implement canonical path classifiers**

Use one bounded lower-case tenant segment in the item/proposal regexes and exact checks for `/api/{tenantCode}/appointment-requests` and `/api/{tenantCode}/admin/appointments`. Do not classify all `/api/{tenantCode}/**` paths as commitment errors; unrelated APIs must retain their own error registry.

- [ ] **Step 3: Prove OpenAPI and feature-off behavior**

The enabled OpenAPI test must assert every new path, required headers, success/error response set, and absence of `/api/v2`. The disabled test must call `/api/tenant-default/appointments/7/commitment`, expect 404, and assert that new commitment paths are absent from OpenAPI.

- [ ] **Step 4: Run the focused HTTP/document tests GREEN**

Run the four test classes in one sequential Gradle invocation with `--no-build-cache`. Expected: stable commitment error envelopes and OpenAPI paths use only the unified tenant contract.

## Task 5: Complete tenant-context and coroutine lifecycle proof

**Files:**
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathResolverTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilterTest.kt` only if a new path cleanup case is needed
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextTest.kt`

- [ ] **Step 1: Replace the old version-root test with legacy rejection coverage**

Keep `/api/v2/...` as a reserved legacy path that resolves to no tenant, add lower-case path success, and add upper-case/space rejection. The test must prove no implicit normalization occurs.

- [ ] **Step 2: Use coroutine-test for propagation and cleanup**

Convert the context propagation test to `runTest` and use `withContext(Dispatchers.Default)` for a real dispatcher hop that proves `TenantContextElement.updateThreadContext`/`restoreThreadContext`. Assert that `TenantContext.current()` is null after the outer context exits and after a child coroutine completes. Do not use `GlobalScope`, manual continuations, or swallowed cancellation.

- [ ] **Step 3: Run tenant lifecycle tests**

Run `TenantPathResolverTest`, `TenantContextFilterTest`, and `TenantContextTest` with `--no-build-cache`. Expected: cleanup, nested restoration, and dispatcher propagation all pass.

## Task 6: Update active documentation and bilingual module README

**Files:**
- Modify: `docs/requirements/architecture.md`
- Modify: `docs/api/visit-commitment.md`
- Modify: `docs/runbooks/visit-commitment-operations.md`
- Modify: `docs/runbooks/profile-reevaluation.md`
- Modify: `docs/runbooks/profile-reevaluation.ko.md`
- Modify: `appointment-api/README.md`
- Modify: `appointment-api/README.ko.md`

- [ ] **Step 1: Replace the ADR-14 exception**

Change the authority table so every HTTP appointment route is path-selected. State that JWT `allowedTenants` is a membership proof, headers are not authority, `/api/v2` is not a supported version root, and internal keys remain server-side. Preserve the explicit background/coroutine scope rule.

- [ ] **Step 2: Update API/runbook examples**

Replace active visit-commitment and cancellation examples with `/api/{tenantCode}` paths and show `allowedTenants` containing the path slug. Rephrase profile reevaluation’s “not `/api/v2/**`” warning as “not exposed under tenant appointment routes”; keep actuator’s actual `/actuator/profileReevaluation` path.

- [ ] **Step 3: Keep English/Korean README parity**

Update both module README class tables and authentication sections. The English README remains English and the Korean README remains Korean; only identifiers, URLs, commands, and exact error text stay unchanged.

- [ ] **Step 4: Validate active docs**

Run:

```bash
rg -n 'api/v2' docs/api docs/runbooks docs/requirements/architecture.md appointment-api/README.md appointment-api/README.ko.md
git diff --check
```

Expected: no active API/runbook/README reference remains. Historical design/plan records may retain prior paths and must be explicitly excluded from the active-doc search.

## Task 7: Rename internal V2 controller symbols only after behavior is green

**Files:**
- Rename: `AdminAppointmentV2Controller.kt` → `AdminAppointmentController.kt`
- Rename: `CustomerAppointmentV2Controller.kt` → `CustomerAppointmentController.kt`
- Modify corresponding controller unit tests and `appointment-api/README.md`, `README.ko.md`

- [ ] **Step 1: Confirm no external symbol dependency**

Run:

```bash
rg -n 'AdminAppointmentV2Controller|CustomerAppointmentV2Controller' appointment-api --glob '!build/**'
```

Expected: only the two production files, their direct unit tests, and README class tables are listed.

- [ ] **Step 2: Rename and update symbols without changing mappings**

Use `git mv`, update class/test references, and retain the same constructor dependencies. Do not add an alias class or a second Spring bean.

- [ ] **Step 3: Compile the affected test sources**

Run the controller unit tests from Task 2. Expected: no behavior or route regression is introduced by symbol cleanup.

## Task 8: Proportional validation and final diff convergence

**Files:** All changed files from Tasks 1–7; no new scope without spec update.

- [ ] **Step 1: Run the smallest affected tests again**

Run the complete focused matrix:

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantContextFilterTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantContextTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.ActorContextResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.AppointmentCommitmentSecurityIntegrationTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.AppointmentCommitmentOpenApiTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.AppointmentCommitmentFeatureOffIntegrationTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentExceptionResolutionTest' \
  --no-build-cache
```

Expected: all selected tests pass; container-backed integration work remains sequential.

- [ ] **Step 2: Run the full affected module test**

Run:

```bash
./gradlew :appointment-api:test --no-build-cache
```

Expected: `appointment-api` test task passes. Record the actual test count and any pre-existing deprecation warnings separately from failures.

- [ ] **Step 3: Run static and scope checks**

Run:

```bash
git diff --check
rg -n 'api/v2|singleOrNull\(\)' appointment-api/src/main appointment-api/src/test docs/api docs/runbooks docs/requirements/architecture.md appointment-api/README.md appointment-api/README.ko.md
git status --short
```

Expected: no production route, active doc, or commitment actor resolver retains the removed v2 authority; historical specs/plans are the only intentionally retained references. No unrelated file is changed.

- [ ] **Step 4: Run Kotlin final checklist and performance/stability scan**

Inspect the final diff for new `!!`, blocking calls on coroutine dispatchers, swallowed cancellation, Exposed transaction drift, lifecycle cleanup, matcher ordering, and route-level authorization cache behavior. Record concrete N/A for performance benchmarks because this change does not alter DB indexes or query plans; run the HTTP/security integration matrix as the relevant hot-path proof.

## Task 9: Review, lesson, and delivery handoff

**Files:**
- Create: `docs/reviews/2026-08-05-issue-38-tenant-authority-review.ko.md`
- Create: `docs/lessons/2026-08-05-issue-38-tenant-authority.md`
- Modify: PR body only after branch validation

- [ ] **Step 1: Run six-perspective final review**

Review security, stability, performance, API contract, Kotlin quality, and documentation against the exact diff. P0/P1 must be zero. Record route inventory, 401/403/404 matrix, test commands/results, and residual risks in the Korean review artifact.

- [ ] **Step 2: Capture the lesson before PR creation**

Record the durable decision that a client-supplied header is never tenant authority and that one explicit path selector is preferable to a hidden Gateway mode. Include the exact tests that prove multi-tenant selection and legacy `/api/v2` rejection.

- [ ] **Step 3: Commit implementation, review, and lesson with Lore trailers**

Use separate focused commits when the diff is large enough to review independently. Every commit must contain `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, and `Not-tested` trailers.

- [ ] **Step 4: Publish and verify the English issue-linked PR**

Push `issue-38-tenant-authority`, create the PR against `develop`, link `Closes #38`, preserve issue assignee/label parity, and make `## DoD Status` the final PR section. Verify the live body and exact remote head before waiting for CI.

## Risk prediction and rerun points

| Risk | Signal | Mitigation | Rerun point / rollback |
|---|---|---|---|
| Security matcher order lets STAFF reach admin commitment mutation | Integration test returns a domain response instead of 403 for STAFF | Put explicit commitment admin rules before generic tenant writes; test role matrix | Rerun Task 3; revert matcher-only commit if unresolved |
| Multi-tenant JWT remains rejected by hidden `singleOrNull()` | Allowed tenant set size 2 fails on a selected path | Pass path tenant through helper and recheck membership in `ActorContextResolver` | Rerun Task 2; revert helper/controller commit |
| Unknown/invalid tenant leaks data or wrong error envelope | Unknown path returns 200, or 403/404 mismatch appears | Shared canonical rule, filter DB lookup, path-specific error registry tests | Rerun Tasks 1 and 4; revert route commit |
| Existing API route collides with commitment route | Spring mapping ambiguity or OpenAPI duplicate path | Keep commitment suffixes under existing appointment base; run context/OpenAPI tests | Stop before merge; split route mapping correction into a follow-up |
| TenantContext leaks across servlet/coroutine boundaries | Context remains after filter/test or appears in another dispatcher | `try/finally`/ThreadContextElement tests with coroutine-test | Rerun Task 5; no merge until cleanup is proven |
| Documentation/client examples drift from code | Active `rg api/v2` hit or bilingual README mismatch | Update active docs in the same branch and run scoped search | Rerun Task 6; hold PR until parity is restored |
| Broad route change causes unexpected HTTP behavior or latency | Full module failures or integration timeout | No DB/query/key change; sequential integration test and diff scan | Revert entire PR; no compatibility alias is added implicitly |

## Self-review result

- Every spec acceptance criterion maps to at least one task in the traceability matrix.
- No task depends on an undefined symbol; the new helper signature and matcher helpers are shown explicitly.
- RED/GREEN commands are present before each behavior change, with expected outcomes.
- Historical documents are intentionally excluded from active API contract updates to avoid rewriting decision history.
- No dependency, migration, key/FK, or production database query change is planned.
