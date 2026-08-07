# Appointment Plan Foundation 구현 계획

> **에이전트 작업자 참고:** 이 계획을 task 단위로 구현하려면 `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans` 하위 스킬을 반드시 사용한다. 단계 추적에는 checkbox(`- [ ]`) 구문을 사용한다.

**목표:** 상품 카탈로그의 버전형 예약 projection을 동기화하고, `PurchaseCompleted` event 한 건을 불변 상품 snapshot 기반의 `AppointmentPlan`, `PlannedTreatment`, `TreatmentDependency`로 정확히 전개한다.

**구조:** 첫 배포 단위는 방문 예약과 자원 배정을 만들지 않는다. `appointment-core`가 카탈로그와 plan aggregate의 typed contract·검증·Exposed 저장소를 소유하고, `appointment-event`가 inbox/outbox와 구매 event 처리 트랜잭션을 소유하며, `appointment-api`가 catalog sync와 plan 조회 HTTP adapter를 제공한다. 기존 `scheduling_appointments`와 API는 수정하지 않고 additive V8 schema로 병행한다.

**기술 스택:** Kotlin language/API 2.3, Kotlin Gradle plugin 2.4.0, Java toolchain 21,
Spring Boot 4 MVC, Exposed JDBC, Flyway H2/PostgreSQL/MySQL, Jackson 3, JUnit 5,
bluetape4k assertions/test helpers. 버전의 source of truth는 build file이다. 저장소
로컬 AGENTS의 Java 25 설명은 별도로 수정할 documentation drift이며 구현 선행
조건이 아니다.

**원본 설계:** [`2026-07-26-appointment-plan-and-capacity-design.md`](../specs/2026-07-26-appointment-plan-and-capacity-design.md)

**시각 동반 문서:** [`2026-07-26-appointment-plan-foundation.html`](./2026-07-26-appointment-plan-foundation.html)

---

## 1. 전달 범위

### 포함 범위

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

### 후속 실행 계획으로 연기한 범위

| 후속 계획 | 범위 | 선행 이유 |
|---|---|---|
| 2. Scheduling policy foundation | tenant default, clinic override, effective snapshot, generation | 예약 확정·hold가 정책 없이 임시 규칙을 만들지 않게 함 |
| 3. Visit 및 commitment | `AppointmentItem`, `ResourceAllocation`, `PROPOSED/HELD/CONFIRMED`, consent | plan과 정책 snapshot을 먼저 참조해야 함 |
| 4. Fulfillment 및 commerce event | 실제 완료, 부분 완료, attempt 분리, 환불, cross-plan visit | item·allocation lifecycle이 필요함 |
| 5. Disruption recovery | disruption case, proposal, solver partition, notification | 확정 예약과 consent 계약이 필요함 |
| 6. Capacity operation | reliability, reconfirm, overbooking, operating extension, SLA | policy compiler와 item별 자원 배정이 필요함 |
| Event transport 완료 | broker adapter, signed envelope/mTLS, batch outbox publish, ack-only completion, retry/DLQ | transport 선택과 운영 인프라는 이 foundation의 atomic enqueue 뒤에 연결 |

이 순서는 원 설계의 동작을 바꾸지 않는다. 다만 예약 생성이 `EffectiveSchedulingPolicy`를 필요로 하므로 policy foundation을 visit/commitment보다 먼저 구현한다.

후속 계획의 승인 전 handoff checklist는 다음을 명시적으로 인수한다:
policy 값별 source/generation, consent bridge와 legacy reschedule
`CONSENT_REQUIRED`, partial fulfillment의 완료/잔여/새 attempt 분리, 추가
구매의 새 plan과 cross-plan join proposal/consent, disruption에서 원 확정 예약
보호, 그리고 모든 caller 결과의 stable code/operator action/`retryable`.

## 2. 파일 구조

| 경로 | 책임 |
|---|---|
| `appointment-core/.../model/catalog/ProductCatalogDefinition.kt` | catalog version, BOM item, dependency, first-booking rule typed contract 정의 |
| `appointment-core/.../model/catalog/CatalogSyncResult.kt` | sync 결과와 conflict 정보 |
| `appointment-core/.../model/plan/AppointmentPlanModel.kt` | plan/treatment/dependency 상태와 aggregate view |
| `appointment-core/.../model/plan/BookingPreferenceSnapshot.kt` | 구매 시 받은 희망 일정 snapshot |
| `appointment-core/.../model/tables/ProductCatalogProjections.kt` | catalog version metadata와 hash 저장 |
| `appointment-core/.../model/tables/ProductCatalogBomItems.kt` | projection의 반복·기간·resource demand 저장 |
| `appointment-core/.../model/tables/ProductCatalogBomDependencies.kt` | catalog DAG edge 저장 |
| `appointment-core/.../model/tables/AppointmentPlans.kt` | 구매별 plan과 catalog/policy 근거 저장 |
| `appointment-core/.../model/tables/PlannedTreatments.kt` | BOM을 횟수만큼 전개한 미래 진료 의무 저장 |
| `appointment-core/.../model/tables/TreatmentDependencies.kt` | 실제 planned treatment 사이 DAG 저장 |
| `appointment-core/.../repository/ProductCatalogRepository.kt` | catalog aggregate 저장·조회·version 비교 |
| `appointment-core/.../repository/AppointmentPlanRepository.kt` | plan aggregate 저장·조회·source purchase uniqueness 처리 |
| `appointment-core/.../service/CatalogDefinitionValidator.kt` | bounded field와 DAG 검증 |
| `appointment-core/.../service/CatalogPayloadHasher.kt` | BOM 순서와 order-insensitive 내부 목록을 구분하는 deterministic SHA-256 계산 |
| `appointment-core/.../service/CatalogSyncApplicationService.kt` | API와 향후 consumer가 공유할 sync use case |
| `appointment-core/.../service/AppointmentPlanFactory.kt` | BOM 횟수 전개와 dependency edge materialization |
| `appointment-event/.../event/integration/SchedulingInboxEvents.kt` | consume dedupe/quarantine 상태 관리 |
| `appointment-event/.../event/integration/SchedulingOutboxEvents.kt` | publish 대기 event 저장 |
| `appointment-event/.../event/integration/PurchaseCompletedEvent.kt` | versioned 최소 구매 event 계약 정의 |
| `appointment-event/.../event/integration/PurchaseCompletedHandler.kt` | inbox + plan + outbox 원자 트랜잭션 처리 |
| `appointment-api/.../dto/CatalogProductVersionRequest.kt` | catalog sync HTTP validation 정의 |
| `appointment-api/.../dto/CatalogSyncResponse.kt` | sync 결과 응답 |
| `appointment-api/.../dto/AppointmentPlanResponse.kt` | plan/treatment/dependency 조회 응답 |
| `appointment-api/.../controller/CatalogProductSyncController.kt` | catalog version PUT adapter |
| `appointment-api/.../controller/AppointmentPlanController.kt` | tenant-scoped plan read adapter |
| `appointment-api/.../config/DatabaseConfig.kt` | dev/test table registration |
| `appointment-api/.../config/ServiceConfig.kt` | repository/application service bean wiring |
| `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V8__add_appointment_plan_foundation.sql` | catalog, plan, inbox/outbox additive schema와 index 정의 |

## 3. Acceptance 추적성

| 설계 acceptance | 이 계획 |
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

### 작업 1: catalog 및 plan contract 고정

**복잡도:** M

**선행 조건:** 승인된 design만 필요

**수정 범위:** 새 pure Kotlin model·validator file 및 unit test

**필요한 스킬:** `bluetape-kotlin-patterns`, `test-driven-development`

**파일:**
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/catalog/ProductCatalogDefinition.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/catalog/CatalogSyncResult.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/plan/AppointmentPlanModel.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/plan/BookingPreferenceSnapshot.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/BookingPreferenceNormalizer.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/CatalogDefinitionValidator.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/CatalogPayloadHasher.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/CatalogDefinitionValidatorTest.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/CatalogPayloadHasherTest.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/BookingPreferenceNormalizerTest.kt`

- [ ] **단계 1: RED validation test 작성**

다음 사례를 고정한다.

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

중복 `bomItemId`, 0 이하인 repeat count/duration, 음수 interval,
`minimum > preferred`, `preferred > maximum`, 잘못된 initial-booking date range,
참조 item의 `repeatCount`를 벗어난 dependency occurrence number도 assertion한다.

`ACTIVE`와 `RETIRED`가 deterministic hash와 repository round-trip에서 보존되고
status가 payload hash에 포함되는지 assertion한다. purchase plan 생성은
`RETIRED` catalog를 거부하되 immutable projection은 계속 읽을 수 있어야 한다.

test에 중앙 bounds contract 하나를 고정한다. UTF-8 payload ≤ 256 KiB,
문서화된 safe identifier alphabet을 사용하는 product/BOM/event/purchase/
correlation/producer ID ≤ 128 characters, display/treatment name ≤ 256, code와
resource string ≤ 128, BOM item 최대 200개, explicit catalog dependency와
persisted treatment edge 최대 1,000개, item별 repeat 최대 100회, expanded
treatment 최대 2,000개, implicit repeat ordering을 포함한 validation-graph edge
최대 2,980개, 각 requirements list의 값 최대 64개를 검증한다. duration ≤ 480
minutes, interval과 initial-booking horizon ≤ 3,650 days도 포함한다. hashing이나
persistence 전에 blank value, 중복 normalized list entry, control character,
bound overflow를 거부한다. Catalog validation은 version을 수락하기 전에
expansion upper bound를 계산하므로 purchase handling이 inbox claim 후 oversized
plan을 발견하지 않는다.

- [ ] **단계 2: RED 실행**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidatorTest"
```

예상 결과: catalog contract와 validator가 없으므로 compilation이 실패한다.

- [ ] **단계 3: immutable contract 구현**

serializable data class와 명시적 enum을 사용한다.

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

`AppointmentPlanStatus`, `PlannedTreatmentStatus`, `AppointmentPlanView`,
`PlannedTreatmentView`를 영문 KDoc과 함께 정의한다. `BookingPreferenceSnapshot`은
다음 sealed interface다.

`AppointmentPlanView`와 persistence-facing `AppointmentPlanRecord`에는 모두
다음 필드가 포함된다.

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

public API response는 `catalogSourceAuthority`를 노출하지만 patient ciphertext,
fingerprint, raw event data, signing metadata는 절대 노출하지 않는다.

- `ExactDateTime(originalLocalDateTime, originalOffset, zoneId, normalizedInstant)`;
- `DateRange(startDate, endDate, zoneId)`;
- `PreferredWeekdaysAndWindows(weekdays, localTimeWindows, zoneId)`;
- `NotProvided`.

`BookingPreferenceNormalizer`는 zone rule을 검증한다. DST gap의 정확한 local time은
거부하고 overlap에는 명시적으로 유효한 offset이 필요하며 original local/offset과
normalized UTC instant를 보존한다. Unit test는 fixed zone과 fixed instant를
사용하며 여기서 appointment date를 계산하지 않는다.

- [ ] **단계 4: deterministic validation 및 hashing 구현**

`CatalogDefinitionValidator`는 알려진 item reference를 검증하고 Kahn topological
sorting을 실행한다. null predecessor sequence는 predecessor BOM item의 마지막
occurrence를 뜻하고 null successor sequence는 successor BOM item의 첫 occurrence를
뜻한다. pairwise 또는 boundary가 아닌 mapping에는 sequence number를 포함한
명시적인 dependency row가 필요하다. `CatalogPayloadHasher`는 BOM order가
semantic이므로 item과 dependency order를 보존하고, 각 item 내부의
order-insensitive string list만 정렬하며, 명시적인 null marker와 함께 named
field를 hash한다. repository의 기존 SHA-256 field-framing pattern을 재사용하고
raw JSON을 hash하지 않는다.

- [ ] **단계 5: GREEN 실행**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidatorTest" \
  --tests "io.bluetape4k.clinic.appointment.service.CatalogPayloadHasherTest" \
  --tests "io.bluetape4k.clinic.appointment.service.BookingPreferenceNormalizerTest"
```

예상 결과: `PASS`이며 list order만 다른 두 definition이 같은 hash를 생성한다.

- [ ] **단계 6: commit**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/catalog \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/plan \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/CatalogDefinitionValidator.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/CatalogPayloadHasher.kt \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service
git commit
```

repository Lore protocol에 따라 commit한다.

**Rollback/rerun:** 아직 schema가 없으므로 이 commit을 revert하고 세 unit-test
class를 다시 실행한다.

---

### 작업 2: immutable catalog 및 plan aggregate 영속화

**복잡도:** L

**선행 조건:** 작업 1

**수정 범위:** `appointment-core` table, record, repository, mapper, repository test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**파일:**
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProductCatalogProjections.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProductCatalogBomItems.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProductCatalogBomDependencies.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/AppointmentPlans.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/PlannedTreatments.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/TreatmentDependencies.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/ProductCatalogProjectionRecord.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/AppointmentPlanRecord.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/PlannedTreatmentRecord.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ProductCatalogRepository.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentPlanRepository.kt`
- 수정: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/RecordMappers.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/ProductCatalogRepositoryTest.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentPlanRepositoryTest.kt`
- 수정: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`

- [ ] **단계 1: RED repository test 작성**

기존 `ENABLE_DIALECTS_METHOD`에서 다음을 증명한다.

1. `(tenantGroupId, clinicId, sourceAuthority, productId, catalogVersion)`가
   unique하고 다른 authority의 같은 product/version은 독립적으로 조회된다.
2. catalog aggregate가 모든 BOM item과 dependency를 round-trip한다.
3. `(tenantGroupId, clinicId, sourcePurchaseAuthority, sourcePurchaseId)`가
   unique하다. 같은 authority-local purchase ID가 다른 tenant/clinic에 충돌
   없이 존재할 수 있고 같은 scope의 replay는 ownership을 옮기거나 immutable
   payload를 바꿀 수 없다.
4. plan child가 복사한 name, duration, resource requirement, sequence number,
   dependency interval을 보존한다.
5. `findByIdAndTenantClinic`과 `findBySourcePurchaseAndTenantClinic`가 다른
   tenant/clinic data를 반환하지 않는다.
6. plan이 version을 참조한 뒤에는 catalog projection 삭제가 제한된다.
7. plan은 encrypted patient reference ciphertext와 keyed fingerprint만
   저장한다. catalog, inbox, outbox, query record는 raw patient reference를
   저장하거나 노출하지 않는다.

- [ ] **단계 2: RED 실행**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.ProductCatalogRepositoryTest" \
  --tests "io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepositoryTest"
```

예상 결과: table/record/repository가 없어 compilation error가 발생한다.

- [ ] **단계 3: additive Exposed table 구현**

`LongIdTable`, 명시적인 named index, `CurrentTimestamp`, immutable catalog/plan
ancestry를 위한 `ReferenceOption.RESTRICT`, plan에서 소유한 child로만 적용되는
`CASCADE`를 사용한다. list-valued requirement는 typed input에서 복사한
canonical JSON text로 저장한다. 이 단계의 query는 JSON field 내부를 filter하지 않는다.

필수 uniqueness 및 index:

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

`scheduling_appointments`의 이름이나 용도를 바꾸지 않는다.

null dependency occurrence는 non-null sentinel `0`으로 영속화하고 양수 값은
명시적인 sequence number로 저장한다. PostgreSQL/MySQL은 unique key에서 여러
null을 허용할 수 있으므로 이 방식으로 cross-dialect unique constraint를
유효하게 유지한다. Mapper는 persistence boundary에서만 `0 ↔ null`을 변환한다.

`AppointmentPlans`에는 `patient_reference_ciphertext`,
`patient_reference_key_id`, `patient_reference_fingerprint`가 있으며 raw
patient reference column은 없다. fingerprint는 equality/ownership check에만
사용하고 tenant-bound keyed HMAC이어야 하며 unsalted hash는 사용하지 않는다.

`ProductCatalogProjections`는 `catalog_status`를 `ACTIVE` 또는 `RETIRED`로
영속화한다. `AppointmentPlans`도 catalog version/hash/name과 함께
`catalog_source_authority`를 복사하므로 현재 catalog를 다시 해석하지 않고
purchase snapshot을 audit할 수 있다.

- [ ] **단계 4: aggregate repository 구현**

`ProductCatalogRepository.saveAggregate()`는 caller-owned `transaction {}` 안에서
projection, BOM item, dependency row를 insert한다. 모든 latest/exact catalog
lookup에는 `sourceAuthority`가 필요하며 이를 생략한 overload는 지원하지 않는다.
`AppointmentPlanRepository.saveAggregate()`도 plan, treatment, materialized edge에
같은 방식을 적용하고 `catalogSourceAuthority`를 복사하며 전체
tenant/clinic/purchase-authority tuple로 convergence를 보장한다.
`TreatmentDependencies`는 소유한 `plan_id`를 가지므로 plan read는 unbounded
child-ID `IN` list 대신 `idx_treatment_dependency_plan`을 사용한다. Exposed DSL
lambda 내부에서 충돌하는 값을 local로 추출하고 public method에 영문 KDoc을 추가한다.

- [ ] **단계 5: GREEN 실행**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.repository.ProductCatalogRepositoryTest" \
  --tests "io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepositoryTest" \
  --tests "io.bluetape4k.clinic.appointment.model.tables.TableSchemaTest"
```

예상 결과: 활성화된 H2/PostgreSQL/MySQL repository dialect에서 `PASS`.

- [ ] **단계 6: commit**

작업 2 경로만 Lore protocol에 따라 commit한다.

**Rollback/rerun:** V8 배포 전에는 이 commit만 독립적으로 revert할 수 있다. V8
배포 후에는 feature flag를 끄고 additive table을 유지한다.

---

### 작업 3: 모든 schema source 일치

**복잡도:** M

**선행 조건:** 작업 2

**수정 범위:** Flyway V8, test schema registration, migration test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**파일:**
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingInboxEvents.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingOutboxEvents.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingQuarantineEvents.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingQuarantineAuditEvents.kt`
- 생성: `appointment-api/src/main/resources/db/migration/h2/V8__add_appointment_plan_foundation.sql`
- 생성: `appointment-api/src/main/resources/db/migration/postgresql/V8__add_appointment_plan_foundation.sql`
- 생성: `appointment-api/src/main/resources/db/migration/mysql/V8__add_appointment_plan_foundation.sql`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/Containers.kt`

- [ ] **단계 1: RED migration assertion 작성**

6개 핵심 table과 inbox/outbox/quarantine/quarantine-audit, named unique
constraint, foreign key, range index를 모두 assertion한다. catalog unique key에
`source_authority`가 포함되고 plan purchase unique key에
`tenant_group_id, clinic_id`가 포함되는지 assertion한다. V8 이전에 legacy
`scheduling_appointments` row 하나를 seed하고 migration 후에도 해당 값과 현재
상태가 바뀌지 않는지 증명한다.

V8 assertion을 추가하기 전에 PostgreSQL/MySQL migration test에서
`@Testcontainers`, `@Container`, 직접적인 container ownership을 제거한다.
repository의 `Containers.kt`에 있는 bluetape4k singleton fixture를 재사용하며 새
container lifecycle은 추가하지 않는다.

- [ ] **단계 2: H2 RED 실행**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
```

예상 결과: V8 table assertion이 실패한다.

- [ ] **단계 3: V8 SQL 및 dev/test registration 추가**

V7에서 이미 정한 dialect-native identity syntax를 사용한다. table 이름은 다음과
같이 유지한다.

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

`SchemaInitConfig`에 10개 Exposed table을 dependency 순서로 등록한다.
handler가 사용하기 전에 inbox/outbox/quarantine/audit를 V8에 포함하므로 후속
작업이 이미 적용된 Flyway checksum을 다시 쓰지 않게 한다.

모든 dialect에서 inbox convergence column을 다음과 같이 고정한다: `event_id`,
`event_type`, `producer`, `source_aggregate_id`, `source_aggregate_version`,
`tenant_group_id`, `clinic_id`, `payload_hash`, `status`, `replay_after`,
`failure_code`, `attempt_count`, `occurred_at`, `received_at`, `processed_at`.
허용 상태는 `RECEIVED`, `WAITING_GAP`, `PROCESSED`, `QUARANTINED`다.
inbox/outbox에는 외부 envelope 원문, event payload 원문, raw patient reference,
이름, 전화번호, treatment detail을 저장하지 않는다. `AppointmentPlanCreated`는
자체 scheduling-owned deterministic `event_id`를 사용하고 inbound
`causation_event_id`와 보존한 `correlation_id`를 저장하며, versioned payload에는
tenant/clinic, plan, authority-qualified source purchase 식별자와 version만
담는다.

`scheduling_quarantine_events`는 inbox/DLQ 상태와 분리하며 다음을 저장한다:
`event_id`, `envelope_hash`, 암호화한 원본 envelope, encryption key ID, producer,
source authority, schema/source aggregate version, tenant/clinic 범위,
allowlist된 reason code, 감지 시각, correlation ID, retention class, payload
만료 시각, legal-hold flag, status. 허용 상태는 `OPEN`, `RELEASE_DENIED`,
`RELEASE_APPROVED`, terminal 상태인 `PAYLOAD_EXPIRED`다. service transition은
`OPEN/RELEASE_DENIED -> RELEASE_APPROVED`,
`OPEN/RELEASE_DENIED/RELEASE_APPROVED -> RELEASE_DENIED`, payload가 보존된
상태에서 `PAYLOAD_EXPIRED`로 이동하며, `PAYLOAD_EXPIRED`에서 나가는 transition은
없다. write redrive는 status transition이 아니다. `RELEASE_APPROVED`가 필요하고
기록된 release approval과 함께 `REDRIVE`를 추가한다. 평문 payload나 key material은
절대 저장하지 않는다. `scheduling_quarantine_audit_events`는 append-only이며
quarantine ID, action, privileged actor, reason, dry-run diff hash, 이전/이후
status, approval reference, timestamp를 저장한다. migration test는 잘못된
quarantine status를 거부하고 repository test는 caller가 service transition
precondition을 우회할 수 없음을 증명한다. payload 만료 시 ciphertext만
삭제하고 hash/reason/audit metadata는 보존하는지, legal hold가 만료를 막는지도
test한다.
MySQL은 quarantine status에 `ENUM`을 사용하고 지원되는 deployment contract는
strict SQL mode(`STRICT_ALL_TABLES` 이상)를 요구한다. migration test는 잘못된
status 거부를 증명하기 전에 해당 session mode를 활성화한다. H2/PostgreSQL은
named `CHECK` constraint를 사용한다.

V8을 확정하기 전에 다음 queue/read index를 요구하고 assertion한다.

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

후속 작업 5에서 schema gap이 드러나면 V8 commit 전에 작업 3을 수정한다. V8이
적용되었거나 검증된 migration history로 commit된 뒤에는 forward-only V9를
생성하며 작업 5는 V8을 다시 쓰지 않는다.

- [ ] **단계 4: migration GREEN을 순차 실행**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest" -Dspring.profiles.active=test,test-mysql
```

예상 결과: 세 test가 모두 `PASS`한다. container-backed test는 순차 실행한다.

- [ ] **단계 5: commit**

V8과 schema-test parity를 함께 commit한다.

**Rollback/rerun:** V8은 additive다. rollback 시 write를 비활성화하고 비어 있는
새 table은 유지한다. write가 시작된 뒤에는 plan history를 삭제하지 않는다.

---

### 작업 4: catalog sync API 공개

**복잡도:** L

**선행 조건:** 작업 1, 2, 3

**수정 범위:** catalog application service, API DTO/controller/config, targeted test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `test-driven-development`

**파일:**
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/CatalogSyncApplicationService.kt`
- 생성: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/CatalogSyncApplicationServiceTest.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/CatalogProductVersionRequest.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/CatalogSyncResponse.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingApiErrorResponse.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/CatalogProductSyncController.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/PlanFoundationProperties.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/PlanFoundationPropertiesValidator.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/OutboxTransportCapability.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/CatalogProductSyncControllerTest.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/CatalogProductSyncSecurityIntegrationTest.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/PlanFoundationPropertiesValidatorTest.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SchedulingUserPrincipal.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParser.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParserTest.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/TestJwtProvider.kt`

- [ ] **단계 1: RED service test 작성**

`CREATED`, same-version/same-hash `UNCHANGED`, lower-version
`STALE_IGNORED`, same-version/different-hash `VERSION_CONFLICT`, invalid DAG
거부, 실패한 transaction 뒤 partial row가 남지 않음을 증명한다. barrier 기반
race에서는 동시 동일 definition이 `CREATED`+`UNCHANGED`로 수렴하고,
동시 same-version/different-hash definition이 `CREATED`+`VERSION_CONFLICT`로
수렴하며, 어느 race에서도 raw duplicate-key failure나 partial child가
노출되지 않음을 추가로 증명한다.

- [ ] **단계 2: service RED 실행**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.CatalogSyncApplicationServiceTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.CatalogSyncDialectIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.CatalogSyncDialectIntegrationTest" -Dspring.profiles.active=test,test-mysql
```

container-backed dialect race command는 공유 singleton launcher로 순차 실행하며
`@Testcontainers`는 사용하지 않는다.

- [ ] **단계 3: sync transaction 구현**

REST adapter와 향후 Pub/Sub adapter가 공유하는 typed entry point 하나를 공개한다.

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

validation과 hashing은 database transaction 밖에서 수행하고, 범위가 지정된
version 비교와 영속화만 connection을 점유한다. 실제 구현은 raw BOM payload나
환자 데이터를 포함하지 않는 stable operational log를 남긴다.

- [ ] **단계 4: RED controller test 작성**

`PUT /api/{tenantCode}/clinics/{clinicId}/catalog-sources/{sourceAuthority}/catalog-products/{productId}/versions/{catalogVersion}`에 대해 다음을 assertion한다.

- `201` + `CREATED`
- `200` + `UNCHANGED`
- `202` + `STALE_IGNORED`
- `409` + `CATALOG_VERSION_CONFLICT`
- `400` for invalid bounds, hash mismatch, timezone/date input, and DAG cycle
- `404` for a clinic outside the authenticated tenant, matching the existing existence-hiding contract
- `404 FEATURE_DISABLED` while catalog sync is disabled
- path/body tenant, clinic, product, and version mismatch rejection
- sanitized `400/401/403/404/409/413/500` five-field bodies containing only stable error code, safe message, and `correlationId`; never raw exception text, BOM, stored payload/hash, purchase ID, or patient reference

`@ActiveProfiles("test", "integration-test")` security integration test에서 token
없음은 `401`, 잘못된 tenant는 `403`, 잘못된 clinic은 `404`, `PATIENT`/`DOCTOR`는
`403`, catalog source authority가 없는 `STAFF`는 `403`, 허용된 catalog source
authority는 성공으로 고정한다.

JWT contract에 다음을 추가한다.

```text
scope: space-delimited OAuth-style scopes, including catalog:write
catalogSourceAuthorities: array of exact authority IDs
```

`JwtTokenParser`는 `scope`를 `SCOPE_<scope>` authority로 매핑하고 두 claim을
immutable principal set으로 매핑한다. request body의 `sourceAuthority`는
`catalogSourceAuthorities`에 있어야 하며 영속화한 catalog definition과 일치해야
한다. path의 tenant/clinic/product/version도 body와 일치해야 한다.

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

위 hash는 예시일 뿐이며 실제 request는 문서화된 typed canonical form에서 이를
계산한다. 모든 instant는 RFC 3339 offset을 사용하고 UTC로 normalize한다.
clinic-local window를 도입할 때는 clinic timezone을 명시해야 한다. OpenAPI
description은 caller가 sealed-class serialization을 추론하게 하지 않고 bounds와
canonical-hash 규칙을 연결한다.

- [ ] **단계 5: controller GREEN 구현 및 실행**

`AppointmentController` convention인 `TenantContext`,
`TenantClinicAccessChecker`, Bean Validation, explicit OpenAPI response를 따른다.
명시적인 `HttpMethod.PUT` matcher를 추가하고 전용 `SCOPE_catalog:write`
authority와 catalog source authority에 일치하는 principal claim을 요구한다.
일반적인 `ADMIN/STAFF` role만으로는 충분하지 않다.

새 foundation error는 기존 `ApiResponse` serialization을 변경하지 않는 additive
envelope를 사용한다.

```json
{
  "success": false,
  "data": null,
  "error": "Catalog version conflicts with an existing definition",
  "errorCode": "CATALOG_VERSION_CONFLICT",
  "correlationId": "01J..."
}
```

`SchedulingApiErrorResponse`는 정확히 이 5개 field를 고정한다. validation은
`400 VALIDATION_FAILED`, authentication은 `401 UNAUTHORIZED`, authorization 또는
scope denial은 `403 FORBIDDEN`, 존재를 숨기는 lookup은 `404 RESOURCE_NOT_FOUND`,
비활성 endpoint는 `404 FEATURE_DISABLED`, catalog conflict는
`409 CATALOG_VERSION_CONFLICT`, deserialization 이전 body overflow는
`413 PAYLOAD_TOO_LARGE`, 예상하지 못한 failure는 `500 INTERNAL_ERROR`로
매핑한다. `error`는 code별로 고정된 안전한 message를 사용하며 exception
message를 복사하지 않는다. Controller, Spring Security entry point/access-denied
handler, body-size filter, 생성된 OpenAPI test가 같은 5-field envelope를
assertion한다. 별도 compatibility migration 전까지 기존 API는 현재의
`ApiResponse`를 계속 사용한다.

다음 deployment control을 포함하는 typed configuration contract 하나를 만든다.

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

Global value는 fail-safe default다. 정확한 tenant/clinic override로 다른 clinic을
활성화하지 않고 clinic 범위 canary와 rollback을 수행한다. 중복되거나 잘못된
scope override는 startup에서 실패한다. `PlanFoundationFeatureControlResolverTest`는
한 scope만 활성화되고 same-tenant/other-clinic 및 other-tenant/same-clinic-shape은
비활성으로 남는지 증명해야 한다. Production 변경에는 actor/reason/previous/new
value/expiry/correlation ID와 effective-value readback을 포함하는 audited provider가
추가로 필요하다. local configuration resolver만으로는 production evidence를
충족하지 못한다.

effective catalog 또는 plan-read flag가 false인 동안에도 endpoint는 OpenAPI에
표시되지만 정제된 `404 FEATURE_DISABLED` contract를 반환한다. 후속 작업은 같은
resolver를 재사용하며 의미가 겹치는 독립 boolean을 추가하지 않는다. startup
validation은 `OutboxTransportCapability` bean이 없으면 production global 또는
scoped `purchase-consumer-mode=WRITE`를 거부한다. 이 foundation에는 의도적으로
그런 production bean이 없으며 test에서만 fake capability를 제공할 수 있다.

trust verification, source-authority lookup, redrive deadline은 cancellation과
executor lifecycle을 소유하는 향후 transport/operator adapter의 책임이다. 이
foundation은 동작하지 않는 timeout property를 노출하지 않는다. adapter가 같은
실행 가능한 변경에서 deadline configuration을 추가하고 강제해야 한다.

`PlanFoundationPropertiesValidatorTest`는 capability 없이 `WRITE`인 production
startup이 실패하고, `OFF/SHADOW`에서는 성공하며, test capability가 있을 때만
`WRITE`를 허용하는지 증명한다. 또한 0 또는 음수 attempt count/replay window,
`initial > max`인 backoff, `0.0..1.0` 밖의 jitter, 0 또는 음수인 event replay
window를 거부한다.

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.CatalogProductSyncControllerTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.security.CatalogProductSyncSecurityIntegrationTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.config.PlanFoundationPropertiesValidatorTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest"
```

- [ ] **단계 6: commit**

core service와 HTTP adapter가 하나의 public contract를 정의하므로 함께 commit한다.

**Rollback/rerun:** catalog sync route를 비활성화한다. 이미 생성한 immutable
version은 계속 읽을 수 있다.

---

### 작업 5: purchase event를 원자적으로 소비해 plan 생성

**복잡도:** L

**선행 조건:** 작업 1~4

**수정 범위:** event envelope, inbox/outbox/quarantine repository,
quarantine retention service, metrics contract, plan factory, handler 및 test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, `test-driven-development`

**파일:**
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentPlanFactory.kt`
- 생성: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentPlanFactoryTest.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedEvent.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/TrustedSchedulingEventEnvelope.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventTrustVerifier.kt`
- 생성: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventTrustVerifierTest.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SourceAggregateVersionVerifier.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SourceAuthorityVersionProof.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PatientReferenceProtector.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedEventAdapter.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedIngress.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedHandler.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseEventRedriveService.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventRepository.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingQuarantineRepository.kt`
- 생성: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/QuarantineRetentionService.kt`
- 테스트: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedHandlerTest.kt`
- 테스트: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedIngressTest.kt`
- 테스트: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseEventRedriveServiceTest.kt`
- 테스트: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingQuarantineRepositoryTest.kt`
- 테스트: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/QuarantineRetentionServiceTest.kt`
- 테스트: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchasePlanMetricsContractTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/PurchaseCompletedDialectIntegrationTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/PurchasePlanPerformanceIntegrationTest.kt`
- 수정: `.github/workflows/ci.yml`

- [ ] **단계 1: RED factory test 작성**

laser item 3개와 dependent care item 1개가 있는 BOM에서 `PlannedTreatment`
draft 4개와 laser의 sequence number `1..3`을 assertion한다. 두 sequence field가
모두 null인 dependency는 laser occurrence 3에서 care occurrence 1로 이어지는
edge 하나를 materialize해야 한다. 명시적인 dependency row 3개를 둔 별도 fixture로
pairwise occurrence mapping을 증명한다. `BookingPreferenceSnapshot`은 보존하되
appointment date를 계산하지 않는다.

- [ ] **단계 2: factory RED 실행**

```bash
./gradlew :appointment-core:test --tests "io.bluetape4k.clinic.appointment.service.AppointmentPlanFactoryTest"
```

- [ ] **단계 3: deterministic plan expansion 구현**

`AppointmentPlanFactory`는 catalog source authority, name, code, duration,
requirement, interval rule, catalog version, payload hash를 복사한다. factory는
ID를 생성하지 않고 I/O도 수행하지 않는다. dependency expansion은 명시적인
occurrence contract를 따른다. null predecessor는 마지막 occurrence를 선택하고,
null successor는 첫 occurrence를 선택하며, 그 외 mapping은 구체적인 sequence
number를 지정한다. 모호하거나 범위를 벗어난 mapping은 persistence 전에
거부한다.

- [ ] **단계 4: RED handler test 작성**

다음을 증명한다.

1. 유효한 event 하나가 plan 하나, treatment/dependency, processed inbox row 하나,
   scheduling-owned event ID·inbound causation ID·보존한 correlation ID·authority-qualified
   source purchase identity/version을 가진 pending `AppointmentPlanCreated` outbox
   row 하나를 생성한다.
2. duplicate `eventId`는 idempotent하게 처리된다.
3. 같은 `(tenantGroupId, clinicId,
   sourcePurchaseAuthority, sourcePurchaseId)`에 대한 두 번째 event는 또 다른
   plan을 만들거나 immutable payload를 변경하지 않는다. 다른
   tenant/clinic에서 같은 authority-local purchase ID를 사용하면 독립적인 plan을
   생성한다.
4. tenant/clinic mismatch는 inbox insertion 전에 FK-free terminal rejection
   store에 기록되며 plan/outbox를 생성하지 않는다.
5. unknown 또는 retired catalog version은 quarantine된다.
6. plan insert 뒤 강제로 failure를 발생시키면 inbox, plan child, outbox가 함께
   rollback된다.
7. 현재 event schema version과 바로 이전 version이 한 release window 동안 같은
   typed command로 normalize된다.
8. lower/equal aggregate version은 stale/idempotent로 수렴한다. 검증된
   higher-than-expected version은 plan/outbox 없이 `WAITING_GAP`에 들어가고 bounded
   attempt count를 증가시키며, 소진 후 `QUARANTINED`가 된다.
9. invalid signature, disallowed algorithm, unknown 또는 revoked `kid`,
   issuer/audience/key pin mismatch, 허용되지 않은 `PurchaseCompleted` producer,
   replay window 밖의 event는 plan/outbox 없이 quarantine된다. verifier 생성도
   permissive fallback 없이 누락되거나 빈 producer, key ID, algorithm allowlist를
   거부한다.
10. 기존 source purchase의 patient reference를 바꾸는 replay는 quarantine된다.
11. identifier length/charset, list cardinality, 전체 serialized payload bound는
    database write 전에 강제된다.
12. DB row, outbox payload, structured log, metric tag, quarantine metadata에는
    patient name, phone, treatment detail, raw payload, raw patient reference가
    포함되지 않는다.
13. `SHADOW`는 redacted diff를 평가·기록하지만 plan/outbox 상태를 write하지 않는다.
14. 같은 `eventId`에 대한 barrier 기반 동시 race와 같은 source purchase를 담은
    서로 다른 event ID race는 각각 plan 하나, orphan outbox 없음, event별로 분류된
    terminal inbox decision 하나를 만든다.
15. 주입한 `Clock`으로 replay-window check와 `replayAfter`를 deterministic하게
    만들며, attempt는 5초 exponential backoff를 사용하고 5분으로 제한하며 20%
    jitter를 적용하고 attempt 5 뒤 quarantine한다.
16. external `kid`는 internal `keyId`에 정확히 한 번 매핑되며 log와 outbox에는
    key material이나 signature를 노출하지 않는다.
17. pre-trust 또는 nonexistent-scope rejection은 bounded FK-free terminal
    metadata와 envelope hash만 기록한다. trusted in-scope processing quarantine은
    inbox terminal state와 source metadata, reason, correlation, retention class,
    detected time을 가진 immutable encrypted quarantine record를 원자적으로
    기록한다.
18. retention expiry는 encrypted original content만 삭제하고 hash/reason/audit
    metadata는 보존한다. selection 뒤 barrier hook으로 최종 conditional update가
    legal hold/status/payload 존재 여부를 다시 확인함을 증명한다. 따라서 동시
    legal hold 또는 먼저 발생한 expiry가 stale audit row 없이 승리한다.
    `PAYLOAD_EXPIRED`는 dry-run/write redrive를 거부한다.
19. quarantine inspection과 dry-run redrive는 privileged audit row를 추가한다.
    write redrive에는 `RELEASE_APPROVED`, 정확한 quarantine/event/source/catalog
    identity, actor/reason, release audit와 일치하는 approval reference가 필요하다.
    trust-failed terminal rejection은 release할 수 없고 refund/consent/safety
    release에는 approval reference 2개가 필요하다.
20. source-authority timeout과 circuit-open 결과는 최종 plan-write transaction을
    열지 않고 짧은 transaction에서 `WAITING_GAP`으로 전환된다. bounded
    `replayAfter`를 기록하며 plan/outbox row를 절대 쓰지 않는다.
21. metric은 문서화한 low-cardinality label name과 bounded value만 허용하고,
    ID/token/ciphertext/key identifier를 label로 거부하며 metric별 1,000-series
    budget의 80%/95%에서 warning/high signal을 발생시킨다.

- [ ] **단계 5: 최소 event contract 구현**

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

`PurchaseCompletedIngress.accept(rawEnvelope)`는 external wire field인
`kid`를 internal `keyId`로 매핑한 다음 `SchedulingEventTrustVerifier`를 호출한다.
verifier는 signature 또는 authenticated mTLS metadata, key/algorithm/issuer/audience,
event-type producer allowlist, payload hash를 검사하고 주입된 `Clock`으로
15분 replay window를 검증한 뒤 `TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>`를
반환한다. 검증된 type만 `PurchaseCompletedHandler`에 도달한다. typed
configuration은 비어 있지 않은 producer, key ID, algorithm allowlist를 요구하며
어느 list든 누락되거나 비어 있으면 construction/startup이 실패한다.
`SchedulingEventTrustVerifierTest`는 빈 list 및 허용되지 않은 algorithm 거부를
증명한다.

`transaction {}` 내부에서는 external I/O를 실행하지 않는다. trust verification,
선택적인 commerce authority lookup, patient-reference encryption은 최종 transaction
밖에서 bounded proof를 준비한다. local source watermark가 충분하지 않으면
source-authority adapter가 write transaction 전에 tenant, clinic, producer, source
authority, aggregate ID, version, verification time, expiry에 bound된
`SourceAuthorityVersionProof`를 얻는다. timeout 또는 circuit-open 결과는 별도의
짧은 transaction에서 `WAITING_GAP`으로 stage한다.

`PurchaseCompletedHandler.handle(verifiedEnvelope, versionProof,
protectedPatientReference)`는 최종 short transaction을 열고 local watermark/proof
freshness와 전체 scoped purchase uniqueness를 다시 확인한다. 이후 inbox row를
claim하고 `(tenantGroupId, clinicId, catalogSourceAuthority, productId,
catalogVersion)`로 catalog를 조회하며 plan을 expand/save하고 outbox row를 원자적으로
insert한다. outbound `AppointmentPlanCreated` contract는 event type, causation
event, plan identity로 deterministic scheduling-owned event ID를 생성하며 inbound
event ID를 outbound identity로 재사용하지 않는다. inbound `causationEventId`,
보존한 `correlationId`, source purchase authority/ID/version, plan ID, tenant/clinic,
schema version을 저장한다. duplicate/replay은 같은 outbound event ID를 가진
outbox row 하나로 수렴하고 payload는 PHI-free로 유지한다. 분류된 duplicate-key
conflict만 catch하며 다른 failure는 rollback한다. commerce producer가
purchase-to-patient 관계의 authoritative source지만 기존 scoped purchase
fingerprint는 변경할 수 없다. 이 계획에는 outbox publishing transport를 추가하지
않는다.

`PurchaseCompletedEventAdapter`는 현재와 바로 이전 schema version만 허용하고
둘 다 위 typed event로 normalize한다. `SourceAggregateVersionVerifier`는
producer-qualified local watermark와 이미 확보한 producer-qualified proof를
결합한다. lower/equal version은 수렴하고 gap은 `replayAfter`와 bounded attempt를
가진 `WAITING_GAP`으로 들어간다. external adapter contract가 timeout, bounded
retry, jitter, circuit breaking을 소유한다. 이 계획은 external client나 새
dependency를 추가하지 않으며 follow-up transport capability 없이는 production
`WRITE`를 사용할 수 없다. `PurchaseEventRedriveService`는 operator가 제공한
original envelope와 정확한 quarantine, event, source purchase, catalog identity를
받는다. `dryRun=true`는 plan write 없이 trust/scope/version/factory validation을
수행하고 정제된 diff hash를 quarantine audit에 추가한다. write redrive에는
`RELEASE_APPROVED`, actor/reason, release approval에 이미 기록된 approval reference가
추가로 필요하며, atomic handler를 호출하기 전에 `REDRIVE` audit를 추가한다.
Generic replay로 trust-failed terminal rejection을 다시 처리할 수 없다. bounded
retry 소진 시 inbox row를 allowlist된 reason code와 함께 `QUARANTINED`로 표시한다.
이 범위는 broker DLQ 또는 outbox delivery 완료를 주장하지 않는다.

pre-trust와 nonexistent-scope failure는 FK-free terminal rejection store를
사용하고 bounded metadata와 envelope hash만 보존한다. trusted in-scope
quarantine path는 inbox transition과 같은 transaction에서
`SchedulingQuarantineRepository`를 호출한다. repository는 transaction 전에
bounded original envelope를 암호화하고 ciphertext와 allowlist된 metadata만
영속화하며 `DETECTED` audit action을 추가한다. inspection, dry-run, redrive,
release denial, retention expiry, legal-hold 변경은 새 audit row를 추가하며 기존
audit row를 갱신하거나 삭제하지 않는다.

운영 contract:

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

alert evidence는 design baseline을 사용한다: purchase-to-plan p95 30초 초과,
queue/backlog 또는 metric-cardinality budget의 80% warning 및 95%
high/backpressure, 모든 trust failure, 지속적인 outbox/DLQ backlog를 감지한다.
Security on-call은 trust/signature/scope alert를, catalog owner는 catalog version
conflict를, scheduling on-call은 backlog/SLO alert를, commerce는 purchase source
correction을 담당한다. trust/signature/scope incident는 critical이다.
security on-call은 5분 이내 acknowledge하고 consumer path를 즉시 차단하며 event를
quarantine한다. backlog/SLO 및 cardinality-high alert는 15분 acknowledgement를
사용한다. 모든 alert class는 30분 secondary escalation을 사용하고 owner handoff
evidence를 보존한다. 이 범위에서는 outbox를 publish하지 않으므로 follow-up
transport plan이 batch publish, ack-only completion, retry/DLQ ownership, alert
wiring을 제공하기 전까지 production `WRITE` mode를 금지한다.

- [ ] **단계 6: bounded performance gate 작성 및 실행**

`PurchasePlanPerformanceIntegrationTest`는 singleton PostgreSQL과 MySQL launcher만
재사용하고 순차 실행한다. 일반적인 treatment 4개 plan과 정확한 최대치인
2,000-treatment/1,000-persisted-edge plan을 seed하고 JVM/database를 warm-up한 뒤
fixture마다 독립적으로 commit된 purchase event를 최소 10개 측정한다. raw elapsed
sample을 기록하고 p95가 30초 purchase-to-plan SLO 미만인지 assertion한다.

transaction observer는 최종 transaction 내부에서 external authority 또는
transport call이 발생하지 않음을 증명한다. SQL statement capture는 inbox claim,
authority-qualified catalog lookup, plan insert, treatment batch insert, dependency
batch insert, outbox insert, terminal inbox update를 분류하고 수를 제한한다.
Treatment 또는 edge별 select N+1은 failure다.

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.PurchasePlanPerformanceIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.PurchasePlanPerformanceIntegrationTest" -Dspring.profiles.active=test,test-mysql
```

예상 결과: 초기 RED 실행은 누락된 fixture 또는 statement budget에서 실패한다.
transaction boundary를 변경하지 않고 Exposed batch insert로 persistence를
최적화한 뒤 두 dialect가 모두 통과해야 한다.

- [ ] **단계 7: handler·dialect·unchanged-V8 GREEN 실행**

작업 3에서 이미 `uq_inbox_event_id`와 `uq_outbox_event_id`를 가진
`scheduling_inbox_events`, `scheduling_outbox_events`를 생성했다. 이 작업에서는
이미 배포된 V8 migration을 수정하지 않는다. `AppointmentPlans.uq_plan_source_purchase`는
tenant, clinic, purchase authority, purchase ID를 포함한다. 이 미출시 branch에서는
검증 전에 작업 3에서 V8을 수정한다. V8이 ephemeral test database 밖에 적용된
적이 있다면 forward-only V9를 사용한다.

실행:

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

dialect integration class는 `Containers.Postgres`/`Containers.MySql8`를 재사용하고
순차 실행한다. 두 duplicate race 형태, rollback injection, stale/gap retry 진행,
최종 quarantine, atomic plan transaction 내부의 external I/O 부재를 검증한다.
기존 API database matrix에 유지하며 `@Testcontainers`는 도입하지 않는다.

- [ ] **단계 8: commit**

작업 3과 이 작업의 migration proof가 통과한 뒤 V8을 수정하지 않고 event
convergence를 commit한다.

**Rollback/rerun:** consumer delivery를 중단하고 inbox/outbox와 plan history를
보존한다. handler를 수정한 뒤 이름을 지정한 `eventId`를 다시 처리한다.

---

### 작업 6: tenant 범위 plan read API 공개

**복잡도:** M

**선행 조건:** 작업 5

**수정 범위:** query service, DTO/controller, integration test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `test-driven-development`

**파일:**
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/AppointmentPlanQueryService.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/AppointmentPlanResponse.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentPlanController.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentPlanControllerTest.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentPlanReadSecurityIntegrationTest.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/AppointmentPlanReadExplainIntegrationTest.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`

- [ ] **단계 1: RED API test 작성**

다음을 assertion한다.

- `GET /api/{tenantCode}/clinics/{clinicId}/appointment-plans/{planId}`가 plan metadata, catalog source authority/version/hash, booking preference, 순서가 있는 treatment, dependency edge를 반환한다.
- `GET .../appointment-plans/by-purchase/{sourcePurchaseAuthority}/{sourcePurchaseId}`가 같은 view를 반환하고 composite unique index를 사용한다.
- cross-tenant 및 cross-clinic access는 존재를 노출하지 않고 `404`를 반환한다.
- 존재하지 않는 plan은 `404`를 반환한다.
- 비활성 plan read는 OpenAPI operation이 discoverable한 상태에서 `404 FEATURE_DISABLED`를 반환한다.
- foundation read API는 clinic operator 전용이다. `ADMIN/STAFF/DOCTOR`는 일치하는 tenant+clinic scope가 필요하고 `PATIENT`는 `403`을 받는다.
- response에는 raw inbox/outbox envelope, patient token/ciphertext/fingerprint, patient contact field가 포함되지 않는다.
- `400/404/500` response는 정제된 error contract를 사용하며 raw exception message, purchase ID, patient reference를 노출하지 않는다.

- [ ] **단계 2: RED 실행**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentPlanControllerTest"
```

- [ ] **단계 3: query 및 response mapping 구현**

query service는 `transaction {}` 내부에서 범위가 지정된 plan query 한 번과 bounded
child query를 수행한다. purchase lookup predicate와 unique key는 모두
`(tenantGroupId, clinicId, sourcePurchaseAuthority, sourcePurchaseId)`다.
repository test는 동일 scope uniqueness와 cross-scope isolation을 증명한다.
treatment는 BOM order와 sequence number로 정렬하고 dependency는
`idx_treatment_dependency_plan`을 사용해 `plan_id`로 조회한 뒤
predecessor/successor ID로 정렬한다. Controller path의 tenant/clinic은
aggregate와 일치해야 한다.

안정적인 OpenAPI discoverability를 위해 controller는 등록한 상태로 유지하되
`appointment.plan-foundation.plan-read-enabled=false`인 동안에는
`404 FEATURE_DISABLED`로 short-circuit한다. principal이 검증된 patient subject
claim을 가지고 repository가 하나의 predicate로 tenant+clinic+patient ownership을
지원할 때까지 patient-facing plan read는 명시적으로 연기한다.

- [ ] **단계 4: GREEN 실행**

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentPlanControllerTest"
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.security.AppointmentPlanReadSecurityIntegrationTest"
```

- [ ] **단계 5: RED index-plan assertion 작성 후 PostgreSQL 및 MySQL 증명**

`AppointmentPlanReadExplainIntegrationTest`는 최소 20개 tenant/clinic partition에
plan row 100,000개, treatment 2,000개와 persisted dependency 1,000개를 각각
가지는 dependency-bearing plan 20개, retry state에 걸친 inbox row 100,000개,
pending/complete state에 걸친 outbox row 100,000개를 seed한다. retry와 pending
row는 1% 분포를 사용하여 queue index proof가 비정상적으로 대부분이 backlog인
상태가 아니라 healthy bounded backlog를 모델링하게 한다. 다음 항목의 `EXPLAIN`을
실행하고 수집한다.

1. authority-qualified scoped purchase lookup;
2. `plan_id`를 기준으로 하는 dependency read;
3. status/replay time/received time에 따른 inbox retry polling;
4. outbox pending polling과 oldest-age lookup.

expected named composite index가 나타나고 estimated/scanned row가 선택한
partition 또는 bounded batch 안에 들어오지 않으면 test가 실패한다. 정제된 plan
text는 test report에 첨부할 수 있지만 patient field, raw event, key material,
signature는 금지한다.

먼저 ephemeral fixture에서 필요한 index를 의도적으로 제거한 상태로 test를
실행하고 해당 index 이름을 포함한 예상 RED assertion을 기록한다.
migration/schema index를 복원하고 두 dialect command를 실행해 GREEN을 요구한다.
이는 plan shape proof이며 production latency benchmark가 아니다.

```bash
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.AppointmentPlanReadExplainIntegrationTest" -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.integration.AppointmentPlanReadExplainIntegrationTest" -Dspring.profiles.active=test,test-mysql
```

- [ ] **단계 6: commit**

read API와 OpenAPI documentation을 함께 commit한다.

**Rollback/rerun:** read route만 비활성화하며 저장된 data는 변경하지 않는다.

---

### 작업 7: documentation·review·verification 수렴

**복잡도:** M

**선행 조건:** 작업 1~6

**수정 범위:** module documentation, plan/spec verification evidence, 최종 범위 내 수정

**필요한 스킬:** `bluetape-kotlin-patterns`, `verification-before-completion`, `requesting-code-review`

**파일:**
- 수정: `appointment-core/README.md`
- 수정: `appointment-core/README.ko.md`
- 수정: `appointment-event/README.md`
- 수정: `appointment-event/README.ko.md`
- 수정: `appointment-api/README.md`
- 수정: `appointment-api/README.ko.md`
- 수정: `README.md`
- 수정: `README.ko.md`
- 생성: `docs/lessons/2026-07-26-appointment-plan-foundation.md`
- 생성: `docs/runbooks/appointment-plan-foundation-recovery.md`
- 생성: `docs/verification/2026-07-26-appointment-plan-foundation-release-evidence.md`
- 검증: `docs/superpowers/plans/2026-07-26-appointment-plan-foundation.html`
- 검증: `docs/superpowers/specs/2026-07-26-appointment-plan-and-capacity-design.html`

- [ ] **단계 1: public documentation 갱신**

plan aggregate 경계, catalog sync endpoint, plan query endpoint, purchase event
ownership, inbox/outbox state, 명시적으로 연기한 동작을 문서화한다. 변경한 모든
module README와 root README가 English/Korean parity를 유지하게 한다. appointment
scheduling, hold, consent, resource allocation이 구현되었다고 주장하지 않는다.

recovery runbook에는 bounded retry, quarantine inspection, original-source version
확인, dry-run re-drive, 정확한 `eventId` 재처리, feature-flag rollback, immutable
history 보존을 고정한다. security on-call은 trust/signature/scope incident를,
catalog owner는 catalog version conflict를, scheduling on-call은 backlog/SLO를,
commerce는 purchase source correction을 담당한다. trust/signature/scope
incident는 critical이며 security-on-call은 5분 이내 acknowledge하고 consumer를
즉시 block/quarantine한다. backlog/SLO와 cardinality-high alert는 15분
acknowledgement를 사용하고 모든 class는 30분 secondary escalation을 사용한다.

- [ ] **단계 2: targeted proof 실행**

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

두 controller test는 `/v3/api-docs`를 가져와 catalog request, plan response,
authority-qualified purchase path, feature-disabled 동작, `401`, `403`, `413`을
포함한 모든 stable Foundation error response를 assertion한다.

- [ ] **단계 3: multi-dialect migration proof 순차 실행**

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

- [ ] **단계 4: module regression 실행**

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

예상 결과: 모두 `PASS`한다. 이 범위에서 scheduling behavior를 수정하지 않더라도
Solver와 notification이 shared appointment contract를 소비하므로 명시적으로
test한다.

- [ ] **단계 5: security 및 stability check 실행**

최종 diff에서 다음을 검사한다.

- 모든 read와 event write에서 tenant/clinic/source purchase ownership;
- duplicate event race와 transaction rollback;
- raw payload/PHI logging;
- bound가 없는 list, string, count, duration, interval input;
- production `!!`, broad exception swallowing, deprecated Exposed import;
- migration lock/index 순서와 MySQL/PostgreSQL identifier limit;
- stable OpenAPI status/error name.

검사한 commit, 변경 파일 목록, 모든 command 결과, 분류한 hit를
`docs/review/2026-07-27-appointment-plan-workflow-repair.md`에 기록한다. raw
payload/PHI field, `!!`, broad catch block, deprecated Exposed package,
identifier/index name은 재현 가능한 search로 검사하고, search 결과가 없다는
이유만으로 증명되었다고 간주하지 말고 모든 hit를 설명한다. delivery 전에
P0/P1은 0이어야 한다.

- [ ] **단계 6: rollout rehearsal 및 release evidence 수집**

evidence는 서로 대체할 수 없는 두 gate로 나눈다. Foundation local gate는
schema, contract, concurrency, security, scoped configuration resolution,
benchmark, rollback-history proof를 완료할 수 있다. production `WRITE` gate는
broker transport/ack/DLQ, 실제 Micrometer alert wiring, audited feature-control
provider/readback, backpressure, owner acknowledgement, production-like rollback
rehearsal이 준비될 때까지 **BLOCKED**로 유지한다. blocked production row는
완료한 Foundation check로 계산하지 않으며 완료한 Foundation local gate가
production `WRITE`를 승인하지도 않는다.

release-evidence document에 다음 항목별 command, timestamp, commit SHA, result,
artifact link를 기록한다.

1. H2/PostgreSQL/MySQL schema dry-run과 legacy row count/hash 비교;
2. legacy plan/item projection은 item model이 생길 때까지 연기하므로 foundation
   backfill을 명시적으로 `N/A`로 표시;
3. 0 write를 증명하는 `OFF → SHADOW` consumer diff와 test에서만 수행하는 gated
   `WRITE` proof;
4. current/previous schema replay, duplicate, out-of-order, aggregate version-gap
   case;
5. barrier 기반 duplicate race proof와 일반 plan 및 최대
   2,000-treatment/1,000-persisted-edge plan의 PostgreSQL/MySQL purchase-to-plan
   benchmark. 어떤 `WRITE` gate보다 먼저 p95가 30초 SLO 미만이어야 한다;
6. 대표 row count에서 scoped purchase lookup, plan dependency read, inbox retry
   polling, outbox backlog/oldest-age query에 대한 PostgreSQL/MySQL `EXPLAIN` 또는
   동등한 index 사용 evidence;
7. redacted metric/log capture, metric-label allowlist rejection, 1,000-series
   cardinality budget, trust failure·lag·80% warning·95% high/backpressure threshold
   alert smoke;
8. catalog sync, plan read, consumer write에 대한 정확한 tenant/clinic override
   하나를 비활성화하는 rollback rehearsal. 다른 clinic의 effective value는
   변하지 않고 생성된 plan/inbox/outbox history가 유지됨을 증명하며, production
   gate에는 audited change/readback evidence도 필요하다;
9. 정확한 catalog request, plan response, stable error envelope, feature-disabled
   response, authority-qualified purchase lookup path에 대한 생성된 OpenAPI
   assertion;
10. root와 변경한 세 module의 English/Korean README link 및 content parity 검증;
11. quarantine inspection, ciphertext retention expiry, legal hold, immutable audit,
    trust-failure release 거부, security on-call acknowledgement를 포함한
    dual-approval evidence;
12. alert owner acknowledgement와 escalation evidence. critical
    trust/signature/scope incident는 5분 이내 security-on-call acknowledgement와
    consumer block/quarantine을 증명한다. catalog owner는 version conflict를,
    scheduling on-call은 15분 이내 backlog/SLO와 cardinality-high alert를,
    commerce는 purchase correction을 담당하며 모든 class가 30분 secondary
    escalation을 증명한다;
13. Foundation plan HTML과 source design HTML의 HTML parse/link/browser smoke.
    internal anchor, relative Markdown link, desktop/mobile table rendering
    screenshot을 포함한다.

production rollout 순서는 additive schema, flags OFF, catalog sync enablement,
consumer SHADOW, plan read enablement이며 transport follow-up을 완료한 뒤에만
consumer WRITE로 진행한다. 최소 한 release window 동안 current와 바로 이전
purchase event schema version을 지원한다. tenant scope error, migration count/hash
mismatch, version-conflict 증가, SLO/error-budget breach는 rollback 기준이다.

- [ ] **단계 7: lesson 및 최종 Lore commit 기록**

lesson에는 plan state를 visit state와 분리하는 이유, policy foundation이
commitment보다 앞서야 하는 이유, inbox/plan/outbox가 하나의 transaction을
공유하는 이유를 기록한다. 승인된 foundation file만 stage하고 Lore protocol에
따라 commit한다.

---

## 4. 위험 예측

| 위험 | 신호 | 완화책 | Rollback/rerun 지점 |
|---|---|---|---|
| 같은 catalog version의 의미가 달라짐 | same version/different computed hash | immutable unique version + quarantine conflict | 작업 4 service test, sync route 비활성화 |
| BOM cycle이 plan 생성 뒤 발견됨 | topological sort failure | sync 단계에서 먼저 거부 | 작업 1 validator RED/GREEN |
| event duplicate가 같은 scope에 plan을 둘 만듦 | scoped source purchase unique violation | inbox + tenant/clinic-qualified source purchase unique + classified retry | 작업 5 concurrency test |
| 다른 catalog authority의 같은 product/version이 충돌 | catalog sync conflict 또는 잘못된 snapshot | authority-qualified unique key와 exact lookup | 작업 2 repository test, 작업 5 dialect event proof |
| inbox만 기록되고 plan/outbox가 없음 | transaction failure injection | 한 Exposed transaction으로 원자화 | 작업 5 rollback test |
| tenant catalog로 다른 clinic plan 생성 | scope mismatch metric/quarantine | handler에서 event/catalog ownership 재검증 | 작업 5 negative test |
| V8이 legacy appointment를 손상 | migration row/hash diff | additive table만 사용하고 legacy row pre/post assertion | 작업 3 migration test |
| JSON 요구사항 snapshot이 비결정적 | 같은 의미의 내부 set 순서가 달라지거나 BOM 순서가 바뀜 | BOM/dependency 순서는 보존하고 item 내부 set만 정렬하는 canonical encoding과 hash test | 작업 1 hasher test |
| 첫 단계가 예약까지 구현했다고 오해 | docs/API capability drift | read API와 README에 `plan only` 명시 | 작업 7 docs review |
| outbox enqueue를 전달 완료로 오해 | pending age/backlog 증가 | WRITE 금지 + transport 후속 계획 + ack-only completion contract | 작업 5/7 evidence |

## 5. 중단 조건

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

## 6. 실행 인계

Plan 구현에는 작업마다 새로운 implementation worker를 하나씩 배정하는
`subagent-driven-development`를 사용하고 작업 사이에 2단계 review를 수행한다.
작업 3과 작업 5의 container-backed DB check는 순차 실행해야 한다. 이 계획
문서는 PR, push, merge를 승인하지 않으며 해당 gate에는 repository workflow의
delivery authority가 필요하다.
