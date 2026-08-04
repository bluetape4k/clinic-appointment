# Tenant Query Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Issue #39의 scheduling, solver, reschedule, event, cache, notification 경계를 검증된 `(tenantGroupId, clinicId)` scope로 연결하고 cross-tenant 누수·side effect를 차단한다.

**Architecture:** `appointment-core`가 단일 `TenantClinicScope` value object와 tenant-aware repository 계약을 제공한다. API adapter는 검증된 tenant와 path clinic을 scope로 바꾸어 전달하고, solver·virtual thread·Spring event·notification worker는 thread-local context 대신 이 immutable scope를 사용한다. V21은 nullable tenant event-log column과 새 direct-claim index를 additive하게 배포하고, old-node drain 이후의 `NOT NULL` hardening은 별도 release로 남긴다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed v1 JDBC transactions, Timefold Solver, Flyway, H2/PostgreSQL/MySQL, JUnit 5 + Kotest assertions, bluetape4k singleton launchers.

---

## 작업 경계와 파일 소유권

| 작업 단위 | 생성/수정 파일 | 책임과 독점 write scope |
|---|---|---|
| Scope/API 기반 | `appointment-core/.../model/service/TenantClinicScope.kt`, `SlotQuery.kt`와 해당 core 테스트 | 양수 scope invariant, named API, source-breaking caller migration의 기준 |
| Repository/slot | core의 `ClinicRepository`, `HolidayRepository`, `DoctorRepository`, `EquipmentRepository`, `TreatmentTypeRepository`, `AppointmentRepository`, `RescheduleCandidateRepository`, `EquipmentUnavailabilityService`, `SlotCalculationService`와 테스트 | 모든 scheduling predicate와 query budget |
| Closure/API | `ClosureRescheduleService`, `RescheduleController`, `SlotController`, `RescheduleBatchStreamController`와 core/API 테스트 | process/stream/CAS/candidate read-confirm-auto 경계 |
| Solver | `SolverService`, 관련 converter/domain DTO와 solver 테스트 | 한 transaction snapshot, scoped facts, version recheck |
| Event | `AppointmentDomainEvent`, event log table/record/logger와 event 테스트 | positive scope, best-effort audit, in-process event 계약 |
| Notification | event notification repository/contracts와 notification listener/direct delivery/route/properties/readiness 및 테스트 | direct claim predicate, permit, canary scope, readiness |
| Migration/운영 | H2/PostgreSQL/MySQL V21, API migration tests, `docs/runbooks/tenant-query-isolation.ko.md` | additive DDL, preflight, rollback, metric/runbook |
| 문서/통합 | 각 모듈 `README.md`/`README.ko.md`, KDoc, review/lesson evidence | public caller 계약과 source-equivalent 문서 |

동시에 수정하는 lane은 위 행을 넘지 않는다. 파일 소유권이 겹치면 main lane이 통합 전에 해당 lane을 순차 실행하며, 어떤 worker도 커밋·Issue/PR·외부 side effect를 만들지 않는다.

## 공통 구현 규칙

- 모든 Exposed 호출은 기존 repository convention대로 `transaction {}` 내부에서 수행한다.
- caller validation에는 bluetape4k `require*` helper를 사용하고, 내부 invariant에는 `check`를 사용한다. production Kotlin에 `!!`, `println`, `System.out`, `System.err`를 추가하지 않는다.
- 새 public parameter/KDoc은 한국어로 작성하고, named argument로 scope 경계를 보인다.
- 테스트 DB는 `@Testcontainers`를 사용하지 않고 기존 singleton launcher와 `SchemaUtils.createMissingTablesAndColumns(Table)`, `Table.deleteAll()` 패턴을 사용한다.
- legacy tenantless public overload는 유지하지 않는다. 내부 테스트 helper는 scope를 명시하도록 함께 바꾼다.
- 새 tenant predicate는 hot loop에 별도 ownership query를 추가하지 않는다. 대표 fixture에서 slot query delta `0`, solver load delta `0`, closure top-level 추가 query 최대 `1`을 측정한다.

---

## Task 1: 공통 scope와 SlotQuery API를 먼저 고정

**Files:**

- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/service/TenantClinicScope.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/service/SlotQuery.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/service/TenantClinicScopeTest.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/SlotCalculationServiceTest.kt`의 모든 `SlotQuery` 생성 호출

- [ ] **Step 1: scope invariant 실패 테스트 작성**

```kotlin
class TenantClinicScopeTest {
    @Test
    fun `scope requires positive tenant and clinic ids`() {
        shouldThrow<IllegalArgumentException> { TenantClinicScope(0L, 7L) }
        shouldThrow<IllegalArgumentException> { TenantClinicScope(3L, 0L) }
        TenantClinicScope(3L, 7L) shouldBe TenantClinicScope(3L, 7L)
    }

    @Test
    fun `canonical cache key keeps tuple boundaries`() {
        TenantClinicScope(1L, 23L).cacheKey() shouldBe "1:23"
        TenantClinicScope(12L, 3L).cacheKey() shouldBe "12:3"
    }
}
```

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.model.service.TenantClinicScopeTest"`

Expected: `TenantClinicScope` 또는 `cacheKey()`가 없어 컴파일 실패한다.

- [ ] **Step 3: 최소 value object 구현**

`TenantClinicScope`는 `data class`와 `Serializable`을 사용하고 `init`에서 두 ID에 `require`를 적용한다. `cacheKey()`는 `"${tenantGroupId}:${clinicId}"`만 반환하며 data-class 기본 `toString()`이나 SpEL expression을 key authority로 사용하지 않는다. `SlotQuery`의 `clinicId`를 `scope: TenantClinicScope`로 교체하고 한국어 KDoc에 “인증 객체가 아닌 DB authority”를 명시한다.

- [ ] **Step 4: GREEN 및 compile caller migration**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.model.service.TenantClinicScopeTest" --tests "io.bluetape4k.clinic.appointment.service.SlotCalculationServiceTest"`

Expected: scope invariant/cache collision 테스트가 통과하고, 이후 task에서 이동할 tenantless caller만 남는다.

---

## Task 2: repository predicate와 slot 계산을 tenant-aware로 전환

**Files:**

- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ClinicRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/HolidayRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/DoctorRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentTypeRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/RescheduleCandidateRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/EquipmentUnavailabilityService.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SlotCalculationService.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/*RepositoryTest.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/SlotCalculationServiceTest.kt`

- [ ] **Step 1: cross-tenant repository/slot RED tests 작성**

Add tests that insert tenant A/B rows with the same clinic-local or resource ID shape and assert:

```kotlin
holidayRepository.existsByDate(tenantA.tenantGroupId, date) shouldBe true
holidayRepository.existsByDate(tenantB.tenantGroupId, date) shouldBe false
slotCalculationService.findAvailableSlots(
    SlotQuery(scope = tenantA, doctorId = doctorB, treatmentTypeId = treatmentA, date = date),
) shouldBe emptyList()
```

Add a query-count fixture around `findAvailableSlots` and assert tenant predicates add zero round trips. Add a cache test proving `TenantClinicScope(1, 23)` and `(12, 3)` never share an entry and existing per-cache maximum/TTL remains unchanged.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.HolidayRepositoryTest" --tests "io.bluetape4k.clinic.appointment.service.SlotCalculationServiceTest"`

Expected: same-date tenant isolation or the new `SlotQuery(scope = ...)` calls fail before implementation.

- [ ] **Step 3: repository API and SQL implementation**

Change public methods such as `existsByDate`, `findByDateRange`, clinic operating-hours/closures, doctor schedule/absence, treatment equipment, appointment overlap, and reschedule candidate reads/writes to accept `TenantClinicScope` or a scope plus resource ID. Every SQL predicate must include tenant and clinic where both are authoritative. Keep child ownership checks in the initial transaction guard; do not issue one tenant query per candidate/equipment loop.

Update `SlotCalculationService.findAvailableSlots` to:

```kotlin
transaction {
    val clinic = clinicRepository.findByIdAndTenant(query.scope.clinicId, query.scope.tenantGroupId)
        ?: return@transaction emptyList()
    if (!clinic.openOnHolidays && holidayRepository.existsByDate(query.scope.tenantGroupId, query.date)) {
        return@transaction emptyList()
    }
    // all subsequent repository calls retain query.scope
}
```

Use existing near-cache adapter/factory and explicit `${tenantGroupId}:${clinicId}` keys; do not add a cache namespace or dependency.

- [ ] **Step 4: GREEN and focused verification**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.SlotCalculationServiceTest" --tests "io.bluetape4k.clinic.appointment.repository.HolidayRepositoryTest" --tests "io.bluetape4k.clinic.appointment.repository.AppointmentRepositoryTest"`

Expected: cross-tenant negative tests pass, no slot hot-loop query delta is observed, and existing appointment repository behavior remains green.

---

## Task 3: closure process/stream와 API candidate 경계를 scope화

**Files:**

- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleService.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/SlotController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleBatchStreamController.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleServiceTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SlotControllerTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleControllerTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/RescheduleBatchStreamControllerTest.kt`

- [ ] **Step 1: scope propagation/CAS lifecycle RED tests 작성**

Cover these independently: process ignores another tenant's active appointment; candidate read cannot return an out-of-scope row; confirm/auto reject a candidate whose original or doctor scope mismatches; two streams racing on one appointment produce one candidate set; disconnect/interruption leaves unstarted appointments `ACTIVE`, while committed appointments retain history/candidates; progress callback observes only committed work.

Use a callback/latch test rather than timing sleeps and assert no duplicate candidate rows.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.ClosureRescheduleServiceTest" && ./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.RescheduleBatchStreamControllerTest"`

Expected: old clinic-only signatures or bulk transition semantics fail the new assertions.

- [ ] **Step 3: service/controller implementation**

Replace `clinicId`/tenantGroup-only public entry points with `TenantClinicScope`. In `processClosureReschedule`, guard the clinic once, query active appointments by tuple, and perform each appointment's optimistic CAS, history, slot search, and candidate writes in the same transaction. In `streamClosureReschedule`, capture the scope before starting the virtual thread, track the thread handle, process one appointment per transaction, call progress only after commit, and on emitter completion/error/timeout interrupt the worker. A CAS loser skips without candidate writes; cancellation rolls back the current transaction and leaves unstarted rows `ACTIVE`.

Move `RescheduleController.getCandidates` from raw `RescheduleCandidates.selectAll()` to a tenant-scoped repository/service method. `SlotController` constructs `TenantClinicScope` from `TenantClinicAccessChecker` and passes `SlotQuery(scope = scope, ...)`. Confirm and auto paths validate original appointment and candidate doctor under the same scope before mutation and event enqueue.

- [ ] **Step 4: GREEN and API verification**

Run: `./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.ClosureRescheduleServiceTest" && ./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.SlotControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.RescheduleControllerTest" --tests "io.bluetape4k.clinic.appointment.api.controller.RescheduleBatchStreamControllerTest"`

Expected: wrong-tenant requests produce empty/not-found behavior without mutation, and stream cancellation/concurrency tests pass with bounded lifecycle resources.

---

## Task 4: Solver facts와 snapshot/version 경계를 scope화

**Files:**

- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverService.kt`
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverResult.kt`
- Modify: `appointment-solver/src/main/kotlin/io/bluetape4k/clinic/appointment/solver/converter/SolutionConverter.kt` only if scoped snapshot metadata is required
- Modify: `appointment-solver/src/test/kotlin/io/bluetape4k/clinic/appointment/solver/service/SolverServiceTest.kt`

- [ ] **Step 1: solver scope/snapshot RED tests 작성**

Add a standalone caller test requiring `optimize(scope = TenantClinicScope(...), dateRange = ...)`; fixture facts must contain only that clinic/tenant. Add a version-race test that changes an appointment after the solver snapshot and asserts the returned result cannot be applied as current. Add a query-count assertion around fact loading with tenant delta `0`.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.service.SolverServiceTest"`

Expected: the old `optimize(clinicId, ...)` contract and unscoped `loadSolution` cannot satisfy the tests.

- [ ] **Step 3: scoped solver implementation**

Change `optimize` and `optimizeReschedule` to accept `TenantClinicScope`. Make `loadSolution(scope, dateRange)` perform clinic ownership validation and use scope-aware clinic/doctor/appointment/treatment/equipment/holiday/closure queries. Keep facts and the original appointment map in one Exposed transaction snapshot; run Timefold read-only, retain source versions in `SolverResult`, and require the existing write caller to recheck versions before applying results. Do not create a solver controller or add a tenant query inside the doctor schedule/absence loops.

- [ ] **Step 4: GREEN and solver validation**

Run: `./gradlew :appointment-solver:test --tests "io.bluetape4k.clinic.appointment.solver.service.SolverServiceTest" --tests "io.bluetape4k.clinic.appointment.solver.move.TimeSlotStrengthComparatorTest"`

Expected: scoped facts, version recheck, and query budget are green.

---

## Task 5: legacy Spring event와 event log에 scope를 보존

**Files:**

- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/AppointmentDomainEvent.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/AppointmentEventLogs.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/AppointmentEventLogRecord.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/AppointmentEventLogger.kt`
- Modify: event publishers/callers found by `rg "AppointmentDomainEvent\." appointment-core appointment-api appointment-event appointment-notification`
- Modify: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/EventLogTest.kt` and publisher tests

- [ ] **Step 1: positive scope and best-effort failure RED tests 작성**

Assert every subtype rejects zero tenant/clinic IDs, serializes tenant and clinic in event-log payload, and writes the row with tenant authority. Add a logger failure test proving a log sink failure is recorded/observed without converting an already committed API command into a failed direct delivery path.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.EventLogTest"`

Expected: constructors and event-log table lack the required tenant field.

- [ ] **Step 3: event implementation**

Add required positive `tenantGroupId` and `clinicId` to every `AppointmentDomainEvent` subtype; keep the event explicitly in-process `ApplicationEvent` and do not introduce broker/Java wire serialization. Add nullable `tenantGroupId` to the Exposed event-log table/record for V21 rolling compatibility, but make all new writers reject missing/zero scope. Update logger payload and row insert with both IDs. Treat logger persistence as best-effort audit with bounded reason-code logging/metrics and leave durable delivery/retry to the existing tenant-aware outbox.

- [ ] **Step 4: GREEN and event verification**

Run: `./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.EventLogTest" --tests "io.bluetape4k.clinic.appointment.event.integration.PurchaseEventRedriveServiceTest"`

Expected: all event publishers pass the explicit scope and no zero tenant is synthesized.

---

## Task 6: direct notification claim, permit, route와 worker를 scope화

**Files:**

- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxContracts.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationEventListener.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationDirectOutboxDelivery.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationDeliveryRouteGate.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationProperties.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorkStore.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadiness.kt`
- Modify: notification auto-configuration wiring and all affected tests

- [ ] **Step 1: direct claim and canary RED tests 작성**

Add tests that a claim with tenant A cannot claim a tenant B row, a claimed row is rechecked before worker/provider invocation, zero/mismatch scope produces no claim/permit/provider side effect, and `(1, 23)` differs from `(12, 3)`. Add route tests for `canaryScopes`, deprecated bridge set equality, positive IDs, and startup rejection when the bridge and scope clinic sets differ.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepositoryTest" && ./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationDirectOutboxDeliveryTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationDeliveryRouteGateTest"`

Expected: direct APIs currently accept clinic-only or synthetic tenant `0L` and route properties lack `canaryScopes`.

- [ ] **Step 3: direct delivery implementation**

Make `NotificationDirectDeliveryPort`, `NotificationDirectOutboxStore.claimReady`, listener, worker eligibility, and `claimReadyForDirect` accept the verified tuple/scope. Include tenant and clinic in every SQL predicate and recheck the claimed row against the event scope before invoking a provider. Replace synthetic permit tenant `0L` with the event tenant. Add canonical `NotificationClinicKey`/scope conversion without changing the modern durable outbox envelope. Add nested `canaryScopes[{tenantGroupId, clinicId}]`; during rolling support `canaryClinicIds` only as a deprecated bridge, require equal clinic sets, and use scopes for all new route/DB eligibility decisions.

- [ ] **Step 4: readiness and notification GREEN**

Require Flyway V21, `scheduling_appointment_event_logs.tenant_group_id`, and `idx_notification_outbox_tenant_direct_lookup` in `NotificationSchemaReadiness`. Run:

`./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepositoryTest" --tests "io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxConcurrencyTest"`

`./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationEventListenerTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationDirectOutboxDeliveryTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationDeliveryRouteGateTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationSchemaReadinessTest"`

Expected: mismatch/zero paths have zero provider side effects and concurrent claim remains single-winner.

---

## Task 7: 세 dialect V21 additive migration과 운영 recovery 계약

**Files:**

- Create: `appointment-api/src/main/resources/db/migration/h2/V21__add_tenant_event_log_scope_and_direct_index.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V21__add_tenant_event_log_scope_and_direct_index.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V21__add_tenant_event_log_scope_and_direct_index.sql`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/MultitenancyMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`
- Create: `docs/runbooks/tenant-query-isolation.ko.md`

- [ ] **Step 1: migration contract RED tests 작성**

Assert each dialect reaches V21 with `tenant_group_id` nullable on `scheduling_appointment_event_logs`, existing V1–V20 checksums intact, tenant FK/index present, and the exact direct lookup index columns. Insert an existing event-log row and prove the clinic join backfill resolves its tenant; insert an orphan fixture and assert dispatch/preflight is held. Assert readiness is DOWN until V21, event column, and new index all exist.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.MultitenancyMigrationTest" --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"`

Expected: current maximum version 20 and missing tenant event column/index fail the new assertions.

- [ ] **Step 3: additive SQL and preflight implementation**

Write dialect-specific V21 SQL that adds nullable `tenant_group_id`, backfills using the existing clinic table, adds `ON DELETE RESTRICT` tenant FK/index, and adds `idx_notification_outbox_tenant_direct_lookup` with `(tenant_group_id, clinic_id, appointment_id, event_type, row_kind, status, available_at, next_retry_at, id)` order while retaining old indexes. Do not modify V1–V20, parse/rewrite historical JSON, add NOT NULL, or silently assign a default tenant. Add read-only preflight checks for row count, orphan count, join/update EXPLAIN, and maintenance/dispatch hold.

The runbook must document H2/PostgreSQL/MySQL order, MySQL partial-DDL/schema-history comparison, old-node drain, idempotent post-drain backfill, null-row gauge, `PAUSED` rollback without schema-down, deprecated canary bridge, and a separate later NOT NULL hardening release.

- [ ] **Step 4: dialect GREEN verification**

Run sequentially (never parallel real DB/testcontainer lanes):

`./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.MultitenancyMigrationTest" --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"`

`./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest"`

`./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest"`

Expected: all enabled dialects pass; an unavailable external database is reported with the repository's existing launcher evidence, not hidden by a retry.

---

## Task 8: KDoc, README pair, YAML example와 lesson evidence

**Files:**

- Modify: `appointment-core/README.md`, `appointment-core/README.ko.md`
- Modify: `appointment-event/README.md`, `appointment-event/README.ko.md`
- Modify: `appointment-solver/README.md`, `appointment-solver/README.ko.md`
- Modify: `appointment-notification/README.md`, `appointment-notification/README.ko.md`
- Modify: `appointment-api/README.md`, `appointment-api/README.ko.md`
- Modify: changed public Kotlin declarations for Korean KDoc
- Create when required by final workflow lesson gate: `docs/lessons/2026-08-04-issue-39-tenant-query-isolation.md`

- [ ] **Step 1: document caller contracts**

Keep each English/Korean README pair source-equivalent. Show `SlotQuery(scope = TenantClinicScope(...))`, standalone solver `optimize(scope = ...)`, candidate confirm/auto scope, local-only positive-scope events, `canary-scopes` plus deprecated bridge, bounded SSE cancellation, and V21 pause/rollback. Explain that scope is verified DB authority, not an authentication object. Do not change unrelated README sections.

- [ ] **Step 2: docs validation**

Run `git diff --check` and the repository README parity/diagram validators if the touched files trigger them. Compare headings, code examples, and required named arguments in both locales. Keep public GitHub text in English; keep this plan/spec/runbook/KDoc prose in Korean.

- [ ] **Step 3: lesson gate**

If final workflow evidence identifies reusable tenant-scope or rolling migration learning, commit one concise Korean lesson with context, decision, proof, miss, and future guard. If no reusable lesson is evidenced, record a concrete `N/A` in the final DoD instead of creating filler prose.

---

## Task 9: integrated verification, cleanup scan, and acceptance mapping

**Files:**

- No production ownership; main lane inspects all changed files, generated/untracked files, issue metadata, workflow receipt, and final diff.
- Modify only test/doc artifacts required by a failing acceptance check.

- [ ] **Step 1: affected-module verification**

Run the targeted commands from Tasks 2–7 first, then sequentially run:

`./gradlew :appointment-core:test`

`./gradlew :appointment-event:test`

`./gradlew :appointment-solver:test`

`./gradlew :appointment-notification:test`

`./gradlew :appointment-api:test`

Expected: all affected module tests pass without unexplained warnings/errors. Real DB and migration lanes remain sequential.

- [ ] **Step 2: Kotlin and performance/stability checklist**

Load `bluetape-kotlin-patterns/references/checklist.md` and `bluetape-full-feature/references/performance-stability-scan.md`. Verify no deprecated Exposed imports, no receiver-shadowing bug in inserts/updates, no uncaught `CancellationException`, no monitor use in virtual-thread code, bounded emitter/thread cleanup, provider side-effect guards, cache max/TTL preservation, and query deltas `slot=0`, `solver=0`, `closure<=1`.

- [ ] **Step 3: exact spec-to-plan acceptance map**

Record evidence for each approved spec section: common scope (Task 1), repositories/slot (Task 2), solver (Task 4), closure/SSE (Task 3), event/direct notification (Tasks 5–6), V21/readiness/runbook (Task 7), docs/compatibility (Task 8), and cross-tenant/rollback/query budgets (Tasks 2–7). Any unchecked row remains `PENDING`; do not create the PR until all required rows are PASS and latest review table is `P0=0/P1=0`.

- [ ] **Step 4: final pre-implementation stop condition**

Before production code, commit this plan with the already committed spec, run six independent plan-review lenses plus main integration, repair all P0/P1 findings, and obtain written approval for the reviewed plan. The implementation branch must remain free of production-code changes until that gate is PASS.

---

## Validation and rollback summary

| Risk | Signal | Mitigation | Rerun/rollback point |
|---|---|---|---|
| tenant predicate omitted | cross-tenant negative test or SQL review failure | fix repository predicate and rerun Task 2/affected service tests | stop before Task 3/4 downstream integration |
| slot/solver query amplification | query-count delta exceeds `0/0` | move ownership guard outside loops and reuse scoped queries | revert only the affected task commit; rerun query fixture |
| SSE partial state or duplicate candidate | concurrent/disconnect test failure | per-appointment CAS + atomic transaction + tracked virtual thread interrupt | pause implementation and rerun Task 3 tests before any event work |
| direct provider side effect on mismatch | provider spy receives call | reject before claim/permit/worker and recheck claimed row | keep direct route PAUSED and rerun Tasks 5–6 |
| V21 partial DDL/orphan | preflight/orphan/readiness failure | hold Flyway dispatch; use MySQL schema-history recovery checklist | no schema-down; repair only after explicit evidence |
| rolling writer incompatibility | old-node insert or readiness failure | keep nullable V21, retain old index, defer NOT NULL | rollback application to prior version with route PAUSED |
| README/KDoc drift | parity validator or API compile failure | update both locales and named examples together | block final verification until source-equivalent |

## Definition of Done for the implementation plan

- Every spec acceptance criterion has an ordered task, exact file owner, RED/GREEN command, and fresh evidence target.
- No task depends on a later artifact; cross-module write ownership is disjoint.
- H2/PostgreSQL/MySQL migration, cancellation/concurrency, cache collision, direct-claim side effect, API caller, and query-budget tests are named.
- Public API/KDoc/README and Korean runbook changes are explicit; no new module/dependency, schema rename, composite PK, or #38 JWT scope is introduced.
- Plan review reaches `P0=0, P1=0`, the plan and spec are committed, and only then may Task 4 implementation begin.
