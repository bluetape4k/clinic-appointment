# EPIC #16 멀티테넌시 구현 계획

> Date: 2026-05-19
> Spec: `docs/superpowers/specs/2026-05-19-multitenancy-design.md`
> Scope: Phase 1 backend tenant isolation, JWT guard, Flyway migration, tests, CI
> Workflow: `bluetape4k-workflow` Type A / `bluetape4k-design` Step 3

## 1. 목표

GitHub issue #16의 Phase 1 범위를 구현한다.

- URL path `/api/{tenantCode}/...` 로 tenant를 식별한다.
- JWT `allowedTenants` 와 URL tenantCode를 비교해 mismatch는 403으로 차단한다.
- `TenantGroup` 이 `Clinics` 와 `Holidays` 를 소유하도록 schema를 확장한다.
- 자식 리소스 ID 조회는 clinic join guard를 통해 cross-tenant 조회를 404로 차단한다.
- Phase 1에서는 frontend i18n, event/notification/solver tenant propagation은 제외한다.

## 2. 선행 검증과 현재 코드 의존성

- 현재 controller는 10개이며 `/api/...` 최상위 mapping을 사용한다.
- 현재 보안 설정은 `SecurityConfig @Profile("!dev & !test")`, `NoOpSecurityConfig @Profile("dev", "test")` 이다. 구현 시 `integration-test` 가 `test` 와 같이 활성화되어도 real security chain이 켜지도록 profile expression을 먼저 바꾼다.
- 현재 Flyway는 `V1__init_schema.sql`, `V2__add_equipment_unavailabilities.sql` 만 있고 H2/MySQL/PostgreSQL 3개 디렉터리를 사용한다.
- 현재 `scheduling_holidays.holiday_date` 는 3개 DB 모두 global `UNIQUE` 이다.
- current-code dependency: 구현 직전 `git status --short`, `rg "@RequestMapping|GetMapping|PostMapping|PatchMapping|DeleteMapping" appointment-api/src/main/kotlin/.../controller`, `rg "holiday_date.*UNIQUE" appointment-api/src/main/resources/db/migration` 을 재확인한다.

## 3. 구현 순서

### Task 1 — TenantGroup domain model

- complexity: medium
- `$bluetape4k-patterns`: 적용. Exposed table/entity/record는 기존 `model/tables`, `model/entities`, `model/dto`, mapper 스타일을 따른다. 모든 DB 접근은 `transaction {}` 안에서만 수행한다.
- expected files:
  - `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/TenantGroups.kt`
  - `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/entities/TenantGroupEntity.kt`
  - `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/TenantGroupRecord.kt`
  - `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/TenantGroupRepository.kt`
  - 기존 `Clinics`, `Holidays`, mapper 파일
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`
  - `SchemaUtils.createMissingTablesAndColumns(...)` 를 직접 쓰는 core tests
- work:
  - `tenantCode`, `displayName`, `active`, timestamp 컬럼을 추가한다.
  - `Clinics` 와 `Holidays` 에 nullable/non-null Kotlin column 타입을 spec 최종 schema와 맞춰 추가한다.
  - `TenantGroupRepository.findActiveByCode(code)` 를 추가한다.
  - `TableSchemaTest.allTables` 에서 `TenantGroups` 를 `Clinics`/`Holidays` 보다 먼저 생성되도록 배치한다.
  - core 테스트 fixtures 는 `Clinics`/`Holidays` insert 전에 `tenant-default` row 를 seed하고 FK 값을 명시한다.
- verification:
  - `./gradlew :appointment-core:compileKotlin`
  - `./gradlew :appointment-core:test --tests "*TableSchemaTest"`
  - `./gradlew :appointment-core:test --tests "*SlotCalculationServiceTest" --tests "*ClosureRescheduleServiceTest" --tests "*EquipmentUnavailabilityServiceTest"`
- rollback point: 이 단계 실패 시 schema/model 변경만 되돌리면 후속 API 변경과 분리된다.

### Task 2 — Flyway V3-V6 migrations

- complexity: high
- `$bluetape4k-patterns`: SQL은 DB별 문법 차이를 명시하고, Kotlin callback/test는 기존 Spring/Flyway/Testcontainers 패턴을 따른다. Testcontainers-backed Gradle commands는 병렬 실행하지 않는다.
- expected files:
  - `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V3__add_tenant_groups.sql`
  - `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V4__seed_default_tenant.sql`
  - `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V5__tenant_group_not_null.sql`
  - `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V6__tenant_constraints.sql`
  - `appointment-api/src/main/kotlin/.../migration/MultitenancyAfterMigrateCallback.kt`
  - `appointment-api/src/test/kotlin/.../migration/MultitenancyMigrationTest.kt`
- work:
  - V3: tenant table, nullable FKs, `holiday_date` global unique 제거.
  - V4: `tenant-default` seed, existing clinics/holidays backfill.
  - V5: tenant FK `NOT NULL`.
  - V6: FK, composite unique, index 추가.
  - callback: V4 이후 clinics/holidays null FK count가 0인지 fail-fast 검증.
- verification:
  - `./gradlew :appointment-api:test --tests "*.MultitenancyMigrationTest"`
  - `./gradlew :appointment-api:test --tests "*.MultitenancyMigrationTest" -Dspring.profiles.active=test,test-postgresql`
  - `./gradlew :appointment-api:test --tests "*.MultitenancyMigrationTest" -Dspring.profiles.active=test,test-mysql`
- rollback point: V3-V6 중 실패 시 이전 Flyway version에서 멈추므로 `flyway repair` 후 해당 migration만 수정한다. 이미 적용된 공유 DB에는 destructive rollback을 하지 않는다.

### Task 3 — Tenant API context and security guard

- complexity: high
- `$bluetape4k-patterns`: Kotlin null-safety, explicit validation, English KDoc for public/internal API surface, no ad hoc ThreadLocal access outside tenant package. Coroutine context propagation은 `ThreadContextElement` 로 테스트한다.
- expected files:
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantInfo.kt`
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContext.kt`
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextElement.kt`
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilter.kt`
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/TenantAuthorizationManager.kt`
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SchedulingUserPrincipal.kt`
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParser.kt`
  - `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/TestJwtProvider.kt`
- work:
  - JWT parser에 `allowedTenants` claim을 추가하고 기존 테스트 호환 기본값을 유지한다.
  - filter는 unauthenticated 요청에서 tenant DB lookup을 skip해 401로 귀결되게 한다.
  - authenticated + unknown tenant는 404, known tenant + disallowed claim은 403.
  - SecurityConfig matcher는 admin-specific matcher를 generic `/api/{tenantCode}/**` 보다 먼저 둔다.
- verification:
  - `./gradlew :appointment-api:test --tests "*.JwtTokenParserTest"`
  - `./gradlew :appointment-api:test --tests "*.TenantContext*Test"`
  - `./gradlew :appointment-api:test --tests "*.SecurityProfileAssertionTest"`
  - `rg "TenantContext\\.current\\(\\)" appointment-api/src/main | grep -v "tenant/"` returns 0.
- rollback point: filter/security changes can be reverted independently before controller route rewrites.

### Task 4 — Repository tenant join guards

- complexity: high
- `$bluetape4k-patterns`: Exposed queries remain inside `transaction {}`. Prefer existing repository helpers and mapper APIs. Add narrow functions rather than replacing all existing call sites at once.
- expected files:
  - `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/*Repository.kt`
  - repository tests for guarded lookup paths
- work:
  - Add `findByIdAndTenant(id, tenantGroupId)` or equivalent guarded methods for clinic-owned child resources.
  - Guard by joining through `Clinics.tenantGroupId`, not by trusting caller-provided `clinicId` alone.
  - Preserve existing unguarded methods only where internal batch/stat paths have explicit scope and tests.
- verification:
  - `./gradlew :appointment-core:test --tests "*RepositoryTest"`
  - Add cross-tenant negative tests returning null/404-mappable result.
- rollback point: guarded methods are additive until controller/service call sites switch.

### Task 5 — Controller route rewrite and service wiring

- complexity: high
- `$bluetape4k-patterns`: Controllers validate path/body consistency early, use existing DTO/service style, and keep KDoc in English for newly added public API pieces.
- expected files:
  - all 10 files under `appointment-api/src/main/kotlin/.../controller/*Controller.kt`
  - related service calls in `appointment-core` if tenantGroupId must be threaded into queries
  - controller and integration tests
- work:
  - Rewrite route roots to `/api/{tenantCode}/...`.
  - Add `@PathVariable tenantCode: String` where needed, even if the controller only uses `TenantContext.requireCurrent()`, to keep mappings explicit.
  - Verify every endpoint with `clinicId` calls `TenantClinicAccessChecker.verify`.
  - Replace child-resource direct lookups with guarded repository methods.
- verification:
  - `rg -n '"/api/(clinics|doctors|appointments|slots|treatment-types|equipments|equipment-unavailabilities|reschedule|admin/stats)' appointment-api/src/main appointment-api/src/test` returns 0.
  - `./gradlew :appointment-api:test --tests "*ControllerTest"`
  - `./gradlew :appointment-api:test --tests "*.MultitenancyIntegrationTest"`
- rollback point: route rewrite is one reviewable commit section; do not mix with migration SQL changes.

### Task 6 — Integration tests and profile isolation

- complexity: high
- `$bluetape4k-patterns`: Use repo-approved Spring Boot/JUnit patterns and bluetape4k assertions. Do not use `@Testcontainers`; use existing singleton/container launchers or current project helpers.
- expected files:
  - `appointment-api/src/main/resources/application-integration-test.yml`
  - `appointment-api/src/test/resources/application-integration-test.yml`
  - `appointment-api/src/test/kotlin/.../integration/AbstractIntegrationApiTest.kt`
  - `appointment-api/src/test/kotlin/.../integration/MultitenancyIntegrationTest.kt`
  - `appointment-api/src/test/kotlin/.../security/SecurityProfileAssertionTest.kt`
- work:
  - Seed `tenant-a`, `tenant-b`, and matching clinics.
  - Cover allowed tenant success, disallowed tenant 403, unknown unauthenticated 401, unknown authenticated 404, cross-tenant clinic/resource 404, admin route matcher.
  - Keep old tests green with `tenant-default` default seed and `TestJwtProvider` default allowedTenants.
- verification:
  - `./gradlew :appointment-api:test --tests "*.SecurityProfileAssertionTest"`
  - `./gradlew :appointment-api:test --tests "*.MultitenancyIntegrationTest"`
  - `./gradlew :appointment-api:test`
- rollback point: profile/test infra changes can be reverted without touching DB migrations if failures are isolated to Spring context loading.

### Task 7 — CI and docs

- complexity: medium
- `$bluetape4k-patterns`: workflow YAML edits are small and anchored. Run `actionlint` and quote checks before push.
- expected files:
  - `.github/workflows/ci.yml`
  - `.github/workflows/nightly.yml`
  - `CHANGELOG.md` or WIP note if present
  - `README.md`, `README.ko.md` only if user-facing API examples are updated
- work:
  - Ensure API H2/PostgreSQL/MySQL matrix still runs and includes new migration/security tests.
  - Add or adjust nightly jobs only if current workflow excludes the new targeted tests.
  - Document backend URL breaking change and Phase 2 frontend scope.
- verification:
  - `rg -n "\\\\'" .github/workflows` returns 0.
  - `actionlint`
  - `rg "MultitenancyMigrationTest|SecurityProfileAssertionTest" .github/workflows`
- rollback point: CI changes can be reverted separately if local tests pass but workflow validation fails.

### Task 8 — Full verification, review, lesson, PR

- complexity: high
- `$bluetape4k-patterns`: review code against validation, logging, Exposed transaction, coroutine, and test conventions.
- work:
  - Run targeted tests first, then module build.
  - Run Step 6-R dual review: Codex review plus Claude Code CLI review if usage limit reset; otherwise record missing-CLI gap.
  - Add `docs/lessons/2026-05-19-multitenancy.md` before PR.
  - Commit with Lore protocol.
  - Create PR body in English with issue link, DoD checklist, P0/P1 convergence, test results, and CI status.
- verification:
  - `./gradlew :appointment-core:build :appointment-api:build`
  - `./gradlew :appointment-core:test :appointment-api:test`
  - 3-DB migration tests sequentially
  - `actionlint` if workflow files changed
- stop condition:
  - P0/P1 = 0, targeted tests pass, CI rollup is SUCCESS/SKIPPED, lessons committed, PR ready for user-requested merge.

## 4. Ordering constraints

1. Do not rewrite controller URLs before `TenantContextFilter`, `TenantAuthorizationManager`, and `TestJwtProvider` compatibility are ready.
2. Do not set `NOT NULL` in V5 until V4 backfill and callback validation prove zero null tenant FKs.
3. Do not rely on H2 migration tests as proof for MySQL/PostgreSQL DDL.
4. Do not use ThreadLocal tenant lookup in suspend service APIs; pass `tenantGroupId` explicitly.
5. Do not run Testcontainers-backed Gradle invocations in parallel across modules or worktrees.

## 5. Plan checklist

- [x] Plan path confirmed inside feature worktree
- [x] All tasks have complexity labels
- [x] `$bluetape4k-patterns` explicitly applied to every code-bearing task
- [x] Verification commands included
- [x] Expected changed modules/files listed
- [x] Docs/README/CI impact listed
- [x] Current-code ordering assumptions listed
- [x] Rollback/re-run points listed
