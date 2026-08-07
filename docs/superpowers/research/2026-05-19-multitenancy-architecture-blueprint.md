# 아키텍처 청사진 — EPIC #16 Multitenancy

> 출처: Step 2-A feature-dev:code-architect output (Opus)
> 날짜: 2026-05-19
> 목적: Codex CLI second-opinion review input

## 확인된 사용자 결정

1. Shallow isolation (A): `TenantGroup` → `Clinics` + `Holidays` FK만 사용하고, 나머지 20개 table은 Clinic FK를 통해 상속
2. URL `/api/{tenantCode}/...` 전체 rewrite(10개 controller + 모든 test; Angular는 Phase 2)
3. JWT `allowedTenants` claim을 사용하고 URL tenantCode mismatch는 403
4. Tenant와 user locale은 서로 다름(orthogonal)
5. Repository는 명시적 `tenantGroupId` parameter를 요구함(Exposed `StatementInterceptor`는 auto-WHERE injection을 지원하지 않음 — Exposed 1.3.0 sources jar로 확인)
6. Suspend 경계의 `TenantContext`는 ThreadLocal + `CoroutineContext.Element`
7. `requestMatchers("/api/{tenantCode}/**").access(...)`를 사용하는 `AuthorizationManager<RequestAuthorizationContext>`
8. Flyway 4-step(V3 schema+nullable, V4 backfill, V5 NOT NULL, V6 FK+composite UNIQUE) × 3 DB(h2/mysql/postgresql)
9. `Holidays.holidayDate` global UNIQUE를 `(tenant_group_id, holiday_date)` composite으로 변경
10. 새 `integration-test` profile은 실제 `SecurityConfig`를 활성화함(dev/test는 `NoOpSecurityConfig` 유지)
11. Service code를 uniform하게 유지하기 위해 `TenantContextFilter`는 ALL profile(dev/test 포함)에서 실행
12. `TenantClinicAccessChecker`(service-layer)는 `clinicId → tenantGroupId`를 확인(Caffeine cache)하고 clinicId spoofing을 방어
13. Admin endpoint도 tenant-scoped: `/api/{tenantCode}/admin/stats`
14. `suspend` path는 coroutine boundary 이후 `TenantContext.current()`를 사용하면 MUST NOT 함. `tenantGroupId`를 명시적으로 전달
15. Frontend i18n / data translation / cross-module(notification/event/solver)은 Phase 2

## 환경(확인됨)

- Kotlin 2.3.21
- Java **21**(header에 기재된 25가 아님)
- Spring Boot 4.0.6 / Spring Security 6.x
- Exposed 1.3.0

## Component map(신규 + 수정)

### 신규(appointment-core)
- `model/tables/TenantGroups.kt` — LongIdTable `scheduling_tenant_groups`: `tenantCode` UNIQUE, `displayName`, `active`, `createdAt`
- `model/dto/TenantGroupRecord.kt` — Serializable + serialVersionUID
- `repository/TenantGroupRepository.kt` — `LongJdbcRepository<TenantGroupRecord>` + `findByCode` / `findIdByCode`

### 신규(appointment-api/tenant)
- `tenant/TenantInfo.kt` — `data class(tenantGroupId, tenantCode)`
- `tenant/TenantContext.kt` — ThreadLocal + `asContextElement()`
- `tenant/TenantContextElement.kt` — `ThreadContextElement<TenantInfo?>`
- `tenant/TenantContextFilter.kt` — `OncePerRequestFilter`, path segment 추출, Caffeine cache `tenantCode → tenantGroupId`(256, 5m), `try/finally clear()`, 모든 profile
- `tenant/TenantClinicAccessChecker.kt` — service-layer guard, Caffeine `clinicId → tenantGroupId`(1024, 10m)
- `tenant/TenantNotAllowedException.kt` — RuntimeException → 403
- `tenant/TenantConfig.kt` — `@Configuration` bean registration

### 신규(appointment-api/security)
- `security/TenantAuthorizationManager.kt` — `AuthorizationManager<RequestAuthorizationContext>`; `ctx.variables["tenantCode"]`를 읽고 `principal.allowedTenants`와 비교

### 수정
- `security/SchedulingUserPrincipal.kt` — `allowedTenants: List<String>` 추가, `serialVersionUID = 2L`로 증가
- `security/JwtTokenParser.kt` — `CLAIM_ALLOWED_TENANTS` 추가, list parse
- `security/SecurityConfig.kt` — `requestMatchers("/api/{tenantCode}/...").access(allOf(tenantAuthorizationManager, roleManager))`, `anyRequest().denyAll()`을 사용하는 DSL 재구성
- `model/tables/Clinics.kt` — `tenantGroupId = long("tenant_group_id").references(TenantGroups.id)` 추가
- `model/tables/Holidays.kt` — `tenantGroupId` 추가, `holidayDate.uniqueIndex()` 제거, composite `uniqueIndex(tenantGroupId, holidayDate)` 추가
- `model/dto/{ClinicRecord,HolidayRecord}.kt` — `tenantGroupId: Long` 추가, serialVersionUID 증가
- `repository/ClinicRepository.kt` — `findByTenant`, `findByIdAndTenant`, `findTenantGroupId`, `findPage(tenantGroupId, …)` 추가
- `repository/HolidayRepository.kt` — 모든 method가 `tenantGroupId`를 첫 parameter로 받음
- 모든 10개 controller — `@RequestMapping` path rewrite + `tenantClinicAccessChecker.verify(clinicId)` 삽입
- `TestJwtProvider.kt` — `allowedTenants` parameter 추가, default는 `["tenant-default"]`(100개 이상 test의 backward compat)
- `GlobalExceptionHandler.kt` — `TenantNotAllowedException → 403`

### Flyway(12개 file: 4 version × 3 DB)
- V3: `add_tenant_groups.sql` — `scheduling_tenant_groups` 생성, clinics + holidays에 `tenant_group_id BIGINT NULL` 추가, `holiday_date` global UNIQUE 제거(DB별 syntax)
- V4: `seed_default_tenant.sql` — `tenant-default` 삽입, 기존 row backfill
- V5: `tenant_group_not_null.sql` — `ALTER COLUMN SET NOT NULL`(H2/PG) / `MODIFY`(MySQL)
- V6: `tenant_constraints.sql` — FK + composite UNIQUE `(tenant_group_id, holiday_date)` + index

### 신규 profile
- `application-integration-test.yml`(main + test) — JWT enabled, H2, Flyway on; `SecurityConfig` 활성화(`!dev & !test`와 일치)

### Test infra
- `integration/AbstractIntegrationApiTest.kt` — `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("integration-test")`, `tenant-a` / `tenant-b`와 clinic 2개 seed
- `integration/MultitenancyIntegrationTest.kt` — 6개 case: 200, URL-vs-claim 403, clinic-vs-tenant 403, unknown tenant 404, JWT 없음 401, holiday cross-tenant isolation
- `tenant/TenantContextFilterTest.kt`, `tenant/TenantClinicAccessCheckerTest.kt`, `security/TenantAuthorizationManagerTest.kt`

## Data flow(HTTP request)

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

| 상황 | HTTP | 출처 |
|---|---|---|
| JWT missing/expired | 401 | Spring default |
| Path의 unknown tenantCode | 404 | `TenantContextFilter.sendError(NOT_FOUND)` |
| tenantCode와 allowedTenants mismatch | 403 | `TenantAuthorizationManager` |
| clinicId가 다른 tenant에 속함 | 403 | `TenantClinicAccessChecker → TenantNotAllowedException` |
| Role 부족 | 403 | `AuthorityAuthorizationManager` |

## Build sequence(phases A→B→C→D)

- A: Domain(TenantGroups + repo + Flyway V3-V6 × 3 DB + Clinic/Holiday table/DTO/repo update + unit test)
- B: Security + Tenant(TenantInfo/Context/Element/Filter/AccessChecker/Exception/Config + TenantAuthorizationManager + JwtTokenParser/SchedulingUserPrincipal/SecurityConfig + TestJwtProvider)
- C: Controller URL rewrite(10개 file) + verify(clinicId) 삽입 + 기존 controller test URL 갱신
- D: integration-test profile + AbstractIntegrationApiTest + MultitenancyIntegrationTest + suspend path 검증

## 주요 위험(blueprint Section 8에서 도출)

| # | 위험 | 완화책 |
|---|---|---|
| R1 | 10개 controller URL 변경으로 모든 test와 Frontend가 깨짐 | Phase C를 sed-style single-commit으로 처리; Frontend는 Phase 2 |
| R2 | V4 backfill failure로 V5 NOT NULL 실패 | V4 마지막에 `SELECT COUNT(*) WHERE tenant_group_id IS NULL` validation과 Flyway `afterMigrate` callback 실행 |
| R3 | H2 holiday_date UNIQUE auto-name이 예측 불가해 V3 H2 실패 | `INFORMATION_SCHEMA`로 사전 확인하고 `DROP CONSTRAINT IF EXISTS` + index drop으로 fallback |
| R4 | ThreadLocal leak(coroutine, virtual thread reuse) | try/finally clear; decision #14의 suspend explicit-arg rule |
| R5 | Tenant 간 clinicId spoofing | Service entry에서 `TenantClinicAccessChecker.verify`를 mandatory로 실행; PR review checklist |
| R6 | bluetape4k에 multitenancy module이 없어 자체 구현 필요 | 300 LOC 미만의 단순 구현; 100% unit-tested |
| R7 | `appointment-notification` / `appointment-event` / `appointment-solver`에 HTTP context가 없음 | Scope 밖; Phase 2 별도 issue |
| R8 | SecurityConfig DSL의 first-match wins로 admin endpoint가 노출될 수 있음 | Matcher case별 MockMvc DSL unit test; code review |
| R9 | Clinic tenant 이동 시 Caffeine cache stale | 현재 clinic-move API 없음; 향후 invalidate hook 필요; KDoc warning |
| R10 | `TestJwtProvider` 변경이 100개 이상 test에 ripple | `allowedTenants=["tenant-default"]`를 default로 사용해 backward compat 유지 |

## 의도적으로 거부한 결정

- Schema-per-tenant / DB-per-tenant — 폐기(Flyway operational cost)
- Subdomain tenant identification — 폐기(URL path가 더 명시적이고 cache-friendly)
- PostgreSQL RLS — 폐기(H2/MySQL이 지원하지 않아 test compat를 깨뜨림)
- Exposed `StatementInterceptor` auto-WHERE — 폐기(Exposed 1.3.0은 WHERE-clause hook을 제공하지 않고 statement-lifecycle만 제공)
- JWT-only tenant resolution — 폐기(URL path가 source of truth이고 JWT는 guard임)

---
END OF BLUEPRINT
