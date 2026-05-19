# Architecture Blueprint — EPIC #16 Multitenancy

> Source: Step 2-A feature-dev:code-architect output (Opus)
> Date: 2026-05-19
> Purpose: Codex CLI second-opinion review input

## Confirmed user decisions

1. Shallow isolation (A): `TenantGroup` → `Clinics` + `Holidays` FK only; 20 other tables inherit via Clinic FK
2. URL `/api/{tenantCode}/...` full rewrite (10 controllers + all tests; Angular = Phase 2)
3. JWT `allowedTenants` claim; URL tenantCode mismatch → 403
4. Tenant ≠ user locale (orthogonal)
5. Repository requires explicit `tenantGroupId` parameter (Exposed `StatementInterceptor` does NOT support auto-WHERE injection — verified via Exposed 1.3.0 sources jar)
6. `TenantContext` = ThreadLocal + `CoroutineContext.Element` for suspend boundary
7. `AuthorizationManager<RequestAuthorizationContext>` with `requestMatchers("/api/{tenantCode}/**").access(...)`
8. Flyway 4-step (V3 schema+nullable, V4 backfill, V5 NOT NULL, V6 FK+composite UNIQUE) × 3 DB (h2/mysql/postgresql)
9. `Holidays.holidayDate` global UNIQUE → `(tenant_group_id, holiday_date)` composite
10. New `integration-test` profile activates real `SecurityConfig` (dev/test keep `NoOpSecurityConfig`)
11. `TenantContextFilter` runs in ALL profiles (dev/test included) to keep service code uniform
12. `TenantClinicAccessChecker` (service-layer) verifies `clinicId → tenantGroupId` (Caffeine cache); guards against clinicId spoofing
13. Admin endpoints also tenant-scoped: `/api/{tenantCode}/admin/stats`
14. `suspend` paths MUST NOT use `TenantContext.current()` after coroutine boundary; pass `tenantGroupId` explicitly
15. Frontend i18n / data translation / cross-module (notification/event/solver) = Phase 2

## Environment (verified)

- Kotlin 2.3.21
- Java **21** (not 25 as header claimed)
- Spring Boot 4.0.6 / Spring Security 6.x
- Exposed 1.3.0

## Component map (new + modified)

### New (appointment-core)
- `model/tables/TenantGroups.kt` — LongIdTable `scheduling_tenant_groups`: `tenantCode` UNIQUE, `displayName`, `active`, `createdAt`
- `model/dto/TenantGroupRecord.kt` — Serializable + serialVersionUID
- `repository/TenantGroupRepository.kt` — `LongJdbcRepository<TenantGroupRecord>` + `findByCode` / `findIdByCode`

### New (appointment-api/tenant)
- `tenant/TenantInfo.kt` — `data class(tenantGroupId, tenantCode)`
- `tenant/TenantContext.kt` — ThreadLocal + `asContextElement()`
- `tenant/TenantContextElement.kt` — `ThreadContextElement<TenantInfo?>`
- `tenant/TenantContextFilter.kt` — `OncePerRequestFilter`, extracts path segment, Caffeine cache `tenantCode → tenantGroupId` (256, 5m), `try/finally clear()`, all profiles
- `tenant/TenantClinicAccessChecker.kt` — service-layer guard, Caffeine `clinicId → tenantGroupId` (1024, 10m)
- `tenant/TenantNotAllowedException.kt` — RuntimeException → 403
- `tenant/TenantConfig.kt` — `@Configuration` bean registrations

### New (appointment-api/security)
- `security/TenantAuthorizationManager.kt` — `AuthorizationManager<RequestAuthorizationContext>`; reads `ctx.variables["tenantCode"]`, checks against `principal.allowedTenants`

### Modified
- `security/SchedulingUserPrincipal.kt` — add `allowedTenants: List<String>`, bump `serialVersionUID = 2L`
- `security/JwtTokenParser.kt` — add `CLAIM_ALLOWED_TENANTS`, parse list
- `security/SecurityConfig.kt` — DSL rebuild with `requestMatchers("/api/{tenantCode}/...").access(allOf(tenantAuthorizationManager, roleManager))`, `anyRequest().denyAll()`
- `model/tables/Clinics.kt` — add `tenantGroupId = long("tenant_group_id").references(TenantGroups.id)`
- `model/tables/Holidays.kt` — add `tenantGroupId`, remove `holidayDate.uniqueIndex()`, add composite `uniqueIndex(tenantGroupId, holidayDate)`
- `model/dto/{ClinicRecord,HolidayRecord}.kt` — add `tenantGroupId: Long`, bump serialVersionUID
- `repository/ClinicRepository.kt` — add `findByTenant`, `findByIdAndTenant`, `findTenantGroupId`, `findPage(tenantGroupId, …)`
- `repository/HolidayRepository.kt` — all methods take `tenantGroupId` first parameter
- All 10 controllers — `@RequestMapping` path rewrite + `tenantClinicAccessChecker.verify(clinicId)` insertion
- `TestJwtProvider.kt` — add `allowedTenants` parameter, default `["tenant-default"]` (backward compat for 100+ tests)
- `GlobalExceptionHandler.kt` — `TenantNotAllowedException → 403`

### Flyway (12 files: 4 versions × 3 DB)
- V3: `add_tenant_groups.sql` — create `scheduling_tenant_groups`, add `tenant_group_id BIGINT NULL` to clinics + holidays, drop global UNIQUE on `holiday_date` (DB-specific syntax)
- V4: `seed_default_tenant.sql` — insert `tenant-default`, backfill existing rows
- V5: `tenant_group_not_null.sql` — `ALTER COLUMN SET NOT NULL` (H2/PG) / `MODIFY` (MySQL)
- V6: `tenant_constraints.sql` — FK + composite UNIQUE `(tenant_group_id, holiday_date)` + indexes

### New profile
- `application-integration-test.yml` (main + test) — JWT enabled, H2, Flyway on; activates `SecurityConfig` (matches `!dev & !test`)

### Test infra
- `integration/AbstractIntegrationApiTest.kt` — `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("integration-test")`, seeds `tenant-a` / `tenant-b` + 2 clinics
- `integration/MultitenancyIntegrationTest.kt` — 6 cases: 200, URL-vs-claim 403, clinic-vs-tenant 403, 404 unknown tenant, 401 no JWT, holiday cross-tenant isolation
- `tenant/TenantContextFilterTest.kt`, `tenant/TenantClinicAccessCheckerTest.kt`, `security/TenantAuthorizationManagerTest.kt`

## Data flow (HTTP request)

```
Client → SecurityFilterChain
  → JwtAuthenticationFilter (parse JWT → principal with allowedTenants)
  → TenantContextFilter (path segment 1 = tenantCode → lookup tenantGroupId → TenantContext.set, try/finally clear)
  → TenantAuthorizationManager (ctx.variables["tenantCode"] ∈ principal.allowedTenants ? GRANTED : DENIED 403)
  → Controller (suspend: capture TenantContext at entry, pass tenantGroupId explicitly)
    → TenantClinicAccessChecker.verify(clinicId)
    → Service
    → Repository (tenantGroupId as first parameter, WHERE clause)
    → DB
```

## Error matrix

| Situation | HTTP | Source |
|---|---|---|
| JWT missing/expired | 401 | Spring default |
| Unknown tenantCode in path | 404 | `TenantContextFilter.sendError(NOT_FOUND)` |
| tenantCode vs allowedTenants mismatch | 403 | `TenantAuthorizationManager` |
| clinicId belongs to other tenant | 403 | `TenantClinicAccessChecker → TenantNotAllowedException` |
| Insufficient role | 403 | `AuthorityAuthorizationManager` |

## Build sequence (phases A→B→C→D)

- A: Domain (TenantGroups + repo + Flyway V3-V6 × 3 DB + Clinic/Holiday table/DTO/repo updates + unit tests)
- B: Security + Tenant (TenantInfo/Context/Element/Filter/AccessChecker/Exception/Config + TenantAuthorizationManager + JwtTokenParser/SchedulingUserPrincipal/SecurityConfig + TestJwtProvider)
- C: Controller URL rewrite (10 files) + verify(clinicId) insertion + existing controller test URL updates
- D: integration-test profile + AbstractIntegrationApiTest + MultitenancyIntegrationTest + suspend path verification

## Key risks (from blueprint Section 8)

| # | Risk | Mitigation |
|---|---|---|
| R1 | 10 controller URL change breaks all tests + Frontend | Phase C single-commit sed-style; Frontend = Phase 2 |
| R2 | V4 backfill failure → V5 NOT NULL fails | V4 ends with `SELECT COUNT(*) WHERE tenant_group_id IS NULL` validation, Flyway `afterMigrate` callback |
| R3 | H2 holiday_date UNIQUE auto-name unpredictable → V3 H2 fails | Pre-verify with `INFORMATION_SCHEMA`, fallback `DROP CONSTRAINT IF EXISTS` + index drop |
| R4 | ThreadLocal leak (coroutine, virtual thread reuse) | try/finally clear; decision #14 explicit-arg rule for suspend |
| R5 | clinicId spoofing across tenant | `TenantClinicAccessChecker.verify` mandatory at service entry; PR review checklist |
| R6 | bluetape4k has no multitenancy module → self-built | Simple <300 LOC; 100% unit-tested |
| R7 | `appointment-notification` / `appointment-event` / `appointment-solver` lack HTTP context | Out of scope; Phase 2 separate issue |
| R8 | SecurityConfig DSL first-match wins → admin endpoint may leak | DSL unit tests with MockMvc per matcher case; code review |
| R9 | Caffeine cache stale on clinic tenant move | Currently no clinic-move API; future requires invalidate hook; KDoc warning |
| R10 | `TestJwtProvider` change ripples to 100+ tests | Default `allowedTenants=["tenant-default"]` for backward compat |

## Decisions intentionally rejected

- Schema-per-tenant / DB-per-tenant — discarded (Flyway operational cost)
- subdomain tenant identification — discarded (URL path more explicit, cache-friendly)
- PostgreSQL RLS — discarded (H2/MySQL don't support; breaks test compat)
- Exposed `StatementInterceptor` auto-WHERE — discarded (Exposed 1.3.0 does NOT provide WHERE-clause hook; statement-lifecycle only)
- JWT-only tenant resolution — discarded (URL path is source of truth; JWT is the guard)

---
END OF BLUEPRINT
