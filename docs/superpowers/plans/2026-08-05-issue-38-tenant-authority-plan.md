# Unified Tenant Path Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` or the repository's approved task-by-task execution lane. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every appointment commitment endpoint to `/api/{tenantCode}/...` and make the path tenant plus verified JWT membership the single HTTP tenant authority.

**Architecture:** Keep the existing `TenantContextFilter`, `TenantAuthorizationManager`, `ActorContextResolver`, clinic membership checks, and internal `tenantGroupId`/Exposed keys. Replace the `/api/v2` Gateway-selected exception with tenant-aware route matchers and pass the path tenant explicitly into commitment actor resolution. Do not add a tenant header or database/key migration.

**Tech Stack:** Kotlin 2.3, Spring Boot 4 MVC/Security, JJWT, Springdoc OpenAPI, JUnit 5, MockK/bluetape4k assertions, Exposed JDBC, coroutine `ThreadContextElement`.

---

## File map and ownership

| Responsibility | Files | Planned result |
|---|---|---|
| Canonical slug and pre-auth guard | Create `TenantCodeRules.kt` and `TenantPathValidationFilter.kt`; modify `TenantPathResolver.kt`, `JwtTokenParser.kt`, `SecurityConfig.kt` | One lower-case ASCII tenant-code rule is shared by path, JWT, matcher, actor, and error classification; malformed/`v1`/`v2` roots fail closed before JWT parsing. |
| Tenant filter lifecycle | Modify `TenantContextFilter.kt`, `TenantContextFilterTest.kt`, `TenantPathValidationFilterTest.kt` | Active-tenant lookup failures have a privacy-safe stable error, stale ThreadLocal state is cleared at request boundaries, and valid requests restore context after success/error/async dispatch. |
| Commitment controllers | Modify `AdminAppointmentV2Controller.kt`, `CustomerAppointmentV2Controller.kt`, `AppointmentCommitmentQueryController.kt`; rename controller classes only if compilation proves no external reference | Class mappings use `/api/{tenantCode}` and every actor resolution receives the path tenant. |
| Actor and service scope boundary | Modify `AppointmentCommitmentHttpSupport.kt`, `ActorContext.kt`, `ActorContextResolver.kt`, `TenantAuthorizationManager.kt`, `AppointmentCommitmentAccessResolver.kt`, `ActorContextResolverTest.kt`, `AppointmentCommitmentAccessResolverTest.kt`, controller unit tests | Multi-tenant JWTs select one canonical path tenant in `ActorContext.selectedTenantCode`; all downstream scope/consent lookups use it; tenant-authority `singleOrNull()` is removed; clinic claims remain fail-closed. |
| Security boundary | Modify `SecurityConfig.kt`, `AppointmentCommitmentSecurityIntegrationTest.kt`, `ProfileReevaluationEndpointSecurityTest.kt` | Commitment-specific patient/admin/read rules are tenant-aware and precede generic tenant writes; all ten routes have role, tenant, invalid-token, and active/inactive-tenant coverage. |
| Stable error routing | Modify `AppointmentCommitmentApiException.kt`, `AppointmentCommitmentExceptionResolutionTest.kt` | Error registry recognizes only canonical tenant commitment routes and shares reserved-root rejection; scope-hidden resources retain the documented 403 privacy contract. |
| HTTP/OpenAPI tests | Modify `AppointmentCommitmentOpenApiTest.kt`, `NotificationOpenApiTest.kt`, `AppointmentCommitmentFeatureOffIntegrationTest.kt`, `TenantPathResolverTest.kt` | Exact ten path/method operations, required headers, error codes, no legacy alias, and enabled/disabled route behavior are asserted. |
| Context lifecycle and suspend boundaries | Modify `TenantContextTest.kt`, `TenantContextFilterTest.kt` and focused context tests; modify a suspend controller only if an ambient tenant read is found | Thread-local cleanup, nested restoration, servlet async/error handling, and coroutine `TenantContextElement` behavior are proven without adding unnecessary ambient-context propagation. Existing suspend controllers keep their explicit tenant scope contract. |
| Public/internal docs | Modify `docs/requirements/architecture.md`, `docs/api/visit-commitment.md`, `docs/runbooks/visit-commitment-operations.md`, `docs/runbooks/profile-reevaluation.md`, `docs/runbooks/profile-reevaluation.ko.md`, `appointment-api/README.md`, `appointment-api/README.ko.md` | Active docs and bilingual module README describe one tenant path contract. This approved spec revision is recorded and requires renewed plan/spec approval; unrelated historical specs/plans remain immutable. |

## Traceability matrix

| Spec acceptance criterion | Plan tasks |
|---|---|
| All commitment routes use `/api/{tenantCode}/...` | Tasks 1, 2, 3, 4, 6 |
| Multi-tenant JWT selects an allowed path tenant | Tasks 1, 2, 3 |
| 401/403/404 mismatch matrix | Tasks 1, 3, 4 |
| Role and clinic membership are not weakened | Tasks 2, 3 |
| TenantContext cleanup/coroutine propagation | Task 5 |
| Active OpenAPI/docs contain no `/api/v2` | Tasks 4, 6 |
| Existing tests and module tests pass | Tasks 8, 9 |
| No internal key/FK or Exposed boundary change | Tasks 2, 6, 8 |
| Malformed paths fail before authentication and filter envelopes are stable | Tasks 1, 3, 4, 5 |
| Selected tenant reaches service scope and spoofed headers/body never override it | Tasks 2, 3, 4 |
| Active/historical docs, bilingual README, review/lesson, PR/CI evidence are complete | Tasks 6, 9 |

## Task 0: Capture the clean baseline

**Files:** No product changes. Evidence only in the workflow receipt and plan notes.

- [ ] **Step 1: Verify branch and baseline worktree**

Run:

```bash
git status --short --branch
git rev-parse HEAD origin/develop
```

Expected: branch `issue-38-tenant-authority` is based on `origin/develop`; the revised spec, plan, and plan-review artifact commits plus ignored temporary workflow evidence are present, pending renewed approval before implementation.

- [ ] **Step 2: Run the existing tenant/security unit baseline**

Run sequentially through the repository context-mode Gradle helper:

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantContextFilterTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.TenantAuthorizationManagerTest' \
  --no-parallel --no-build-cache --rerun-tasks
```

Expected: current baseline remains green (previous fresh run: 24 tests passed). If it fails, stop and diagnose the baseline before writing red tests.

## Task 1: Lock the canonical tenant-code and route failure contracts

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantCodeRules.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathValidationFilter.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathResolver.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParser.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathResolverTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathValidationFilterTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParserTest.kt`

- [ ] **Step 1: Write failing canonical-slug and pre-auth tests**

Add tests proving that `tenant-a` resolves and that `Tenant-A`, `tenant a`, an empty segment, `v1`, and `v2` do not resolve. The canonical rule must match Flyway V20: lower-case ASCII alphanumeric segments separated by single hyphens; dots, underscores, leading/trailing hyphens, and repeated hyphens are invalid, and the slug is at most 64 characters. Add JWT tests proving `allowedTenants = ["tenant-a"]` parses, `allowedTenants = ["Tenant-A"]` is rejected, and duplicate/maximum-size claims remain bounded and materialize as immutable sets. Add a filter test proving malformed, encoded-ambiguous, and reserved roots are rejected before the JWT parser is invoked with a privacy-safe 404 envelope. Exercise both raw `requestURI` and `servletPath`/path-info representations for `%2f`, `%2e`, `%5c`, semicolon path parameters, and double-encoded separators; reject any representation whose decoded path is not a single canonical servlet path before Spring Security matching. The parser-not-invoked assertion must run through a real `MockMvc`/security-chain fixture with a spy parser or equivalent filter-chain probe, not only a standalone filter call.

- [ ] **Step 2: Run only the new tests and verify RED**

Run:

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathValidationFilterTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest' \
  --no-build-cache --rerun-tasks
```

Expected: the new uppercase/space JWT, resolver, and pre-auth filter cases fail against the current mixed-case/reserved-root behavior; no unrelated failure is accepted.

- [ ] **Step 3: Implement one shared rule**

Implement the following shape without normalization:

```kotlin
internal object TenantCodeRules {
    private val CANONICAL = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val RESERVED_ROOTS = setOf("v1", "v2")

    fun isCanonical(value: String): Boolean =
        value.length <= 64 && CANONICAL.matches(value) && value !in RESERVED_ROOTS
}
```

`TenantPathResolver.resolve` returns only a canonical first `/api/` segment. `JwtTokenParser` uses the same rule for every `allowedTenants` entry. Invalid values are rejected; they are never lower-cased implicitly. `TenantPathValidationFilter` runs after correlation setup but before `JwtAuthenticationFilter`; configure this explicitly as `addFilterAfter(tenantPathValidationFilter, correlationIdFilter)` followed by `addFilterAfter(jwtAuthenticationFilter, tenantPathValidationFilter)` (or the exact equivalent), never as two filters both relative to correlation. It rejects malformed, encoded-ambiguous, and `v1`/`v2` roots with `RESOURCE_NOT_FOUND` without invoking JWT parsing. A syntactically canonical but unknown tenant continues to JWT authentication and is resolved as authenticated `RESOURCE_NOT_FOUND` by `TenantContextFilter`.

Register the new filter as a Spring bean with a disabled servlet `FilterRegistrationBean` (the same `enabled=false` convention used for correlation/JWT/tenant filters) so it runs only once in the `SecurityFilterChain`. Assert the effective order as correlation → tenant-path validation → JWT → tenant-context in the security configuration test. The pre-auth filter must not query the tenant database or inspect JWT claims.

- [ ] **Step 4: Run the focused tests GREEN**

Run the Task 1 command again. Expected: all path and JWT tests pass, including existing valid lower-case tenants.

## Task 2: Make commitment actor resolution path-scoped

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentCommitmentHttpSupport.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/ActorContext.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/ActorContextResolver.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/TenantAuthorizationManager.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentCommitmentAccessResolver.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentV2Controller.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/CustomerAppointmentV2Controller.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentCommitmentQueryController.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ActorContextResolverTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentCommitmentAccessResolverTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentV2Test.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentRequestV2Test.kt`

- [ ] **Step 1: Add the multi-tenant resolver regression test**

Extend the actor test fixture to use `allowedTenants = setOf("tenant-a", "tenant-b")`. Prove `resolve(authentication, "tenant-b", 7L, ...)` succeeds with `selectedTenantCode == "tenant-b"` and that `tenant-c`, malformed codes, and reserved roots fail. Add a service-scope regression in `AppointmentCommitmentAccessResolverTest` with active tenant A/B and the same multi-tenant actor, proving path B reaches tenant B rows and tenant A data cannot be addressed through B. Keep the existing clinic mismatch failure. These tests must fail before the selected-tenant and access-resolver changes because the current helper and service use tenant `singleOrNull()`.

Add `selectedTenantCode: String? = null` with a default to `ActorContext` so existing policy/background fixtures and all `ActorContext(...)` construction sites remain source-compatible. Inventory those sites with `rg -n 'ActorContext\(' appointment-api/src/main appointment-api/src/test`; only the commitment access boundary may require the field and it must fail closed when the field is null. Do not retrofit unrelated policy actors or silently infer a selected tenant from the full grant set.

- [ ] **Step 2: Change the helper contract**

Change the internal extension to this explicit signature:

```kotlin
internal fun ActorContextResolver.resolveAppointmentActor(
    authentication: Authentication?,
    tenantCode: String,
    request: HttpServletRequest,
): ActorContext
```

Resolve the authenticated principal, require `TenantCodeRules.isCanonical(tenantCode)`, keep the selected `clinicId` membership check, and call `resolve(authentication, tenantCode, clinicId, correlationId)`. The resolver stores the canonical path value in `ActorContext.selectedTenantCode` while retaining the full immutable grant set for audit. `AppointmentCommitmentAccessResolver.resolveScope` must use and revalidate `selectedTenantCode` and active tenant-group lookup; it must not infer authority from `allowedTenantCodes.singleOrNull()`. Do not accept a tenant header, body field, or internal tenant ID. Unknown body fields remain a 400 contract; known consent `evidenceAuthority` must match the selected tenant namespace or return the documented 403.

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
  --tests 'io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentAccessResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.AdminAppointmentV2Test' \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.AppointmentRequestV2Test' \
  --no-parallel --no-build-cache --rerun-tasks
```

Expected: multi-tenant path selection reaches the service's selected tenant scope, direct controller calls compile with the new path argument, cross-tenant/clinic data remains inaccessible, and no state-machine/key behavior changes.

## Task 3: Replace v2 Security matchers with tenant-aware commitment rules

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilter.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentSecurityIntegrationTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ProfileReevaluationEndpointSecurityTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/TenantAuthorizationManagerTest.kt`

- [ ] **Step 1: Add failing authorization matrix cases**

Seed active `tenant-default` and `tenant-other`, an inactive tenant, and an authenticated missing tenant through the existing singleton integration fixture. For each allowed route, stub the exact application-service method with a deterministic success response and verify its invocation with the selected tenant/clinic scope; use a response header/body marker only as a secondary assertion. This makes a 2xx/4xx produced by an un-stubbed downstream call unable to masquerade as matcher success. Update the integration test paths to `/api/tenant-default/...` and cover the complete ten-route inventory:

```text
POST /api/tenant-default/appointment-requests: PATIENT + allowed tenant -> post-auth marker; ADMIN/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/admin/appointments: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/approve: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/confirm: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/proposals/{proposalId}/expire: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/cancel: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/change-proposals: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/proposals/{proposalId}/accept: PATIENT -> post-auth marker; ADMIN/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/proposals/{proposalId}/decline: PATIENT -> post-auth marker; ADMIN/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
GET /api/tenant-default/appointments/7/commitment: ADMIN or PATIENT -> post-auth marker; STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
Any route with a JWT allowed only for tenant-default under `/api/tenant-other/...`: 403 `FORBIDDEN` from the tenant filter; unknown or inactive authenticated tenant: 404 `RESOURCE_NOT_FOUND`; missing, invalid, or expired JWT: 401 `UNAUTHORIZED`.
Malformed/uppercase/space/encoded-ambiguous/v1/v2 roots: pre-auth 404 `RESOURCE_NOT_FOUND` and JWT parser is not invoked.
The same multi-tenant JWT with `allowedTenants={tenant-default,tenant-other}` must select the path tenant and reach the matching tenant group; an appointment in another clinic remains 403 `SCOPE_FORBIDDEN` without revealing data.
Conflicting `X-Tenant-Code`, `X-Clinic-Id`, or `tenantGroupId` headers/body fields never override the path. Unknown fields are 400; known consent namespace conflicts are 403.
```

Use the existing singleton infrastructure and `TestJwtProvider`; create/cleanup tenant rows with `SchemaUtils.createMissingTablesAndColumns` and `Table.deleteAll()` under the repository's `API_INTEGRATION_RESOURCE`/explicit database resource lock. Do not add `@Testcontainers` or a raw container.

The fixture must deterministically seed active A/B tenants, an inactive tenant, and a missing-code case instead of relying on the default `tenant-default` seed. Save and restore `TransactionManager.defaultDatabase` around any Exposed `Database.connect` setup, delete rows in dependency-safe order inside `transaction {}`, and restore the original default even when setup or teardown fails. This prevents parallel integration classes from changing the process-global Exposed database.

- [ ] **Step 2: Run the matrix and verify RED**

Run only `AppointmentCommitmentSecurityIntegrationTest` and `TenantAuthorizationManagerTest`. Expected: new tenant paths currently return 404 or fall through to generic role rules, proving the matcher/controller boundary is not yet migrated; malformed matcher variables must fail closed. Add manager cases where a raw `context.variables["tenantCode"]` is uppercase, contains spaces, is `v1`/`v2`, or is encoded/ambiguous; all must be denied without trusting the matcher variable.

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

`patientTenantAccess` is `hasRole(PATIENT)` plus the tenant manager; `commitmentReadTenantAccess` is `hasAnyRole(ADMIN, PATIENT)` plus the manager; `commitmentAdminTenantAccess` is `hasRole(ADMIN)` plus the manager. Direct creation remains covered by the existing tenant-aware `/api/{tenantCode}/admin/**` rule. The specific admin rules are required so generic STAFF write access cannot reach commitment mutations. `TenantAuthorizationManager` must canonicalize and reserved-root-check the matcher variable before membership, even when Spring supplies `context.variables["tenantCode"]`; it may not trust raw matcher input.

The tenant path validation filter is registered after correlation and before JWT authentication, and its servlet registration is disabled so it cannot run outside or twice around the security chain. The tenant context filter remains after JWT authentication and is likewise chain-only. Tenant lookup/membership failures deliberately use the existing foundation envelope (`RESOURCE_NOT_FOUND`/`FORBIDDEN`); endpoint role/clinic failures use the commitment envelope (`SCOPE_FORBIDDEN`). This distinction is part of the public matrix and is asserted by filter-level tests.

- [ ] **Step 4: Run the integration matrix GREEN**

Run the Task 3 test command as separate `--no-parallel --rerun-tasks` invocations for the integration fixture and unit matcher tests; keep the explicit serialization/resource lock even though the current test resource disables global parallel execution because Exposed's default database is process-global. Expected: all ten route rules select exactly once, status/error envelope matches the matrix, post-auth markers prove matcher success, and generic tenant routes retain their prior behavior.

## Task 4: Update stable commitment error routing and OpenAPI tests

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentCommitmentApiException.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentCommitmentExceptionResolutionTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentOpenApiTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/NotificationOpenApiTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentFeatureOffIntegrationTest.kt`

- [ ] **Step 1: Replace all v2 path fixtures with canonical tenant paths**

Use `tenant-default` in concrete MVC paths and `{tenantCode}` in OpenAPI paths. Update the local failing controller mapping in `AppointmentCommitmentExceptionResolutionTest` to `/api/{tenantCode}/appointments/{id}/commitment` and assert that `/api/v2/other` and every other reserved-root commitment shape are not recognized by the classifier. Preserve explicit negative tests for the profile actuator's legacy `/api/v2/profileReevaluation` guard.

- [ ] **Step 2: Implement canonical path classifiers**

Use the shared `TenantCodeRules.isCanonical` (including the database-compatible lower-case alnum/hyphen rule and reserved `v1`/`v2` roots) in the item/proposal regexes and exact checks for `/api/{tenantCode}/appointment-requests` and `/api/{tenantCode}/admin/appointments`. Do not classify all `/api/{tenantCode}/**` paths as commitment errors; unrelated APIs must retain their own error registry. Preserve the current fail-closed scope contract: tenant/clinic mismatch and a scoped commitment/proposal that is absent both return 403 `SCOPE_FORBIDDEN`; 404 is reserved for malformed/reserved paths and unknown or inactive tenant groups at the filter boundary. Do not add a separate existence query merely to distinguish authorized absence in this issue. Align `AppointmentCommitmentAccessResolver`, controller OpenAPI, and tests with this privacy contract.

- [ ] **Step 3: Prove OpenAPI and feature-off behavior**

The enabled OpenAPI test must assert the exact ten path/method operations, unique operation IDs, required headers, success/error response set, no duplicate/shadow operation, a required `tenantCode` path parameter with the canonical lower-case alnum/hyphen pattern and reserved-root description, and absence of `/api/v2`. The disabled test must seed an active tenant, call both `/api/tenant-default/appointments/7/commitment` and every legacy `/api/v2` commitment shape, expect 404 for all, and assert that new commitment paths are absent from OpenAPI. The query operation documents 403 `SCOPE_FORBIDDEN` for fail-closed scope absence rather than promising a data-existence-sensitive 404.

- [ ] **Step 4: Run the focused HTTP/document tests GREEN**

Run the focused HTTP/document tests in separate `--no-parallel` invocations with `--no-build-cache --rerun-tasks`. Expected: stable commitment/foundation error envelopes, exact route inventory, and OpenAPI paths use only the unified tenant contract.

## Task 5: Complete tenant-context and coroutine lifecycle proof

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilter.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathResolverTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathValidationFilterTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilterTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextTest.kt`
- Modify: focused suspend-controller/context tests and `junit-platform.properties` resource-lock usage only when required by the existing integration contract

- [ ] **Step 1: Replace the old version-root test with legacy rejection coverage**

Keep `/api/v2/...` as a reserved legacy path that resolves to no tenant, add lower-case path success, and add upper-case/space/encoded-ambiguous rejection. The pre-auth filter test must prove the JWT parser is not invoked for malformed/reserved roots. Add table-driven negative requests for all ten old commitment route shapes and keep the separate profile-reevaluation 404 guard.

- [ ] **Step 2: Use coroutine-test for propagation and cleanup**

Convert the context propagation test to `runTest` and use `withContext(Dispatchers.Default)` for a real dispatcher hop that proves `TenantContextElement.updateThreadContext`/`restoreThreadContext`. Add nested A→B→A restoration, throwing-block restoration, parallel child cleanup, and cancellation assertions. `TenantContextFilter` must clear stale context at request entry/finally, restore no prior request state on tenantless/error paths, and catch tenant-group lookup failures into the existing privacy-safe `PlanFoundationError.INTERNAL_ERROR` (HTTP 500) for general requests or `SchedulingPolicyErrorCode.POLICY_INTERNAL_ERROR` for policy requests; neither outage path may be mapped to 404/403. Log only a correlation ID and sanitized tenant code, never token or identifiers, and add a response/error-log assertion. Register `TenantPathValidationFilter` after correlation and before JWT; register `TenantContextFilter` after JWT, with both servlet registrations disabled. Existing `AppointmentController` and `NotificationOperationsController` already pass explicit tenant scope and must remain unchanged unless implementation inspection finds a real ambient read; do not introduce `withContext` propagation solely for this issue. Explicit `ActorContext.selectedTenantCode` remains the commitment authority. Do not use `GlobalScope`, manual continuations, or swallowed cancellation.

- [ ] **Step 3: Run tenant lifecycle tests**

Run `TenantPathResolverTest`, `TenantPathValidationFilterTest`, `TenantContextFilterTest`, and `TenantContextTest` in separate `--no-parallel --no-build-cache --rerun-tasks` invocations. Expected: pre-auth rejection, active/inactive/unknown tenant envelopes, DB-failure cleanup, stale-thread isolation, nested restoration, coroutine element dispatcher propagation, and servlet async/error cleanup all pass. Do not claim production ambient propagation: current suspend controllers pass explicit `TenantClinicScope`; the test proves only the reusable element and filter lifecycle unless implementation inspection finds a real ambient consumer. The filter fixture must use `SchemaUtils.createMissingTablesAndColumns` and `Table.deleteAll()` inside `transaction {}` and an explicit shared database/resource lock; it must save and restore the original `TransactionManager.defaultDatabase` in `try/finally` so it cannot leak a process-global H2 connection.

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

Change the authority table so every HTTP appointment route is path-selected. State that JWT `allowedTenants` is a membership proof, headers are not authority, `/api/v2` is not a supported version root, and internal keys remain server-side. Preserve the explicit background/coroutine scope rule. Document the canonical database-compatible slug pattern, filter versus endpoint error codes, and the fail-closed 403 scope contract (404 is limited to malformed/reserved paths and unknown/inactive tenant groups; no existence-sensitive aggregate distinction is promised).

- [ ] **Step 2: Update API/runbook examples**

Replace active visit-commitment and cancellation examples with `/api/{tenantCode}` paths and show `allowedTenants` containing the path slug. Rephrase profile reevaluation’s “not `/api/v2/**`” warning as “not exposed under tenant appointment routes”; keep actuator’s actual `/actuator/profileReevaluation` path. Add a rollout checklist: keep commitment `api-enabled=false` or drain old pods, deploy controller/security/filter/docs atomically, switch clients/Gateway only after all pods are new, smoke-test all ten new routes plus all ten legacy negatives, observe 401/403/404/error counters, and rollback only after old-route smoke and readiness checks pass. Mixed old/new pod traffic is explicitly unsupported.

- [ ] **Step 3: Keep English/Korean README parity**

Update both module README class tables and authentication sections. The English README remains English and the Korean README remains Korean; only identifiers, URLs, commands, and exact error text stay unchanged. Use a parity check over the commitment route table and authentication contract rather than relying on visual review.

- [ ] **Step 4: Validate active docs**

Run:

```bash
rg -n 'api/v2' docs/api docs/runbooks docs/requirements/architecture.md appointment-api/README.md appointment-api/README.ko.md
rg -n '\bv2\b|version root|V2' docs/api docs/runbooks docs/requirements/architecture.md appointment-api/README.md appointment-api/README.ko.md
rg -n 'api/v2' appointment-api/src/main --glob '*.kt' --glob '!build/**'
rg -n 'api/v2' . --glob '*.md' --glob '*.kt' --glob '!build/**' | tee tmp/issue38-api-v2-residuals.txt
comm -3 \
  <(rg -o '/api/\{tenantCode\}/[^`| ]+' appointment-api/README.md | sort -u) \
  <(rg -o '/api/\{tenantCode\}/[^`| ]+' appointment-api/README.ko.md | sort -u)
git diff --check
```

Expected: no active API/runbook/README route or request example uses `/api/v2`; a short explicit statement that the root is reserved/unsupported is allowed. No stale version-root/V2 wording remains beyond that statement. Every residual `api/v2` hit is classified under the explicit historical or negative-test allowlist and the captured inventory is attached to the review evidence. README route/authentication entries have equivalent coverage in both languages.

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

Expected: only the two production files, their direct unit tests, and README class tables are listed. Route strings in legacy-negative tests are behavior fixtures, not symbol dependencies.

- [ ] **Step 2: Rename and update symbols without changing mappings**

Use `git mv`, update class/test references, and retain the same constructor dependencies. Do not add an alias class or a second Spring bean.

- [ ] **Step 3: Compile the affected test sources**

Run the controller unit tests from Task 2. Expected: no behavior or route regression is introduced by symbol cleanup.

## Task 8: Proportional validation and final diff convergence

**Files:** All changed files from Tasks 1–7; no new scope without spec update.

- [ ] **Step 1: Run the smallest affected tests again**

Run the complete focused matrix as separate invocations. Keep pure unit classes together only when they do not touch Exposed; run every DB-backed or Spring-context class in its own `--no-parallel` invocation even though the current test resource disables global parallel execution, because class-mode settings and the process-global Exposed default database still make explicit serialization and resource locks the safer proof:

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.ActorContextResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentExceptionResolutionTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.TenantAuthorizationManagerTest' \
  --no-parallel --no-build-cache --rerun-tasks

./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathValidationFilterTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantContextFilterTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantContextTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentAccessResolverTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.AppointmentCommitmentSecurityIntegrationTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.AppointmentCommitmentOpenApiTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.AppointmentCommitmentFeatureOffIntegrationTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.ProfileReevaluationEndpointSecurityTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.NotificationOpenApiTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.AdminAppointmentV2Test' \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.AppointmentRequestV2Test' \
  --no-parallel --no-build-cache --rerun-tasks
```

Expected: all selected tests pass; pre-auth parser ordering, selected downstream tenant, controller mappings, context cleanup, scope errors, and legacy negatives are all reverified. Container-backed integration work remains sequential.

- [ ] **Step 2: Run the full affected module test**

Run:

```bash
./gradlew :appointment-api:test --no-parallel --no-build-cache --rerun-tasks
```

Expected: `appointment-api` test task passes. Record the actual test count and any pre-existing deprecation warnings separately from failures.

- [ ] **Step 3: Run static and scope checks**

Run:

```bash
git diff --check
rg -n 'api/v2' appointment-api/src/main --glob '*.kt' --glob '!build/**'
rg -n 'singleOrNull\(\)' \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentCommitmentHttpSupport.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentCommitmentAccessResolver.kt
rg -n 'api/v2' appointment-api/src/test --glob '*.kt' --glob '!build/**' | tee tmp/issue38-api-v2-test-residuals.txt
git status --short
```

Expected: no production `api/v2` route, classifier, or actor resolver retains the removed v2 authority; `appointment-api/src/main` must have zero literal `api/v2` hits after migration (the profile-reevaluation guard is tested at its own non-API path and is not an allowlist exception). The test residual inventory is allowlisted to legacy-negative route fixtures, feature-off assertions, and profile-reevaluation protection; any other hit is a blocker. The two authority files contain no `singleOrNull()` tenant selection (unrelated `singleOrNull()` in other domains is not a blocker). No unrelated file is changed.

- [ ] **Step 4: Run Kotlin final checklist and performance/stability scan**

Inspect the final diff for new `!!`, blocking calls on coroutine dispatchers, swallowed cancellation, Exposed transaction drift, lifecycle cleanup, matcher ordering, and route-level authorization cache behavior. Treat the bounded route/JWT/context work as the relevant hot path: preserve the existing JWT limits (token ≤8192 bytes, tenant claim count ≤64, tenant code ≤64 characters), add boundary/duplicate/max-cardinality tests, and verify the filter plus access resolver perform exactly the two intentional active-tenant lookups per authenticated commitment request. Document the no-cache decision: the first lookup establishes request tenant context and the second revalidates service scope; no cross-layer cache is introduced in this issue. Record timings or lookup counts from the focused tests rather than claiming performance N/A; no JMH dependency is needed.

## Task 9: Review, lesson, and delivery handoff

**Files:**
- Create: `docs/reviews/2026-08-05-issue-38-tenant-authority-plan-review.ko.md` (plan gate)
- Create: `docs/reviews/2026-08-05-issue-38-tenant-authority-review.ko.md` (final diff gate)
- Create: `docs/lessons/2026-08-05-issue-38-tenant-authority.md`
- Modify: PR body only after branch validation

- [ ] **Step 1: Run six-perspective final review**

Review security, stability, performance, API contract, Kotlin quality, and documentation against the exact diff. P0/P1 must be zero. Record route inventory, 401/403/404 matrix, filter error envelopes, selected-tenant proof, lookup-count/no-cache rationale, rollout checklist, test commands/results, and residual risks in the Korean review artifact. The review artifact is Korean-first; preserve English only for code, commands, identifiers, and exact error codes.

- [ ] **Step 2: Capture the lesson before PR creation**

Record the durable decision that a client-supplied header is never tenant authority and that one explicit path selector is preferable to a hidden Gateway mode. Include the exact tests that prove multi-tenant selection, pre-auth ambiguity rejection, filter ordering, fail-closed scope errors, lookup bounds, and legacy `/api/v2` rejection. Write the lesson in Korean with English identifiers/commands preserved.

- [ ] **Step 3: Commit implementation, review, and lesson with Lore trailers**

Use separate focused commits when the diff is large enough to review independently. Every commit must contain `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, and `Not-tested` trailers.

- [ ] **Step 4: Publish and verify the English issue-linked PR**

Push `issue-38-tenant-authority`, create the PR against `develop`, link `Closes #38`, preserve issue assignee/label/milestone parity, and make `## DoD Status` the final PR section. Verify the live body and exact remote head before waiting for CI with:

```bash
gh pr view <number> --json body,headRefName,headRefOid,baseRefName,labels,assignees,milestone,statusCheckRollup,reviews,reviewThreads,closingIssuesReferences
git rev-parse HEAD origin/issue-38-tenant-authority
```

After CI completes, repeat the same readback against the exact `headRefOid`, confirm every required check is successful, all review threads are resolved, the final body section is `## DoD Status`, and issue/PR metadata still matches. Before merge, repeat the readback against the exact head, then obtain fresh user approval tied to that SHA. Never enable auto-merge.

## Risk prediction and rerun points

| Risk | Signal | Mitigation | Rerun point / rollback |
|---|---|---|---|
| Security matcher order lets STAFF reach admin commitment mutation | Integration test returns a domain response instead of 403 for STAFF | Put explicit commitment admin rules before generic tenant writes; test role matrix | Rerun Task 3; revert matcher-only commit if unresolved |
| Multi-tenant JWT remains rejected by hidden `singleOrNull()` | Allowed tenant set size 2 fails on a selected path | Pass path tenant through helper and recheck membership in `ActorContextResolver` | Rerun Task 2; revert helper/controller commit |
| Selected path tenant is lost before service scope resolution | A/B JWT reaches the controller but `AppointmentCommitmentAccessResolver` selects no or the wrong tenant | Store `selectedTenantCode` in `ActorContext`, verify it in the access resolver, and assert the exact service call for tenant B | Rerun Task 2 and the access-resolver matrix; no merge while any authority `singleOrNull()` remains |
| Unknown/invalid tenant leaks data or wrong error envelope | Unknown path returns 200, or 403/404 mismatch appears | Shared canonical rule, filter DB lookup, path-specific error registry tests | Rerun Tasks 1 and 4; revert route commit |
| Pre-auth path ambiguity reaches JWT or a matcher | JWT parser invocation or controller marker appears for `%2f`, `%2e`, `%5c`, semicolon, or double-encoded paths | Validate raw and decoded URI forms before JWT, reject malformed/reserved roots with foundation 404, and prove chain-only filter order | Rerun Task 1/3 pre-auth tests; no merge if parser is invoked |
| New validation filter runs twice or out of order | Filter executes outside the chain, before correlation, or twice per request | Add disabled servlet registration and assert correlation → validation → JWT → tenant-context order | Rerun Task 1/3 security configuration tests |
| Existing API route collides with commitment route | Spring mapping ambiguity or OpenAPI duplicate path | Keep commitment suffixes under existing appointment base; run context/OpenAPI tests | Stop before merge; split route mapping correction into a follow-up |
| TenantContext leaks across servlet/coroutine boundaries | Context remains after filter/test or appears in another dispatcher | `try/finally`/ThreadContextElement tests with coroutine-test | Rerun Task 5; no merge until cleanup is proven |
| Tenant lookup outage is misreported as tenant absence or authorization failure | DB exception becomes 404/403 or leaks a stack trace | Map lookup failure to the existing privacy-safe internal error, attach correlation-only logging, and assert cleanup plus response code | Rerun Task 5; hold merge until the failure contract is stable |
| Duplicate tenant lookup adds unbounded hot-path work | Unexpected repository call count or latency growth | Keep exactly two bounded lookups per authenticated commitment request and record the explicit no-cache decision | Rerun Task 8 performance/stability scan; follow up separately for measured regression |
| Documentation/client examples drift from code | Active `rg api/v2` hit or bilingual README mismatch | Update active docs in the same branch and run scoped search | Rerun Task 6; hold PR until parity is restored |
| Mixed old/new pods expose incompatible route contracts | Requests fail during rolling deployment or rollback | Require api-enabled/drain gate, atomic controller/security/docs cutover, readiness/smoke checks, and document mixed traffic unsupported | Stop rollout and execute the runbook rollback checklist |
| Broad route change causes unexpected HTTP behavior or latency | Full module failures or integration timeout | No DB/query/key change; sequential integration test and diff scan | Revert entire PR; no compatibility alias is added implicitly |

## Self-review result

- Every spec acceptance criterion maps to at least one task in the traceability matrix.
- No task depends on an undefined symbol; the new helper signature and matcher helpers are shown explicitly.
- RED/GREEN commands are present before each behavior change, with expected outcomes.
- The approved spec revision and plan-review artifact record the new acceptance/security clarifications; unrelated historical documents remain immutable to preserve decision history.
- No dependency, migration, key/FK, or production database query change is planned.
