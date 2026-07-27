# Appointment Plan Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 상품 카탈로그의 버전형 예약 projection을 동기화하고, `PurchaseCompleted` event 한 건을 불변 상품 snapshot 기반의 `AppointmentPlan`, `PlannedTreatment`, `TreatmentDependency`로 정확히 전개한다.

**Architecture:** 첫 배포 단위는 방문 예약과 자원 배정을 만들지 않는다. `appointment-core`가 카탈로그와 plan aggregate의 typed contract·검증·Exposed 저장소를 소유하고, `appointment-event`가 inbox/outbox와 구매 event 처리 트랜잭션을 소유하며, `appointment-api`가 catalog sync와 plan 조회 HTTP adapter를 제공한다. 기존 `scheduling_appointments`와 API는 수정하지 않고 additive V8 schema로 병행한다.

**Tech Stack:** Kotlin language/API 2.3, Kotlin Gradle plugin 2.4.0, Java toolchain 21, Spring Boot 4 MVC, Exposed JDBC, Flyway H2/PostgreSQL/MySQL, Jackson 3, JUnit 5, bluetape4k assertions/test helpers. The build files are the version source of truth; the repo-local AGENTS Java 25 description is documentation drift to fix separately, not an implementation prerequisite.

**Source Design:** [`2026-07-26-appointment-plan-and-capacity-design.md`](../specs/2026-07-26-appointment-plan-and-capacity-design.md)

**Visual Companion:** [`2026-07-26-appointment-plan-foundation.html`](./2026-07-26-appointment-plan-foundation.html)

---

## 1. Delivery boundary

### Included

- 버전형 `ProductCatalogProjection`과 BOM item/dependency 저장
- catalog payload의 bounded validation, deterministic hash, DAG cycle 검증
- catalog sync API의 `CREATED`, `UNCHANGED`, `STALE_IGNORED`, `VERSION_CONFLICT`
- `PurchaseCompleted` inbox 중복 방지와 원자적 plan 전개
- `AppointmentPlan`, `PlannedTreatment`, `TreatmentDependency`
- 고객 희망 날짜·범위·요일·시간대 preference snapshot 보존
- `AppointmentPlanCreated` outbox 적재
- tenant/clinic 범위가 강제된 plan 조회 API
- H2, PostgreSQL, MySQL V8 migration과 schema parity 검증
- named feature flag, shadow/dry-run, recovery evidence

### Deferred to the next executable plans

| 후속 계획 | 범위 | 선행 이유 |
|---|---|---|
| 2. Scheduling policy foundation | tenant default, clinic override, effective snapshot, generation | 예약 확정·hold가 정책 없이 임시 규칙을 만들지 않게 함 |
| 3. Visit and commitment | `AppointmentItem`, `ResourceAllocation`, `PROPOSED/HELD/CONFIRMED`, consent | plan과 정책 snapshot을 먼저 참조해야 함 |
| 4. Fulfillment and commerce events | 실제 완료, 부분 완료, attempt 분리, 환불, cross-plan visit | item·allocation lifecycle이 필요함 |
| 5. Disruption recovery | disruption case, proposal, solver partition, notification | 확정 예약과 consent 계약이 필요함 |
| 6. Capacity operations | reliability, reconfirm, overbooking, operating extension, SLA | policy compiler와 item별 자원 배정이 필요함 |
| Event transport completion | broker adapter, signed envelope/mTLS, batch outbox publish, ack-only completion, retry/DLQ | transport 선택과 운영 인프라는 이 foundation의 atomic enqueue 뒤에 연결 |

이 순서는 원 설계의 동작을 바꾸지 않는다. 다만 예약 생성이 `EffectiveSchedulingPolicy`를 필요로 하므로 policy foundation을 visit/commitment보다 먼저 구현한다.

후속 계획의 승인 전 handoff checklist는 다음을 명시적으로 인수한다:
policy 값별 source/generation, consent bridge와 legacy reschedule
`CONSENT_REQUIRED`, partial fulfillment의 완료/잔여/새 attempt 분리, 추가
구매의 새 plan과 cross-plan join proposal/consent, disruption에서 원 확정 예약
보호, 그리고 모든 caller 결과의 stable code/operator action/`retryable`.

## 2. File structure

| Path | Responsibility |
|---|---|
| `appointment-core/.../model/catalog/ProductCatalogDefinition.kt` | catalog version, BOM item, dependency, first-booking rule typed contract |
| `appointment-core/.../model/catalog/CatalogSyncResult.kt` | sync 결과와 conflict 정보 |
| `appointment-core/.../model/plan/AppointmentPlanModel.kt` | plan/treatment/dependency 상태와 aggregate view |
| `appointment-core/.../model/plan/BookingPreferenceSnapshot.kt` | 구매 시 받은 희망 일정 snapshot |
| `appointment-core/.../model/tables/ProductCatalogProjections.kt` | catalog version metadata와 hash |
| `appointment-core/.../model/tables/ProductCatalogBomItems.kt` | projection의 반복·기간·resource demand |
| `appointment-core/.../model/tables/ProductCatalogBomDependencies.kt` | catalog DAG edge |
| `appointment-core/.../model/tables/AppointmentPlans.kt` | 구매별 plan과 catalog/policy 근거 |
| `appointment-core/.../model/tables/PlannedTreatments.kt` | BOM을 횟수만큼 전개한 미래 진료 의무 |
| `appointment-core/.../model/tables/TreatmentDependencies.kt` | 실제 planned treatment 사이 DAG |
| `appointment-core/.../repository/ProductCatalogRepository.kt` | catalog aggregate save/read/version comparison |
| `appointment-core/.../repository/AppointmentPlanRepository.kt` | plan aggregate save/read/source purchase uniqueness |
| `appointment-core/.../service/CatalogDefinitionValidator.kt` | bounded field와 DAG 검증 |
| `appointment-core/.../service/CatalogPayloadHasher.kt` | BOM 순서와 order-insensitive 내부 목록을 구분하는 deterministic SHA-256 |
| `appointment-core/.../service/CatalogSyncApplicationService.kt` | API/향후 consumer가 공유할 sync use case |
| `appointment-core/.../service/AppointmentPlanFactory.kt` | BOM 횟수 전개와 dependency edge materialization |
| `appointment-event/.../event/integration/SchedulingInboxEvents.kt` | consume dedupe/quarantine 상태 |
| `appointment-event/.../event/integration/SchedulingOutboxEvents.kt` | publish 대기 event |
| `appointment-event/.../event/integration/PurchaseCompletedEvent.kt` | versioned 최소 구매 event 계약 |
| `appointment-event/.../event/integration/PurchaseCompletedHandler.kt` | inbox + plan + outbox 원자 트랜잭션 |
| `appointment-api/.../dto/CatalogProductVersionRequest.kt` | catalog sync HTTP validation |
| `appointment-api/.../dto/CatalogSyncResponse.kt` | sync 결과 응답 |
| `appointment-api/.../dto/AppointmentPlanResponse.kt` | plan/treatment/dependency 조회 응답 |
| `appointment-api/.../controller/CatalogProductSyncController.kt` | catalog version PUT adapter |
| `appointment-api/.../controller/AppointmentPlanController.kt` | tenant-scoped plan read adapter |
| `appointment-api/.../config/DatabaseConfig.kt` | dev/test table registration |
| `appointment-api/.../config/ServiceConfig.kt` | repository/application service bean wiring |
| `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V8__add_appointment_plan_foundation.sql` | catalog, plan, inbox/outbox additive schema and indexes |

## 3. Acceptance traceability

| Design acceptance | This plan |
|---|---|
| AC-1 반복 상품 N개 의무 생성 | Task 1, 2, 5 |
| AC-2 패키지와 DAG 표현 | Task 1, 2, 5 |
| AC-3 고객 희망 일정 우선 사용 | preference snapshot만 보존; 실제 후보 계산은 후속 plan 3 |
| AC-15 예약 외 서비스 경계 유지 | Task 5의 inbound application contract와 atomic outbox enqueue까지만 포함; transport publish/ack는 후속 계획 |
| AC-16 duplicate/out-of-order convergence | catalog version 처리와 purchase inbox 범위만 Task 4, 5 |
| AC-21 tenant/clinic 불일치 fail closed | Task 4, 5, 6 |
| AC-23 additive migration 호환 | Task 3 |
| AC-24 SLO·최대 fixture·EXPLAIN·복구 증거 | Task 5 Step 6, Task 6 Step 5, Task 7 Step 6 |
| AC-25~30 scheduling policy | 후속 plan 2 |
| catalog/purchase authority-qualified identity | Task 2, 3, 4, 5의 unique key, lookup, event contract, dialect race test |

---

### Task 1: Lock the catalog and plan contracts

**Complexity:** M

**Depends on:** approved design only

**Write scope:** new pure Kotlin model and validator files plus unit tests

**Required skills:** `bluetape-kotlin-patterns`, `test-driven-development`

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/catalog/ProductCatalogDefinition.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/catalog/CatalogSyncResult.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/plan/AppointmentPlanModel.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/plan/BookingPreferenceSnapshot.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/BookingPreferenceNormalizer.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/CatalogDefinitionValidator.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/CatalogPayloadHasher.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/CatalogDefinitionValidatorTest.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/CatalogPayloadHasherTest.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/BookingPreferenceNormalizerTest.kt`

- [ ] **Step 1: Write RED validation tests**

Pin these cases:

```kotlin
@Test
fun `accepts repeat and package items with an acyclic dependency graph`() {
    val definition = catalogDefinition(
        items = listOf(
            bomItem(id = "laser", repeatCount = 3),
            bomItem(id = "care", repeatCount = 1),
        ),
        dependencies = listOf(dependency("laser", "care")),
    )

    CatalogDefinitionValidator.validate(definition) shouldBeEqualTo definition
}

@Test
fun `rejects dependency cycles and unknown item references`() {
    assertFailsWith<IllegalArgumentException> {
        CatalogDefinitionValidator.validate(
            catalogDefinition(
                items = listOf(bomItem("a"), bomItem("b")),
                dependencies = listOf(dependency("a", "b"), dependency("b", "a")),
            )
        )
    }
}
```

Also assert duplicate `bomItemId`, non-positive repeat count/duration, negative interval, `minimum > preferred`, `preferred > maximum`, invalid initial-booking date range, and dependency occurrence numbers outside the referenced item's `repeatCount`.

Assert `ACTIVE` and `RETIRED` survive deterministic hashing and repository
round-trip, the status participates in the payload hash, and purchase plan
creation rejects a `RETIRED` catalog while leaving the immutable projection
readable.

Pin one central bounds contract in tests: UTF-8 payload ≤ 256 KiB; product/BOM/event/purchase/correlation/producer IDs ≤ 128 characters using the documented safe identifier alphabet; display/treatment names ≤ 256; code and resource strings ≤ 128; at most 200 BOM items, 1,000 explicit catalog dependencies and persisted treatment edges, 100 repeats per item, 2,000 expanded treatments, 2,980 validation-graph edges including implicit repeat ordering, and 64 values in each requirements list; duration ≤ 480 minutes; interval and initial-booking horizon ≤ 3,650 days. Reject blank values, duplicate normalized list entries, control characters, and bound overflow before hashing or persistence. Catalog validation computes the expansion upper bound before accepting a version so purchase handling never discovers an oversized plan after inbox claim.

- [ ] **Step 2: Run RED**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidatorTest"
```

Expected: compilation fails because catalog contracts and validator do not exist.

- [ ] **Step 3: Implement immutable contracts**

Use serializable data classes and explicit enums:

```kotlin
data class ProductCatalogDefinition(
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourceAuthority: String,
    val productId: String,
    val catalogVersion: Long,
    val productName: String,
    val schemaVersion: Int,
    val sourceUpdatedAt: Instant,
    val status: CatalogProjectionStatus,
    val items: List<CatalogBomItem>,
    val dependencies: List<CatalogBomDependency>,
    val initialBookingRule: InitialBookingRule?,
) : Serializable

enum class CatalogProjectionStatus {
    ACTIVE,
    RETIRED,
}

data class CatalogBomItem(
    val bomItemId: String,
    val representativeTreatmentName: String,
    val detailedTreatmentCodes: List<String>,
    val repeatCount: Int,
    val durationMinutes: Int,
    val minimumIntervalDays: Int?,
    val preferredIntervalDays: Int?,
    val maximumIntervalDays: Int?,
    val practitionerQualifications: List<String>,
    val equipmentTypes: List<String>,
    val roomTypes: List<String>,
) : Serializable

data class CatalogBomDependency(
    val predecessorBomItemId: String,
    val predecessorSequenceNo: Int? = null,
    val successorBomItemId: String,
    val successorSequenceNo: Int? = null,
    val minimumIntervalDays: Int,
    val preferredIntervalDays: Int,
    val maximumIntervalDays: Int,
) : Serializable

sealed interface InitialBookingRule : Serializable {
    data class WithinDaysAfterPurchase(val maximumDays: Int) : InitialBookingRule
}
```

Define `AppointmentPlanStatus`, `PlannedTreatmentStatus`, `AppointmentPlanView`, and `PlannedTreatmentView` with English KDoc. `BookingPreferenceSnapshot` is a sealed interface with:

`AppointmentPlanView` and the persistence-facing `AppointmentPlanRecord` both
include:

```kotlin
val tenantGroupId: Long
val clinicId: Long
val catalogProjectionId: Long
val catalogSourceAuthority: String
val sourcePurchaseAuthority: String
val sourcePurchaseId: String
val productId: String
val catalogVersion: Long
val catalogPayloadHash: String
```

The public API response exposes `catalogSourceAuthority` but never patient
ciphertext, fingerprint, raw event data, or signing metadata.

- `ExactDateTime(originalLocalDateTime, originalOffset, zoneId, normalizedInstant)`;
- `DateRange(startDate, endDate, zoneId)`;
- `PreferredWeekdaysAndWindows(weekdays, localTimeWindows, zoneId)`;
- `NotProvided`.

`BookingPreferenceNormalizer` verifies the zone rules: an exact local time in a DST gap is rejected, an overlap requires an explicit valid offset, and the original local/offset plus normalized UTC instant are preserved. Unit tests use fixed zones and fixed instants; no appointment date is calculated here.

- [ ] **Step 4: Implement deterministic validation and hashing**

`CatalogDefinitionValidator` validates known item references and runs Kahn topological sorting. A null predecessor sequence means the predecessor BOM item's last occurrence; a null successor sequence means the successor BOM item's first occurrence. Pairwise or non-boundary mappings require explicit dependency rows with sequence numbers. `CatalogPayloadHasher` preserves item and dependency order because BOM order is semantic, sorts only order-insensitive string lists inside each item, and hashes named fields with explicit null markers. Reuse the repository's existing SHA-256 field-framing pattern; do not hash raw JSON.

- [ ] **Step 5: Run GREEN**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidatorTest" \
  --tests "io.bluetape4k.clinic.appointment.service.CatalogPayloadHasherTest" \
  --tests "io.bluetape4k.clinic.appointment.service.BookingPreferenceNormalizerTest"
```

Expected: PASS and two definitions differing only in list order produce the same hash.

- [ ] **Step 6: Commit**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/catalog \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/plan \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/CatalogDefinitionValidator.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/CatalogPayloadHasher.kt \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service
git commit
```

Commit with the repository Lore protocol.

**Rollback/rerun:** No schema exists yet; revert this commit and rerun the three unit-test classes.

---

### Task 2: Persist immutable catalog and plan aggregates

**Complexity:** L

**Depends on:** Task 1

**Write scope:** `appointment-core` tables, records, repositories, mappers, repository tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProductCatalogProjections.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProductCatalogBomItems.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProductCatalogBomDependencies.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/AppointmentPlans.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/PlannedTreatments.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/TreatmentDependencies.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/ProductCatalogProjectionRecord.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/AppointmentPlanRecord.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/PlannedTreatmentRecord.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ProductCatalogRepository.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentPlanRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/RecordMappers.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/ProductCatalogRepositoryTest.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentPlanRepositoryTest.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`

- [ ] **Step 1: Write RED repository tests**

Across the existing `ENABLE_DIALECTS_METHOD`, prove:

1. `(tenantGroupId, clinicId, sourceAuthority, productId, catalogVersion)` is
   unique, while the same product/version from another authority remains
   independently addressable.
2. a catalog aggregate round-trips every BOM item and dependency.
3. `(tenantGroupId, clinicId, sourcePurchaseAuthority, sourcePurchaseId)` is
   unique. The same authority-local purchase ID may exist in another
   tenant/clinic without collision, while a replay inside the same scope cannot
   move ownership or change immutable payload.
4. plan children preserve copied names, durations, resource requirements, sequence numbers, and dependency intervals.
5. `findByIdAndTenantClinic` and `findBySourcePurchaseAndTenantClinic` return no cross-tenant or cross-clinic data.
6. deleting a catalog projection is restricted after a plan references its version.
7. plan stores only encrypted patient reference ciphertext plus a keyed fingerprint; catalog, inbox, outbox, and query records never store or expose the raw patient reference.

- [ ] **Step 2: Run RED**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.ProductCatalogRepositoryTest" \
  --tests "io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepositoryTest"
```

Expected: missing table/record/repository compilation errors.

- [ ] **Step 3: Implement additive Exposed tables**

Use `LongIdTable`, explicit named indexes, `CurrentTimestamp`, `ReferenceOption.RESTRICT` for immutable catalog/plan ancestry, and `CASCADE` only from plan to its owned children. Store list-valued requirements as canonical JSON text copied from typed input; no query in this phase filters inside those JSON fields.

Required uniqueness and indexes:

```text
uq_catalog_scope_version:
  tenant_group_id, clinic_id, source_authority, product_id, catalog_version
uq_catalog_bom_item:
  catalog_projection_id, bom_item_id
uq_catalog_bom_dependency:
  catalog_projection_id, predecessor_bom_item_id, predecessor_sequence_no,
  successor_bom_item_id, successor_sequence_no
uq_plan_source_purchase:
  tenant_group_id, clinic_id, source_purchase_authority, source_purchase_id
uq_planned_treatment_sequence:
  plan_id, bom_item_id, sequence_no
uq_treatment_dependency:
  predecessor_treatment_id, successor_treatment_id
idx_treatment_dependency_plan:
  plan_id, predecessor_treatment_id, successor_treatment_id
idx_plan_tenant_clinic_status:
  tenant_group_id, clinic_id, status
idx_treatment_plan_status_window:
  plan_id, status, earliest_start_at, latest_start_at
```

Never rename or repurpose `scheduling_appointments`.

Persist a null dependency occurrence as the non-null sentinel `0`; positive values are explicit sequence numbers. This keeps the cross-dialect unique constraint effective because PostgreSQL/MySQL otherwise allow multiple nulls in a unique key. Mappers convert `0 ↔ null` only at the persistence boundary.

`AppointmentPlans` contains `patient_reference_ciphertext`, `patient_reference_key_id`, and `patient_reference_fingerprint`; there is no raw patient reference column. The fingerprint is for equality/ownership checks only and must be a tenant-bound keyed HMAC, never an unsalted hash.

`ProductCatalogProjections` persists `catalog_status` as `ACTIVE` or
`RETIRED`. `AppointmentPlans` also copies `catalog_source_authority` beside
catalog version/hash/name so the purchase snapshot can be audited without
reinterpreting the current catalog.

- [ ] **Step 4: Implement aggregate repositories**

`ProductCatalogRepository.saveAggregate()` inserts projection, BOM items, and
dependency rows inside a caller-owned `transaction {}`. Every latest/exact
catalog lookup requires `sourceAuthority`; omitting it is not a supported
overload. `AppointmentPlanRepository.saveAggregate()` does the same for plan,
treatments, and materialized edges, copies `catalogSourceAuthority`, and uses
the full tenant/clinic/purchase-authority tuple for convergence.
`TreatmentDependencies` carries the owning `plan_id` so plan reads use
`idx_treatment_dependency_plan` rather than an unbounded child-ID `IN` list.
Extract colliding values to locals inside Exposed DSL lambdas and add English
KDoc to public methods.

- [ ] **Step 5: Run GREEN**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.ProductCatalogRepositoryTest" \
  --tests "io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepositoryTest" \
  --tests "io.bluetape4k.clinic.appointment.model.tables.TableSchemaTest"
```

Expected: PASS for enabled H2/PostgreSQL/MySQL repository dialects.

- [ ] **Step 6: Commit**

Commit only Task 2 paths using the Lore protocol.

**Rollback/rerun:** Before V8 deployment this commit is independently revertible. After V8 deployment, disable the feature flag and leave additive tables in place.

---

### Task 3: Make every schema source agree

**Complexity:** M

**Depends on:** Task 2

**Write scope:** Flyway V8, test schema registration, migration tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**Files:**
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingInboxEvents.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingOutboxEvents.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingQuarantineEvents.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingQuarantineAuditEvents.kt`
- Create: `appointment-api/src/main/resources/db/migration/h2/V8__add_appointment_plan_foundation.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V8__add_appointment_plan_foundation.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V8__add_appointment_plan_foundation.sql`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/Containers.kt`

- [ ] **Step 1: Write RED migration assertions**

Assert all six core tables plus inbox/outbox/quarantine/quarantine-audit, named unique constraints, foreign
keys, and range indexes. Assert the catalog unique key includes
`source_authority` and the plan purchase unique key includes
`tenant_group_id, clinic_id`. Seed one legacy `scheduling_appointments` row
before V8 and prove its values and current state remain unchanged after
migration.

Before adding V8 assertions, remove `@Testcontainers`, `@Container`, and direct container ownership from the PostgreSQL/MySQL migration tests. Reuse the repository's bluetape4k singleton fixtures in `Containers.kt`; do not add a new container lifecycle.

- [ ] **Step 2: Run H2 RED**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
```

Expected: V8 table assertions fail.

- [ ] **Step 3: Add V8 SQL and dev/test registration**

Use dialect-native identity syntax already established by V7. Keep table names:

```text
scheduling_product_catalog_projections
scheduling_product_catalog_bom_items
scheduling_product_catalog_bom_dependencies
scheduling_appointment_plans
scheduling_planned_treatments
scheduling_treatment_dependencies
scheduling_inbox_events
scheduling_outbox_events
scheduling_quarantine_events
scheduling_quarantine_audit_events
```

Register the ten Exposed tables in dependency order in `SchemaInitConfig`.
Inbox/outbox/quarantine/audit land in V8 before the handler uses them so a
later task never rewrites an already-applied Flyway checksum.

Pin the inbox convergence columns in all dialects: `event_id`, `event_type`, `producer`, `source_aggregate_id`, `source_aggregate_version`, `tenant_group_id`, `clinic_id`, `payload_hash`, `status`, `replay_after`, `failure_code`, `attempt_count`, `occurred_at`, `received_at`, and `processed_at`. Allowed statuses are `RECEIVED`, `WAITING_GAP`, `PROCESSED`, and `QUARANTINED`. Store no raw external envelope, raw event payload, raw patient reference, name, phone, or treatment detail in inbox/outbox. `AppointmentPlanCreated` uses its own scheduling-owned deterministic `event_id`, stores the inbound `causation_event_id` and preserved `correlation_id`, and carries only tenant/clinic, plan, and authority-qualified source purchase identifiers/version in its versioned payload.

`scheduling_quarantine_events` is separate from inbox/DLQ state and stores
`event_id`, `envelope_hash`, encrypted original envelope, encryption key ID,
producer, source authority, schema/source aggregate version, tenant/clinic
scope, allowlisted reason code, detected time, correlation ID, retention class,
payload expiry, legal-hold flag, and status. Allowed statuses are `OPEN`,
`RELEASE_DENIED`, `RELEASE_APPROVED`, and terminal `PAYLOAD_EXPIRED`. Service
transitions are `OPEN/RELEASE_DENIED -> RELEASE_APPROVED`,
`OPEN/RELEASE_DENIED/RELEASE_APPROVED -> RELEASE_DENIED`,
payload-retained states -> `PAYLOAD_EXPIRED`, and no transition exits
`PAYLOAD_EXPIRED`. Write redrive is not a status transition: it requires
`RELEASE_APPROVED` and appends `REDRIVE` with the recorded release approvals.
It never stores plaintext payload
or key material. `scheduling_quarantine_audit_events` is append-only and stores
quarantine ID, action, privileged actor, reason, dry-run diff hash,
before/after status, approval references, and timestamp. Migration tests reject
an invalid quarantine status, and repository tests prove callers cannot bypass
the service transition preconditions. Tests prove payload
expiry removes ciphertext while preserving hash/reason/audit metadata, and
legal hold blocks expiry.
MySQL uses an `ENUM` for quarantine status and the supported deployment
contract requires strict SQL mode (`STRICT_ALL_TABLES` or stricter); its
migration test enables that session mode before proving invalid-status
rejection. H2/PostgreSQL use named `CHECK` constraints.

Require and assert these queue/read indexes before V8 is fixed:

```text
idx_inbox_status_replay_after_received:
  status, replay_after, received_at
idx_inbox_source_version:
  producer, source_aggregate_id, source_aggregate_version
idx_treatment_dependency_successor:
  successor_treatment_id
idx_outbox_plan_id:
  plan_id
idx_outbox_status_created_at:
  status, created_at
idx_outbox_status_next_attempt:
  status, next_attempt_at
```

If Task 5 later reveals a schema gap, repair Task 3 before the V8 commit. Once V8 has been applied or committed as verified migration history, create a forward-only V9; Task 5 must never rewrite V8.

- [ ] **Step 4: Run migration GREEN sequentially**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest" -Dspring.profiles.active=test,test-mysql
```

Expected: all three PASS. Run container-backed tests sequentially.

- [ ] **Step 5: Commit**

Commit V8 and schema-test parity together.

**Rollback/rerun:** V8 is additive. Rollback disables writes and retains empty/new tables; do not drop plan history after writes begin.

---

### Task 4: Publish the catalog sync API

**Complexity:** L

**Depends on:** Task 1, 2, 3

**Write scope:** catalog application service, API DTO/controller/config, targeted tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `test-driven-development`

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/CatalogSyncApplicationService.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/CatalogSyncApplicationServiceTest.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/CatalogProductVersionRequest.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/CatalogSyncResponse.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingApiErrorResponse.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/CatalogProductSyncController.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/PlanFoundationProperties.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/PlanFoundationPropertiesValidator.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/OutboxTransportCapability.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/CatalogProductSyncControllerTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/CatalogProductSyncSecurityIntegrationTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/PlanFoundationPropertiesValidatorTest.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SchedulingUserPrincipal.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParser.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParserTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/TestJwtProvider.kt`

- [ ] **Step 1: Write RED service tests**

Prove `CREATED`, same-version/same-hash `UNCHANGED`, lower-version
`STALE_IGNORED`, same-version/different-hash `VERSION_CONFLICT`, invalid DAG
rejection, and no partial rows after a failed transaction. A barrier-based race
must also prove that concurrent identical definitions converge to
`CREATED`+`UNCHANGED`, concurrent same-version/different-hash definitions
converge to `CREATED`+`VERSION_CONFLICT`, and neither race exposes a raw
duplicate-key failure or partial children.

- [ ] **Step 2: Run service RED**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.CatalogSyncApplicationServiceTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.CatalogSyncDialectIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.CatalogSyncDialectIntegrationTest" -Dspring.profiles.active=test,test-mysql
```

Run the container-backed dialect race commands sequentially with the shared
singleton launchers; never use `@Testcontainers`.

- [ ] **Step 3: Implement the sync transaction**

Expose one typed entry point shared by the REST adapter and a future Pub/Sub adapter:

```kotlin
class CatalogSyncApplicationService(
    private val repository: ProductCatalogRepository,
) {
    fun synchronize(definition: ProductCatalogDefinition, claimedPayloadHash: String): CatalogSyncResult {
        val valid = CatalogDefinitionValidator.validate(definition)
        val actualHash = CatalogPayloadHasher.hash(valid)
        actualHash.requireEquals(claimedPayloadHash, "payloadHash")
        return transaction {
            repository.resolveSync(valid, actualHash)
        }
    }
}
```

Validation and hashing stay outside the database transaction; only scoped version comparison and persistence hold a connection. The real implementation emits stable operational logs without raw BOM payload or patient data.

- [ ] **Step 4: Write RED controller tests**

For `PUT /api/{tenantCode}/clinics/{clinicId}/catalog-sources/{sourceAuthority}/catalog-products/{productId}/versions/{catalogVersion}`, assert:

- `201` + `CREATED`
- `200` + `UNCHANGED`
- `202` + `STALE_IGNORED`
- `409` + `CATALOG_VERSION_CONFLICT`
- `400` for invalid bounds, hash mismatch, timezone/date input, and DAG cycle
- `404` for a clinic outside the authenticated tenant, matching the existing existence-hiding contract
- `404 FEATURE_DISABLED` while catalog sync is disabled
- path/body tenant, clinic, product, and version mismatch rejection
- sanitized `400/401/403/404/409/413/500` five-field bodies containing only stable error code, safe message, and `correlationId`; never raw exception text, BOM, stored payload/hash, purchase ID, or patient reference

In an `@ActiveProfiles("test", "integration-test")` security integration test, pin no token `401`, wrong tenant `403`, wrong clinic `404`, `PATIENT`/`DOCTOR` `403`, `STAFF` without catalog source authority `403`, and an allowed catalog source authority success.

The JWT contract adds:

```text
scope: space-delimited OAuth-style scopes, including catalog:write
catalogSourceAuthorities: array of exact authority IDs
```

`JwtTokenParser` maps `scope` to `SCOPE_<scope>` authorities and both claims to immutable principal sets. The request body `sourceAuthority` must be present in `catalogSourceAuthorities` and must match the persisted catalog definition; path tenant/clinic/product/version must also match the body.

Caller-facing catalog body contract:

| Field | Shape |
|---|---|
| identity | `sourceAuthority`, `tenantGroupId`, `clinicId`, `productId`, `catalogVersion` |
| version | positive `schemaVersion`, RFC 3339 `sourceUpdatedAt`, `ACTIVE`/`RETIRED` status, lowercase hex `payloadHash` |
| BOM | bounded `items[]`, `dependencies[]`; occurrence sequence is positive or null |
| first booking | `{ "type": "WITHIN_DAYS_AFTER_PURCHASE", "maximumDays": N }` or null |

```json
{
  "sourceAuthority": "product-catalog",
  "tenantGroupId": 10,
  "clinicId": 21,
  "productId": "laser-care",
  "catalogVersion": 7,
  "schemaVersion": 1,
  "sourceUpdatedAt": "2026-07-26T05:00:00Z",
  "status": "ACTIVE",
  "productName": "Laser Care",
  "items": [{
    "bomItemId": "laser",
    "representativeTreatmentName": "Laser",
    "detailedTreatmentCodes": ["LASER"],
    "repeatCount": 3,
    "durationMinutes": 30,
    "minimumIntervalDays": 21,
    "preferredIntervalDays": 28,
    "maximumIntervalDays": 42,
    "practitionerQualifications": ["DERMATOLOGIST"],
    "equipmentTypes": ["LASER_A"],
    "roomTypes": ["PROCEDURE"]
  }],
  "dependencies": [],
  "initialBookingRule": { "type": "WITHIN_DAYS_AFTER_PURCHASE", "maximumDays": 14 },
  "payloadHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

The hash above is illustrative; a real request computes it from the documented typed canonical form. All instants use RFC 3339 offsets and normalize to UTC; clinic-local windows, when introduced, must carry the clinic timezone explicitly. The OpenAPI description links the bounds and canonical-hash rules rather than asking callers to infer sealed-class serialization.

- [ ] **Step 5: Implement and run controller GREEN**

Follow `AppointmentController` conventions: `TenantContext`, `TenantClinicAccessChecker`, Bean Validation, and explicit OpenAPI responses. Add an explicit `HttpMethod.PUT` matcher and require a dedicated `SCOPE_catalog:write` authority plus a principal claim matching the catalog source authority; coarse `ADMIN/STAFF` role alone is insufficient.

New foundation errors use an additive envelope without changing existing `ApiResponse` serialization:

```json
{
  "success": false,
  "data": null,
  "error": "Catalog version conflicts with an existing definition",
  "errorCode": "CATALOG_VERSION_CONFLICT",
  "correlationId": "01J..."
}
```

`SchedulingApiErrorResponse` pins exactly these five fields. Map validation to
`400 VALIDATION_FAILED`, authentication to `401 UNAUTHORIZED`, authorization
or scope denial to `403 FORBIDDEN`, existence-hiding lookup to
`404 RESOURCE_NOT_FOUND`, disabled endpoints to `404 FEATURE_DISABLED`,
catalog conflicts to `409 CATALOG_VERSION_CONFLICT`, pre-deserialization body
overflow to `413 PAYLOAD_TOO_LARGE`, and unexpected failures to
`500 INTERNAL_ERROR`. `error` is a fixed safe message per code; never copy an
exception message. Controller, Spring Security entry point/access-denied
handler, body-size filter, and generated OpenAPI tests assert the same
five-field envelope. Existing APIs continue to use their current `ApiResponse`
until a separate compatibility migration.

Create one typed configuration contract with these deployment controls:

```text
appointment.plan-foundation.catalog-sync-enabled=false
appointment.plan-foundation.plan-read-enabled=false
appointment.plan-foundation.purchase-consumer-mode=OFF  # OFF | SHADOW | WRITE
appointment.plan-foundation.scope-overrides[0].tenant-group-id=1
appointment.plan-foundation.scope-overrides[0].clinic-id=10
appointment.plan-foundation.scope-overrides[0].catalog-sync-enabled=true
appointment.plan-foundation.scope-overrides[0].plan-read-enabled=true
appointment.plan-foundation.scope-overrides[0].purchase-consumer-mode=SHADOW
appointment.plan-foundation.consumer-max-attempts=5
appointment.plan-foundation.consumer-initial-backoff=5s
appointment.plan-foundation.consumer-max-backoff=5m
appointment.plan-foundation.consumer-jitter=0.20
appointment.plan-foundation.event-replay-window=15m
```

Global values are fail-safe defaults. Exact tenant/clinic overrides provide
clinic-scoped canary and rollback without enabling another clinic. Duplicate
or invalid scope overrides fail startup. `PlanFoundationFeatureControlResolverTest`
must prove one enabled scope while same-tenant/other-clinic and
other-tenant/same-clinic-shape remain disabled. Production changes additionally
require an audited provider with actor/reason/previous/new value/expiry/
correlation ID and effective-value readback; the local configuration resolver
does not satisfy that production evidence by itself.

While the effective catalog or plan-read flag is false, the endpoint remains visible in OpenAPI but returns the sanitized `404 FEATURE_DISABLED` contract. Later tasks reuse the same resolver; do not add independent booleans with overlapping meaning. Startup validation rejects production global or scoped `purchase-consumer-mode=WRITE` unless an `OutboxTransportCapability` bean is present; this foundation intentionally provides no such production bean, while tests may supply a fake capability.

Trust verification, source-authority lookup, and redrive deadlines belong to
the future transport/operator adapter that owns cancellation and executor
lifecycle. This foundation exposes no inert timeout properties: the adapter
must add and enforce its deadline configuration in the same executable change.

`PlanFoundationPropertiesValidatorTest` proves production startup fails for
`WRITE` without the capability, succeeds for `OFF/SHADOW`, and permits `WRITE`
only with a test capability. It also rejects zero or negative attempt counts
and replay windows, backoff values where `initial > max`, jitter outside `0.0..1.0`,
and an event replay window that is zero or negative.

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.CatalogProductSyncControllerTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.security.CatalogProductSyncSecurityIntegrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.config.PlanFoundationPropertiesValidatorTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest"
```

- [ ] **Step 6: Commit**

Commit core service and HTTP adapter together because they define one public contract.

**Rollback/rerun:** Disable the catalog sync route; immutable versions already created remain readable.

---

### Task 5: Consume purchase events atomically and create a plan

**Complexity:** L

**Depends on:** Task 1~4

**Write scope:** event envelope, inbox/outbox/quarantine repositories,
quarantine retention service, metrics contract, plan factory, handler, and
tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, `test-driven-development`

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentPlanFactory.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentPlanFactoryTest.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedEvent.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/TrustedSchedulingEventEnvelope.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventTrustVerifier.kt`
- Create: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventTrustVerifierTest.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SourceAggregateVersionVerifier.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SourceAuthorityVersionProof.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PatientReferenceProtector.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedEventAdapter.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedIngress.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedHandler.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseEventRedriveService.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventRepository.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingQuarantineRepository.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/QuarantineRetentionService.kt`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedHandlerTest.kt`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedIngressTest.kt`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseEventRedriveServiceTest.kt`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingQuarantineRepositoryTest.kt`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/QuarantineRetentionServiceTest.kt`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchasePlanMetricsContractTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/PurchaseCompletedDialectIntegrationTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/PurchasePlanPerformanceIntegrationTest.kt`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Write RED factory tests**

For a BOM with three repeated laser items and one dependent care item, assert four `PlannedTreatment` drafts and sequence numbers `1..3` for laser. A dependency with both sequence fields null materializes one edge from laser occurrence 3 to care occurrence 1. A separate fixture with three explicit dependency rows proves pairwise occurrence mapping. Preserve `BookingPreferenceSnapshot` without computing an appointment date.

- [ ] **Step 2: Run factory RED**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.AppointmentPlanFactoryTest"
```

- [ ] **Step 3: Implement deterministic plan expansion**

`AppointmentPlanFactory` copies the catalog source authority, name, codes,
duration, requirements, interval rules, catalog version, and payload hash. The
factory creates no IDs and performs no I/O. Dependency expansion follows the
explicit occurrence contract: null predecessor selects the last occurrence,
null successor selects the first, and all other mappings name concrete
sequence numbers. Ambiguous or out-of-range mappings are rejected before
persistence.

- [ ] **Step 4: Write RED handler tests**

Prove:

1. one valid event creates one plan, its treatments/dependencies, one processed inbox row, and one pending `AppointmentPlanCreated` outbox row with a scheduling-owned event ID, inbound causation ID, preserved correlation ID, and authority-qualified source purchase identity/version;
2. duplicate `eventId` is idempotent;
3. a second event for the same `(tenantGroupId, clinicId,
   sourcePurchaseAuthority, sourcePurchaseId)` does not create another plan or
   change immutable payload, while the same authority-local purchase ID in
   another tenant/clinic creates an independent plan;
4. tenant/clinic mismatch is written to the FK-free terminal rejection store
   before inbox insertion and creates no plan/outbox;
5. unknown or retired catalog version is quarantined;
6. a forced failure after plan insert rolls back inbox, plan children, and outbox together;
7. current and immediately previous event schema versions normalize to the same typed command for one release window;
8. lower/equal aggregate versions converge as stale/idempotent; a verified higher-than-expected version enters `WAITING_GAP` without plan/outbox, increments a bounded attempt count, and becomes `QUARANTINED` after exhaustion;
9. invalid signature, disallowed algorithm, unknown or revoked `kid`, issuer/audience/key pin mismatch, disallowed `PurchaseCompleted` producer, or event outside the replay window is quarantined without plan/outbox; verifier construction also rejects missing/empty producer, key ID, or algorithm allowlists without a permissive fallback;
10. a replay that changes the patient reference for an existing source purchase is quarantined;
11. identifier length/charset, list cardinality, and total serialized payload bounds are enforced before any database write;
12. DB rows, outbox payload, structured logs, metric tags, and quarantine metadata omit patient name, phone, treatment detail, raw payload, and raw patient reference;
13. `SHADOW` evaluates and records a redacted diff but never writes plan/outbox state;
14. barrier-based concurrent races for the same `eventId` and for different event IDs carrying the same source purchase each produce one plan, no orphan outbox, and one classified terminal inbox decision per event;
15. an injected `Clock` makes replay-window checks and `replayAfter` deterministic; attempts use 5-second exponential backoff capped at 5 minutes with 20% jitter and quarantine after attempt 5;
16. external `kid` maps to internal `keyId` exactly once, while logs and outbox expose neither key material nor signatures;
17. a pre-trust or nonexistent-scope rejection writes bounded FK-free terminal
    metadata plus envelope hash only; a trusted in-scope processing quarantine
    atomically writes the inbox terminal state plus an immutable encrypted
    quarantine record with source metadata, reason, correlation, retention
    class, and detected time;
18. retention expiry deletes only encrypted original content and preserves
    hash/reason/audit metadata; a barrier hook after selection proves the final
    conditional update rechecks legal hold/status/payload presence, so a
    concurrent legal hold or earlier expiry wins without a stale audit row;
    `PAYLOAD_EXPIRED` rejects dry-run/write redrive;
19. quarantine inspection and dry-run redrive append privileged audit rows;
    write redrive requires `RELEASE_APPROVED`, exact quarantine/event/source/
    catalog identity, actor/reason, and approval references matching the release
    audit; trust-failed terminal rejections are not releasable, and
    refund/consent/safety release requires two approval references;
20. source-authority timeout and circuit-open outcomes enter `WAITING_GAP` in
    a short transaction without opening the final plan-write transaction,
    record a bounded `replayAfter`, and never write a plan or outbox row;
21. metrics accept only the documented low-cardinality label names and bounded values, reject IDs/tokens/ciphertext/key identifiers as labels, and emit warning/high signals at 80%/95% of the 1,000-series per-metric budget.

- [ ] **Step 5: Implement the minimum event contract**

```kotlin
data class TrustedSchedulingEventEnvelope<T>(
    val eventId: String,
    val eventType: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val producer: String,
    val issuer: String,
    val audience: String,
    val keyId: String,
    val algorithm: String,
    val schemaVersion: Int,
    val correlationId: String,
    val payloadHash: String,
    val payload: T,
) : Serializable

data class PurchaseCompletedEvent(
    val sourceAggregateId: String,
    val sourceAggregateVersion: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val patientReferenceToken: String,
    val catalogSourceAuthority: String,
    val productId: String,
    val catalogVersion: Long,
    val bookingPreference: BookingPreferenceSnapshot,
) : Serializable
```

`PurchaseCompletedIngress.accept(rawEnvelope)` maps the external wire field
`kid` to internal `keyId`, then calls `SchedulingEventTrustVerifier`. The
verifier checks signature or authenticated mTLS metadata,
key/algorithm/issuer/audience, event-type producer allowlist, payload hash, and the
15-minute replay window using an injected `Clock`, then returns
`TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>`. Only that verified
type reaches `PurchaseCompletedHandler`. Its typed configuration requires
nonempty producer, key ID, and algorithm allowlists and fails
construction/startup when any list is missing or empty.
`SchedulingEventTrustVerifierTest` proves empty-list rejection as well as
disallowed algorithm rejection.

No external I/O may execute inside `transaction {}`. Trust verification, optional commerce authority lookup, and patient-reference encryption prepare bounded proofs outside the final transaction. When the local source watermark is insufficient, the source-authority adapter obtains a `SourceAuthorityVersionProof` bound to tenant, clinic, producer, source authority, aggregate ID, version, verification time, and expiry before any write transaction. A timeout or circuit-open result is staged as `WAITING_GAP` in its own short transaction.

`PurchaseCompletedHandler.handle(verifiedEnvelope, versionProof,
protectedPatientReference)` opens the final short transaction, rechecks the
local watermark/proof freshness and full scoped purchase uniqueness, claims the
inbox row, loads the catalog with `(tenantGroupId, clinicId,
catalogSourceAuthority, productId, catalogVersion)`, expands/saves the plan,
and inserts the outbox row atomically. The outbound `AppointmentPlanCreated`
contract creates a deterministic scheduling-owned event ID from event type,
causation event, and plan identity; it never reuses the inbound event ID as the
outbound identity. Persist inbound `causationEventId`, preserved
`correlationId`, source purchase authority/ID/version, plan ID, tenant/clinic,
and schema version. Duplicate/replay converges on one outbox row with the same
outbound event ID, and the payload remains PHI-free. Catch only classified duplicate-key
conflicts; other failures roll back. The commerce producer is authoritative
for the purchase-to-patient relationship, but an existing scoped purchase
fingerprint may never change. Outbox publishing transport is not added in this
plan.

`PurchaseCompletedEventAdapter` accepts only the current and immediately previous schema versions and normalizes both into the typed event above. `SourceAggregateVersionVerifier` combines the producer-qualified local watermark with the already-obtained producer-qualified proof; lower/equal versions converge, while a gap enters `WAITING_GAP` with `replayAfter` and bounded attempts. The external adapter contract owns timeout, bounded retry, jitter, and circuit breaking; this plan adds no external client or new dependency, and production `WRITE` is unavailable without the follow-up transport capability. `PurchaseEventRedriveService` accepts an operator-supplied original envelope plus exact quarantine, event, source purchase, and catalog identity. `dryRun=true` performs trust/scope/version/factory validation without plan writes and appends a redacted diff hash to the quarantine audit. Write redrive additionally requires `RELEASE_APPROVED`, actor/reason, and approval references already recorded by release approval, then appends a `REDRIVE` audit before invoking the atomic handler. Generic replay cannot re-drive a trust-failed terminal rejection. Bounded retry exhaustion marks the inbox row `QUARANTINED` with an allowlisted reason code. This slice does not claim a broker DLQ or outbox delivery completion.

Pre-trust and nonexistent-scope failures use the FK-free terminal rejection
store and retain only bounded metadata plus the envelope hash. Trusted,
in-scope quarantine paths call `SchedulingQuarantineRepository` in the same
transaction as the inbox transition. The repository encrypts the bounded
original envelope before the transaction, persists only ciphertext and
allowlisted metadata, and appends a `DETECTED` audit action. Inspection,
dry-run, redrive, release denial, retention expiry, and legal-hold changes append new
audit rows; prior audit rows are never updated or deleted.

Operational contract:

```text
metrics:
  appointment_catalog_sync_total{result}
  appointment_purchase_plan_handle_total{result,reason}
  appointment_purchase_plan_lag_seconds
  appointment_plan_outbox_pending
  appointment_plan_outbox_oldest_age_seconds
allowed labels:
  result, reason, mode, eventType, producerClass, tenantPartition, clinicPartition
forbidden labels:
  eventId, correlationId, sourcePurchaseId, planId, patientReference, ciphertext, kid/keyId
cardinality:
  warning at 80% of the per-metric 1,000-series budget; high at 95%
logs:
  eventId, correlationId, producer, schemaVersion, sourceAggregateVersion,
  tenantGroupId, clinicId, result, reasonCode, durationMs
forbidden:
  patientReferenceToken, patient name/contact, raw event/BOM, treatment detail
```

Alert evidence uses the design baseline: purchase-to-plan p95 above 30 seconds,
queue/backlog or metric-cardinality budget at 80% warning and 95%
high/backpressure, any trust failure, and any sustained outbox/DLQ backlog.
Security on-call owns trust/signature/scope alerts, the catalog owner owns
catalog version conflicts, scheduling on-call owns backlog/SLO alerts, and
commerce owns purchase source correction. Trust/signature/scope incidents are
critical: security on-call acknowledges within 5 minutes and immediately
blocks the consumer path plus quarantines the event. Backlog/SLO and
cardinality-high alerts use a 15-minute acknowledgement. All alert classes use
30-minute secondary escalation and retain owner handoff evidence. Because this
slice does not publish the outbox, production `WRITE` mode remains prohibited
until the follow-up transport plan supplies batch publish, ack-only completion,
retry/DLQ ownership, and alert wiring.

- [ ] **Step 6: Write and run the bounded performance gate**

`PurchasePlanPerformanceIntegrationTest` reuses only the singleton PostgreSQL
and MySQL launchers and runs sequentially. It seeds a typical four-treatment
plan and the exact maximum 2,000-treatment/1,000-persisted-edge plan, warms the
JVM/database, then measures at least 10 independently committed purchase events
per fixture. It records raw elapsed samples and asserts p95 is below the
30-second purchase-to-plan SLO.

A transaction observer proves no external authority or transport call occurs
inside the final transaction. SQL statement capture classifies and bounds the
inbox claim, authority-qualified catalog lookup, plan insert, treatment batch
insert, dependency batch insert, outbox insert, and terminal inbox update.
Per-treatment or per-edge select N+1 is a failure.

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.PurchasePlanPerformanceIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.PurchasePlanPerformanceIntegrationTest" -Dspring.profiles.active=test,test-mysql
```

Expected: the initial RED run fails on the missing fixture or statement budget.
Optimize persistence with Exposed batch inserts without changing the
transaction boundary, then require both dialects to pass.

- [ ] **Step 7: Run handler, dialect, and unchanged-V8 GREEN**

Task 3 has already created `scheduling_inbox_events` and
`scheduling_outbox_events` with `uq_inbox_event_id` and `uq_outbox_event_id`;
do not edit an already deployed V8 migration in this task.
`AppointmentPlans.uq_plan_source_purchase` covers tenant, clinic, purchase
authority, and purchase ID. In this unreleased branch, Task 3 repairs V8 before
verification; if V8 has ever been applied outside an ephemeral test database,
use a forward-only V9 instead.

Run:

```bash
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedHandlerTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedIngressTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.PurchaseEventRedriveServiceTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineRepositoryTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.QuarantineRetentionServiceTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.PurchasePlanMetricsContractTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest" -Dspring.profiles.active=test,test-mysql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.PurchaseCompletedDialectIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.PurchaseCompletedDialectIntegrationTest" -Dspring.profiles.active=test,test-mysql
```

The dialect integration class reuses `Containers.Postgres`/`Containers.MySql8` and runs sequentially. It covers both duplicate race shapes, rollback injection, stale/gap retry progression, final quarantine, and no external I/O during the atomic plan transaction. Keep it in the existing API database matrix in CI; do not introduce `@Testcontainers`.

- [ ] **Step 8: Commit**

After Task 3 and this task's migration proof pass, commit event convergence
without modifying V8.

**Rollback/rerun:** Stop consumer delivery, retain inbox/outbox and plan history, fix the handler, then re-drive a named `eventId`.

---

### Task 6: Expose a tenant-scoped plan read API

**Complexity:** M

**Depends on:** Task 5

**Write scope:** query service, DTO/controller, integration tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `test-driven-development`

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentPlanQueryService.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/AppointmentPlanResponse.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentPlanController.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentPlanControllerTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentPlanReadSecurityIntegrationTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/AppointmentPlanReadExplainIntegrationTest.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`

- [ ] **Step 1: Write RED API tests**

Assert:

- `GET /api/{tenantCode}/clinics/{clinicId}/appointment-plans/{planId}` returns plan metadata, catalog source authority/version/hash, booking preference, ordered treatments, and dependency edges;
- `GET .../appointment-plans/by-purchase/{sourcePurchaseAuthority}/{sourcePurchaseId}` returns the same view and uses the composite unique index;
- cross-tenant and cross-clinic access returns `404` without revealing existence;
- missing plan returns `404`;
- disabled plan reads return `404 FEATURE_DISABLED` while the OpenAPI operation remains discoverable;
- the foundation read API is clinic-operator-only: `ADMIN/STAFF/DOCTOR` require matching tenant+clinic scope, while `PATIENT` receives `403`;
- response contains no raw inbox/outbox envelope, patient token/ciphertext/fingerprint, or patient contact fields;
- `400/404/500` responses use the sanitized error contract and never expose raw exception messages, purchase IDs, or patient references.

- [ ] **Step 2: Run RED**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentPlanControllerTest"
```

- [ ] **Step 3: Implement query and response mapping**

The query service performs one scoped plan query plus bounded child queries
inside `transaction {}`. The purchase lookup predicate and unique key are both
`(tenantGroupId, clinicId, sourcePurchaseAuthority, sourcePurchaseId)`.
Repository tests prove same-scope uniqueness and cross-scope isolation. Sort
treatments by BOM order and sequence number; query dependencies by `plan_id`
using `idx_treatment_dependency_plan` and sort by predecessor/successor ID.
Controller path tenant/clinic must match the aggregate.

Keep the controller registered for stable OpenAPI discoverability, but short-circuit with `404 FEATURE_DISABLED` while `appointment.plan-foundation.plan-read-enabled=false`. Patient-facing plan reads are explicitly deferred until the principal carries a verified patient subject claim and the repository supports tenant+clinic+patient ownership in one predicate.

- [ ] **Step 4: Run GREEN**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentPlanControllerTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.security.AppointmentPlanReadSecurityIntegrationTest"
```

- [ ] **Step 5: Write RED index-plan assertions, then prove PostgreSQL and MySQL**

`AppointmentPlanReadExplainIntegrationTest` seeds 100,000 plan rows across at
least 20 tenant/clinic partitions, 20 dependency-bearing plans with 2,000
treatments and 1,000 persisted dependencies each, 100,000 inbox rows across
retry states, and 100,000 outbox rows across pending/complete states. Retry and
pending rows use a one-percent distribution so the queue index proof models a
healthy bounded backlog rather than a pathological majority backlog. It
executes and captures `EXPLAIN` for:

1. the authority-qualified scoped purchase lookup;
2. dependency reads by `plan_id`;
3. inbox retry polling by status/replay time/received time;
4. outbox pending polling and oldest-age lookup.

The test fails unless the expected named composite index appears and the
estimated/scanned rows stay within the selected partition or bounded batch.
Sanitized plan text may be attached to the test report; patient fields, raw
events, key material, and signatures are forbidden.

Run the test first with the required index deliberately absent from the
ephemeral fixture and record the expected RED assertion naming that index.
Restore the migration/schema index, run both dialect commands, and require
GREEN. This is a plan-shape proof, not a production latency benchmark.

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.AppointmentPlanReadExplainIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.AppointmentPlanReadExplainIntegrationTest" -Dspring.profiles.active=test,test-mysql
```

- [ ] **Step 6: Commit**

Commit the read API and OpenAPI documentation together.

**Rollback/rerun:** Disable only the read route; no stored data changes.

---

### Task 7: Converge documentation, review, and verification

**Complexity:** M

**Depends on:** Task 1~6

**Write scope:** module docs, plan/spec verification evidence, final scoped fixes

**Required skills:** `bluetape-kotlin-patterns`, `verification-before-completion`, `requesting-code-review`

**Files:**
- Modify: `appointment-core/README.md`
- Modify: `appointment-core/README.ko.md`
- Modify: `appointment-event/README.md`
- Modify: `appointment-event/README.ko.md`
- Modify: `appointment-api/README.md`
- Modify: `appointment-api/README.ko.md`
- Modify: `README.md`
- Modify: `README.ko.md`
- Create: `docs/lessons/2026-07-26-appointment-plan-foundation.md`
- Create: `docs/runbooks/appointment-plan-foundation-recovery.md`
- Create: `docs/verification/2026-07-26-appointment-plan-foundation-release-evidence.md`
- Verify: `docs/superpowers/plans/2026-07-26-appointment-plan-foundation.html`
- Verify: `docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.html`

- [ ] **Step 1: Update public documentation**

Document the plan aggregate boundary, catalog sync endpoint, plan query endpoint, purchase event ownership, inbox/outbox state, and explicit deferred behavior. Keep every touched module README and the root README in English/Korean parity. Do not claim appointment scheduling, hold, consent, or resource allocation is implemented.

The recovery runbook pins bounded retry, quarantine inspection, original-source
version confirmation, dry-run re-drive, exact `eventId` reprocessing,
feature-flag rollback, and immutable history preservation. It assigns security
on-call to trust/signature/scope incidents, the catalog owner to catalog
version conflicts, scheduling on-call to backlog/SLO, and commerce to purchase
source correction. Trust/signature/scope incidents are critical with a
5-minute security-on-call acknowledgement and immediate consumer
block/quarantine action. Backlog/SLO and cardinality-high alerts use a
15-minute acknowledgement; every class uses a 30-minute secondary escalation.

- [ ] **Step 2: Run targeted proof**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidatorTest"
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.CatalogPayloadHasherTest"
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.CatalogSyncApplicationServiceTest"
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.BookingPreferenceNormalizerTest"
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.ProductCatalogRepositoryTest"
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepositoryTest"
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.AppointmentPlanFactoryTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedHandlerTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedIngressTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.PurchaseEventRedriveServiceTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineRepositoryTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.QuarantineRetentionServiceTest"
./gradlew :appointment-event:test --tests "io.bluetape4k.clinic.appointment.event.integration.PurchasePlanMetricsContractTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.CatalogProductSyncControllerTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentPlanControllerTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.security.CatalogProductSyncSecurityIntegrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.security.AppointmentPlanReadSecurityIntegrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.config.PlanFoundationPropertiesValidatorTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.config.PlanFoundationFeatureControlResolverTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest"
```

The two controller tests fetch `/v3/api-docs` and assert the catalog request,
plan response, authority-qualified purchase path, feature-disabled behavior,
and all stable Foundation error responses, including `401`, `403`, and `413`.

- [ ] **Step 3: Run multi-dialect migration proof sequentially**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest" -Dspring.profiles.active=test,test-mysql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.CatalogSyncDialectIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.CatalogSyncDialectIntegrationTest" -Dspring.profiles.active=test,test-mysql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.PurchaseCompletedDialectIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.PurchaseCompletedDialectIntegrationTest" -Dspring.profiles.active=test,test-mysql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.PurchasePlanPerformanceIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.PurchasePlanPerformanceIntegrationTest" -Dspring.profiles.active=test,test-mysql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.AppointmentPlanReadExplainIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.AppointmentPlanReadExplainIntegrationTest" -Dspring.profiles.active=test,test-mysql
```

- [ ] **Step 4: Run module regression**

```bash
./gradlew :appointment-core:test
./gradlew :appointment-event:test
./gradlew :appointment-api:test
./gradlew :appointment-solver:test
./gradlew :appointment-notification:test
./gradlew :appointment-core:build :appointment-event:build :appointment-solver:build \
  :appointment-notification:build :appointment-api:build
./gradlew detekt
actionlint .github/workflows/ci.yml
git diff --check
```

Expected: all PASS. Solver and notification are tested explicitly because both consume shared appointment contracts even though this slice does not modify their scheduling behavior.

- [ ] **Step 5: Run security and stability checks**

Inspect the final diff for:

- tenant/clinic/source purchase ownership on every read and event write;
- duplicate event race and transaction rollback;
- raw payload/PHI logging;
- unbounded list, string, count, duration, and interval input;
- production `!!`, broad exception swallowing, deprecated Exposed imports;
- migration lock/index order and MySQL/PostgreSQL identifier limits;
- stable OpenAPI status/error names.

Record the inspected commit, changed-file list, every command result, and
classified hit in `docs/review/2026-07-27-appointment-plan-workflow-repair.md`.
Use reproducible searches for raw payload/PHI fields, `!!`, broad catch blocks,
deprecated Exposed packages, and identifier/index names; explain every hit
instead of treating search silence as proof. P0/P1 must be zero before
delivery.

- [ ] **Step 6: Rehearse rollout and capture release evidence**

Evidence is split into two non-interchangeable gates. The Foundation local gate
may complete schema, contract, concurrency, security, scoped configuration
resolution, benchmark, and rollback-history proofs. The production `WRITE`
gate remains **BLOCKED** until broker transport/ack/DLQ, real Micrometer alert
wiring, audited feature-control provider/readback, backpressure, owner
acknowledgement, and a production-like rollback rehearsal exist. A blocked
production row is never counted as a completed Foundation check and a completed
Foundation local gate never authorizes production `WRITE`.

Populate the release-evidence document with command, timestamp, commit SHA, result, and artifact link for:

1. schema dry-run on H2/PostgreSQL/MySQL and legacy row count/hash comparison;
2. foundation backfill marked explicitly `N/A` because legacy plan/item projection is deferred until the item model exists;
3. `OFF → SHADOW` consumer diff proving zero writes, then gated `WRITE` proof in test only;
4. current/previous schema replay, duplicate, out-of-order, and aggregate version-gap cases;
5. barrier-based duplicate race proof plus PostgreSQL/MySQL purchase-to-plan benchmarks for a typical plan and the maximum 2,000-treatment/1,000-persisted-edge plan, with p95 below the 30-second SLO before any `WRITE` gate;
6. PostgreSQL/MySQL `EXPLAIN` or equivalent index-usage evidence for scoped purchase lookup, plan dependency read, inbox retry polling, and outbox backlog/oldest-age queries at representative row counts;
7. redacted metric/log capture, metric-label allowlist rejection, 1,000-series cardinality budget, and alert smoke for trust failure, lag, 80% warning, and 95% high/backpressure thresholds;
8. rollback rehearsal disabling one exact tenant/clinic override for catalog
   sync, plan read, and consumer write, proving another clinic's effective value
   is unchanged and generated plan/inbox/outbox history remains intact; the
   production gate additionally requires audited change/readback evidence.
9. generated OpenAPI assertions for exact catalog request, plan response, stable error envelopes, feature-disabled responses, and the authority-qualified purchase lookup path;
10. English/Korean README link and content-parity validation for root and all three touched modules.
11. quarantine inspection, ciphertext retention expiry, legal hold, immutable audit, denied trust-failure release, and dual-approval evidence with security on-call acknowledgement;
12. alert-owner acknowledgement and escalation evidence: critical
    trust/signature/scope incidents prove security-on-call acknowledgement
    within 5 minutes plus consumer block/quarantine; catalog owner handles
    version conflicts; scheduling on-call handles backlog/SLO and
    cardinality-high alerts within 15 minutes; commerce handles purchase
    correction; all classes prove 30-minute secondary escalation;
13. HTML parse/link/browser smoke for the Foundation plan HTML and source design HTML, including internal anchors, relative Markdown links, and desktop/mobile table rendering screenshots.

The production rollout order is additive schema, flags OFF, catalog sync enablement, consumer SHADOW, plan read enablement, and only after the transport follow-up is complete, consumer WRITE. Support current and immediately previous purchase event schema versions for at least one release window. Tenant scope errors, migration count/hash mismatch, version-conflict growth, or SLO/error-budget breach are rollback criteria.

- [ ] **Step 7: Record the lesson and final Lore commit**

The lesson records why plan state is separated from visit state, why policy foundation precedes commitment, and why inbox/plan/outbox share one transaction. Stage only approved foundation files and commit using the Lore protocol.

---

## 4. Risk prediction

| Risk | Signal | Mitigation | Rollback/rerun point |
|---|---|---|---|
| 같은 catalog version의 의미가 달라짐 | same version/different computed hash | immutable unique version + quarantine conflict | Task 4 service test, disable sync route |
| BOM cycle이 plan 생성 뒤 발견됨 | topological sort failure | sync 단계에서 먼저 거부 | Task 1 validator RED/GREEN |
| event duplicate가 같은 scope에 plan을 둘 만듦 | scoped source purchase unique violation | inbox + tenant/clinic-qualified source purchase unique + classified retry | Task 5 concurrency test |
| 다른 catalog authority의 같은 product/version이 충돌 | catalog sync conflict 또는 잘못된 snapshot | authority-qualified unique key와 exact lookup | Task 2 repository test, Task 5 dialect event proof |
| inbox만 기록되고 plan/outbox가 없음 | transaction failure injection | 한 Exposed transaction으로 원자화 | Task 5 rollback test |
| tenant catalog로 다른 clinic plan 생성 | scope mismatch metric/quarantine | handler에서 event/catalog ownership 재검증 | Task 5 negative test |
| V8이 legacy appointment를 손상 | migration row/hash diff | additive tables only, legacy row pre/post assertion | Task 3 migration tests |
| JSON 요구사항 snapshot이 비결정적 | 같은 의미의 내부 set 순서가 달라지거나 BOM 순서가 바뀜 | BOM/dependency 순서는 보존하고 item 내부 set만 정렬하는 canonical encoding과 hash test | Task 1 hasher test |
| 첫 단계가 예약까지 구현했다고 오해 | docs/API capability drift | read API와 README에 `plan only` 명시 | Task 7 docs review |
| outbox enqueue를 전달 완료로 오해 | pending age/backlog 증가 | WRITE 금지 + transport 후속 계획 + ack-only completion 계약 | Task 5/7 evidence |

## 5. Stop condition

이 계획의 구현은 다음 상태에서만 완료다.

1. catalog sync와 purchase event replay가 H2/PostgreSQL/MySQL에서 동일하게 수렴한다.
2. 하나의 tenant/clinic/authority-qualified 구매가 정확히 하나의 plan과
   결정적인 treatment DAG를 만든다.
3. tenant/clinic mismatch가 plan을 만들거나 존재를 노출하지 않는다.
4. 기존 appointment table/state/API 동작이 바뀌지 않는다.
5. plan 조회가 고객 희망 정보와 상품 snapshot 근거를 보여 준다.
6. visit, hold, consent, item fulfillment, refund, disruption, overbooking은 구현되었다고 노출하지 않는다.
7. outbox enqueue는 delivery 완료로 표시되지 않고 production consumer `WRITE`는 transport 후속 계획 전까지 비활성이다.
8. rollout/rollback evidence와 PHI-redacted ops proof가 채워져 있다.
9. 최종 리뷰가 P0=0, P1=0이고 모든 지정 검증이 PASS다.

## 6. Execution handoff

Plan implementation should use `subagent-driven-development` with one fresh implementation worker per task and two-stage review between tasks. Task 3 and Task 5 container-backed DB checks must run sequentially. No PR, push, or merge is authorized by this plan document; those gates require the repository workflow's delivery authority.
