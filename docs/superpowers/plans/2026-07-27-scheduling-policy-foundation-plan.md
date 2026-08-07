# Scheduling Policy Foundation 구현 계획

> **에이전트 작업자 참고:** 이 계획은 작업별로 `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans` 하위 스킬을 사용해 구현해야 한다. 단계 추적에는 checkbox(`- [ ]`) 구문을 사용한다.

> 상태: 최종 사용자 구현 계획 승인 완료, Step 3-R P0=0/P1=0, Step 3-P PASS. Task 1 착수 준비, 생산 코드 미착수.

**목표:** 병원별 예약 정책을 typed·versioned aggregate로 관리하고, Gateway 인증 주체·승인·CAS·세대·outbox를 근거로 미래 예약 판단에 사용할 불변 `EffectiveSchedulingPolicy` snapshot을 생성한다.

**구조:** `appointment-core`가 policy contract, validator, compiler, Exposed table/repository와 순수 hash 규칙을 소유한다. `appointment-api`가 Gateway JWT를 불변 `ActorContext`로 변환하고 draft/preview/approval/activation/effective-read use case와 bounded worker를 제공하며, `appointment-event`의 기존 outbox를 aggregate-neutral envelope로 확장한다. 모든 예약 consumer는 feature flag가 꺼진 상태로 유지되어 기존 예약 생성 동작은 바뀌지 않는다.

**기술 스택:** Kotlin 2.3 language/API, Java 25 runtime contract, Spring Boot 4 MVC/Security/Scheduling/Actuator, Exposed JDBC, Flyway H2/PostgreSQL/MySQL, Jackson 3, JJWT 0.13.0, JUnit 5, bluetape4k assertions/concurrency/Testcontainers helpers.

**원본 설계:** [`2026-07-27-scheduling-policy-foundation-design.md`](../specs/2026-07-27-scheduling-policy-foundation-design.md)

**열람용 동반 문서:** [`2026-07-27-scheduling-policy-foundation-plan.html`](./2026-07-27-scheduling-policy-foundation-plan.html)

---

## 1. 전달 범위

### 포함 범위

- tenant default와 clinic override의 typed policy definition
- `DRAFT`, `SCHEDULED`, `ACTIVE`, `RETIRED` lifecycle과 stale revision 처리
- `INHERIT`, `SET`, `DISABLE` precedence와 platform/tenant hard ceiling
- `BOOKING_COMMITMENT`를 포함한 초기 8개 policy kind
- approval/activation authority 분리와 sensitive policy 직무분리
- deterministic canonical payload/snapshot hash와 source path 추적
- tenant/clinic generation vector, immutable snapshot, authoritative-read cache
- bounded sync/async impact preview와 polling
- immediate/scheduled activation, lease recovery, `MISSED`, manual replay
- aggregate-neutral scheduling outbox V9 expand/backfill/dual-write
- Gateway JWT audience/algorithm/clock-skew/claim validation과 `ActorContext`
- tenant/clinic policy administration API, stable error contract, OpenAPI
- H2/PostgreSQL/MySQL migration·repository·concurrency parity
- 한국어 requirements, 영문 KDoc, 운영 runbook, README locale parity

### 명시적으로 제외한 범위

| 제외 동작 | 이유 |
|---|---|
| 실제 `PROVISIONAL`, `HELD`, `CONFIRMED` 예약 state machine | visit/commitment aggregate가 policy snapshot을 소비하는 후속 issue의 책임 |
| 실제 capacity allocation, overbooking, waitlist, reliability score | 이번 변경은 policy 정의·compile·snapshot까지만 제공 |
| 고객 동의 생성·전자서명·환불·결제 | 외부 commerce/consent 서비스 경계 |
| broker outbox relay와 Pub/Sub transport | 현재 HTTP/admin foundation과 atomic outbox enqueue만 구현 |
| V10 outbox `aggregate_type/aggregate_id NOT NULL` cutover | 모든 writer의 dual-write와 null row 0건 운영 증거 후 별도 배포 |
| frontend 관리 화면 | issue #182 out of scope |

### PR 전달 권한

이 계획의 승인 범위는 `bluetape4k/clinic-appointment`, base
`develop`, head `feature/issue-182-scheduling-policy-foundation`의 구현·검증·PR
생성까지다. merge는 CI와 live review가 끝난 merge-ready 보고 후 별도 승인을
받는다.

## 2. 파일 구조

### `appointment-core`

| 경로 | 책임 |
|---|---|
| `model/policy/SchedulingPolicyContract.kt` | scope, lifecycle, kind, generation, source, actor audit 공통 contract |
| `model/policy/PolicyOverride.kt` | `INHERIT`, `SET`, `DISABLE`와 source tracking |
| `model/policy/BookingCommitmentPolicy.kt` | 관리자 직접확정, 고객 가예약, TTL, 동의 변경 규칙 |
| `model/policy/CapacityAndReliabilityPolicies.kt` | capacity/overbooking와 객관적 reliability 입력 policy |
| `model/policy/OperationalSchedulingPolicies.kt` | hold, reconfirmation, disruption, extension, SLA policy |
| `model/policy/EffectiveSchedulingPolicy.kt` | compiled immutable snapshot과 hash input |
| `service/SchedulingPolicyPayloadCodec.kt` | strict schema-version-aware Jackson 3 codec |
| `service/SchedulingPolicyValidator.kt` | scope/kind/schema/bounds/ceiling/직무분리 전제 검증 |
| `service/SchedulingPolicyHasher.kt` | canonical payload와 snapshot SHA-256 |
| `service/SchedulingPolicyCompiler.kt` | platform → tenant → clinic deterministic merge |
| `service/EffectivePolicyCache.kt` | generation-aware bounded LRU와 tenant quota |
| `model/tables/SchedulingPolicyDefinitions.kt` | immutable version envelope와 draft revision |
| `model/tables/SchedulingPolicyApprovals.kt` | revision별 approval evidence |
| `model/tables/SchedulingPolicyScopeHeads.kt` | scope revision/generation serialization row |
| `model/tables/EffectiveSchedulingPolicySnapshots.kt` | immutable compiled snapshot |
| `model/tables/SchedulingPolicyActivationCommands.kt` | keyed idempotency hash, lease, retry, missed/replay |
| `model/tables/SchedulingPolicyPreviewJobs.kt` | sync/async preview evidence와 cursor |
| `repository/SchedulingPolicyRepository.kt` | definition/approval/head/snapshot transaction primitives |
| `repository/SchedulingPolicyJobRepository.kt` | activation/preview claim, lease, checkpoint |
| `repository/SchedulingPolicyImpactRepository.kt` | 미래 appointment/active plan의 bounded keyset scan |

### `appointment-event`

| 경로 | 책임 |
|---|---|
| `event/integration/SchedulingOutboxEvents.kt` | aggregate-neutral outbox columns와 nullable legacy plan reference |
| `event/integration/SchedulingEventRepository.kt` | plan event dual-write 유지 |
| `event/policy/SchedulingPolicyActivatedEvent.kt` | redacted policy activation event contract |
| `event/policy/SchedulingPolicyEventRepository.kt` | activation transaction 안의 deterministic outbox insert |

### `appointment-api`

| 경로 | 책임 |
|---|---|
| `security/SchedulingUserPrincipal.kt` | validated gateway claims |
| `security/JwtSecurityProperties.kt` | issuer/audience/algorithm/clock-skew 설정 |
| `security/JwtTokenParser.kt` | JJWT strict parser와 conflicting actor claim 거절 |
| `security/ActorContextResolver.kt` | Spring principal/request를 immutable domain context로 변환 |
| `security/CorrelationIdFilter.kt` | bounded correlation ID 생성·전파 |
| `security/SecurityConfig.kt` | policy route matcher를 broad admin matcher 앞에 배치 |
| `policy/SchedulingPolicyCommandService.kt` | draft/approve/schedule/activate/retire transaction orchestration |
| `policy/EffectiveSchedulingPolicyService.kt` | double-read compile, snapshot CAS, authoritative cache read |
| `policy/SchedulingPolicyPreviewService.kt` | sync preview와 persisted async 전환 |
| `policy/SchedulingPolicyWorker.kt` | activation/preview lease worker와 startup catch-up |
| `policy/SchedulingPolicyMetrics.kt` | low-cardinality metrics와 structured result logging |
| `controller/TenantSchedulingPolicyController.kt` | tenant default admin API |
| `controller/ClinicSchedulingPolicyController.kt` | clinic override admin API |
| `controller/SchedulingPolicyPreviewJobController.kt` | tenant/clinic scoped polling |
| `dto/SchedulingPolicyRequests.kt` | actor/tenant/clinic/booking origin이 없는 strict request DTO |
| `dto/SchedulingPolicyResponses.kt` | lifecycle, preview, effective snapshot response |
| `config/SchedulingPolicyProperties.kt` | off-by-default flags, bounds, cadence, lease, cache quota |
| `config/SchedulingPolicyErrorCode.kt` | stable code, HTTP status, retryability, caller action |
| `config/SchedulingPolicyApiException.kt` | typed policy failure carrying one error code |
| `config/ServiceConfig.kt` | repository/service/worker wiring |
| `config/DatabaseConfig.kt` | Flyway-off dev/test table registration |
| `config/GlobalExceptionHandler.kt` | policy exception sanitization |

### Schema·test·documentation

| 경로 | 책임 |
|---|---|
| `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V9__add_scheduling_policy_foundation.sql` | policy tables와 outbox expand/backfill |
| `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/*Test.kt` | payload, validation, hash, compiler, cache |
| `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/SchedulingPolicyRepositoryTest.kt` | H2 transaction primitives |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/policy/*Test.kt` | command, preview, worker, cache integration |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/*Test.kt` | JWT/actor/scope escalation |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/*SchedulingPolicy*Test.kt` | API/OpenAPI/stable error |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/*MigrationTest.kt` | three-dialect V9 parity |
| `docs/api/scheduling-policy.md` | caller contract and examples |
| `docs/runbooks/scheduling-policy-activation.md` | lateness, missed, replay, rollback |
| `docs/requirements/{architecture,domain-model,data-flow}.md` | Korean source-of-truth requirements |
| `README.md`, `README.ko.md` | feature/API/flag parity |

## 3. Acceptance 추적성

| Acceptance 기준 | 계획 작업 및 증거 |
|---|---|
| deterministic tenant + clinic compile/hash | Task 1 compiler/hash tests, Task 7 effective-read tests |
| explicit `INHERIT/SET/DISABLE` | Task 1 validator/compiler table tests |
| validation before generation change | Task 6 transaction rollback tests |
| one activation winner per boundary | Task 2 lock primitives, Task 6 concurrent integration |
| idempotent activation retry | Task 6 fingerprint/result replay |
| generation + cache invalidation + one outbox event | Task 4, 6, 7 |
| immutable/reproducible snapshots | Task 2 repository, Task 7 double-read/CAS |
| bounded non-mutating preview | Task 8 fake-clock and DB-count assertions |
| three-dialect persistence/uniqueness/concurrency | Task 3 and Task 10 sequential matrix |
| actor identity only from Gateway claims | Task 5 parser/resolver/security tests |
| admin direct-confirm vs patient provisional policy | Task 1 typed compile tests |
| confirmed change requires new consent | Task 1 non-disableable invariant |
| async `202`/polling terminal contract | Task 8/9 API tests |
| documentation and operational explanation | Task 9/10 KDoc, requirements, OpenAPI, runbook |
| existing booking behavior unchanged | Task 9 off-by-default flags and legacy API regression |

---

### 작업 1: typed policy contract·validation·deterministic compilation 고정

**복잡도:** XL

**선행 조건:** 승인된 design

**수정 범위:** 새 `appointment-core/model/policy`, pure service 및 unit test

**필요한 스킬:** `bluetape-kotlin-patterns`, `test-driven-development`

**파일:**
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/SchedulingPolicyContract.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/PolicyOverride.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/BookingCommitmentPolicy.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/CapacityAndReliabilityPolicies.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/OperationalSchedulingPolicies.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/EffectiveSchedulingPolicy.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyPayloadCodec.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyValidator.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyHasher.kt`
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyCompiler.kt`
- 수정: `appointment-core/build.gradle.kts`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyValidatorTest.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyCompilerTest.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyHashTest.kt`

- [ ] **단계 1: RED contract 및 invariant test 작성**

공통 envelope와 generation contract를 고정한다.

```kotlin
data class SchedulingPolicyDefinition(
    val id: Long?,
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicId: Long?,
    val kind: SchedulingPolicyKind,
    val version: Long,
    val schemaVersion: Int,
    val lifecycle: PolicyLifecycle,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val revision: Long,
    val payloadHash: String,
    val payload: SchedulingPolicyPayload,
    val createdBy: ActorAuditRef,
    val changeReason: String,
) : Serializable

sealed interface OverrideValue<out T> : Serializable {
    data object Inherit : OverrideValue<Nothing>
    data class Set<T : Serializable>(val value: T) : OverrideValue<T>
    data object Disable : OverrideValue<Nothing>
}
```

test는 clinic이 있는 tenant scope, clinic이 없는 clinic scope, 1 미만의
version/revision, 빈 reason, unknown schema version, 잘못된 effective interval,
kind/payload mismatch, required field의 `DISABLE`, tenant/platform hard ceiling을
완화하는 clinic value를 거부해야 한다.

- [ ] **단계 2: RED booking-origin 및 TTL test 작성**

승인된 spec에서 정확한 booking contract를 작성하고 다음을 증명한다.

```kotlin
BookingCommitmentPolicy(
    adminBookingMode = DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE,
    patientBookingMode = PROVISIONAL_APPROVAL_REQUIRED,
    provisionalCapacityMode = HARD_HOLD,
    provisionalRequestTtl = 24.hours,
    resourceHoldTtl = 15.minutes,
    approvalRoles = setOf(ActorRole.ADMIN, ActorRole.STAFF),
    adminConsentEvidence = consentRequirement(),
    confirmedChangeMode = NEW_PROPOSAL_AND_CUSTOMER_CONSENT,
)
```

`provisionalRequestTtl`은 `5m..7d`만 허용한다. `HARD_HOLD`에는
`resourceHoldTtl`은 `1m..30m`이며 request TTL보다 길 수 없다. `NO_HOLD`와
`SOFT_HOLD`에서는 이 값을 사용할 수 없다. `confirmedChangeMode`는 비활성화하거나
완화할 수 없다.

- [ ] **단계 3: 8개 tenant·clinic payload pair 정의**

schema version 1을 사용하는 sealed·serializable payload를 정의한다.

```kotlin
sealed interface SchedulingPolicyPayload : Serializable {
    val kind: SchedulingPolicyKind
}

data class CapacityAndOverbookingPolicy(
    val nominalCapacity: Int,
    val overbookingQuota: Int,
    val absoluteBookingLimit: Int,
    val automaticReductionEnabled: Boolean,
) : SchedulingPolicyPayload

data class CapacityAndOverbookingOverride(
    val nominalCapacity: OverrideValue<Int>,
    val overbookingQuota: OverrideValue<Int>,
    val automaticReductionEnabled: OverrideValue<Boolean>,
) : SchedulingPolicyPayload
```

hold/consent, priority/reliability, reconfirmation, disruption recovery, operating
extension, notification/SLA에도 동등한 explicit contract를 정의한다. absolute
limit, confirmed-change consent, legal/safety extension ceiling, mandatory SLA
bound 같은 safety field는 disable할 수 없다.

- [ ] **단계 4: RED 실행**

```bash
./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.policy.SchedulingPolicyValidatorTest" \
  --tests "io.bluetape4k.clinic.appointment.policy.SchedulingPolicyCompilerTest" \
  --tests "io.bluetape4k.clinic.appointment.policy.SchedulingPolicyHashTest"
```

예상 결과: policy contract가 없으므로 compilation이 실패한다.

- [ ] **단계 5: strict codec 및 canonical hash 구현**

`appointment-core`에 Jackson 3 Kotlin dependency를 추가한다. 명시적인
`(kind, scope, schemaVersion)` dispatch로 decode하며 open polymorphic
deserialization은 활성화하지 않는다. unknown field를 거부하고 decode 또는
hashing 전에 256 KiB UTF-8 payload limit를 적용한다.

`SchedulingPolicyHasher`는 schema version, named field, explicit null marker,
정렬된 map/set, semantic list 순서를 SHA-256에 기록한다. actor audit timestamp와
database ID는 payload hash에서 제외하고 source version, generation, evaluation
time, compiled payload, warning, disabled path는 snapshot hash에 포함한다.

- [ ] **단계 6: deterministic compiler 구현**

merge 순서는 platform default → active tenant default → active clinic override다.
모든 output path에는 `PLATFORM`, `TENANT`, `CLINIC` source를 기록한다. hash input
전에 map과 set을 정렬한다. clinic override는 더 엄격한 tenant/platform value보다
capacity/extension/retry ceiling을 높일 수 없다.

- [ ] **단계 7: GREEN 및 determinism stress 실행**

```bash
./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.policy.*"
```

예상 결과: `PASS`; 섞은 map/set input permutation 1,000개가 snapshot hash 하나를
생성한다.

- [ ] **단계 8: 작업 1 commit**

Lore protocol에 따라 작업 1 경로만 commit한다. intent line에는 persistence 전에
policy semantics가 deterministic해야 하는 이유를 설명한다.

**Rollback/rerun:** schema가 없으므로 commit을 revert하고 policy unit package를
다시 실행한다.

---

### 작업 2: definition·approval·head·snapshot·job 영속화

**복잡도:** XL

**선행 조건:** 작업 1

**수정 범위:** `appointment-core` Exposed table, record, repository, H2 test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**파일:**
- 생성: 2절에 나열한 table file 6개와 repository file 3개
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/SchedulingPolicyRecords.kt`
- 수정: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/RecordMappers.kt`
- 수정: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/SchedulingPolicyRepositoryTest.kt`
- 테스트: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/SchedulingPolicyJobRepositoryTest.kt`

- [ ] **단계 1: RED repository test 작성**

definition identity unique성, `clinic_scope_key=0` tenant sentinel,
revision-scoped approval, immutable snapshot hash, monotonic generation, activation
keyed idempotency hash+fingerprint, preview/activation lease reclaim을 증명한다.
입력 key의 길이/문자 집합을 제한하고 `idempotencyKeyHash`만 저장하며 raw key가
row, log, response에 나타나지 않음을 증명한다.

```text
uq_policy_definition:
  tenant_group_id, scope, clinic_scope_key, policy_kind, version
uq_policy_approval:
  definition_id, draft_revision, actor_id
uq_policy_scope_head:
  tenant_group_id, scope, clinic_scope_key
uq_effective_policy_hash:
  tenant_group_id, clinic_id, snapshot_hash
uq_policy_activation_idempotency:
  tenant_group_id, scope, clinic_scope_key, idempotency_key_hash
```

non-null `clinic_scope_key`로 nullable clinic ID를 포함하는 unique constraint에서
PostgreSQL/MySQL/H2 간 차이를 방지한다.

- [ ] **단계 2: RED 실행**

```bash
./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepositoryTest" \
  --tests "io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepositoryTest"
```

- [ ] **단계 3: caller-transaction repository 구현**

Public KDoc에 transaction ownership을 명시한다. 모든 write는 caller-owned
`transaction {}` 내부에서 실행한다. top-level Exposed operator와 DSL receiver
내부의 named local value를 사용하고, scope-head bootstrap에는 `insertIgnore`를
사용한다. head를 tenant-before-clinic 순서로 확보한 뒤에만 `forUpdate()`를
사용한다.

```kotlin
fun lockScopeHead(scope: PolicyScopeRef): SchedulingPolicyScopeHeadRecord

fun compareAndIncrementGeneration(
    scope: PolicyScopeRef,
    expectedRevision: Long,
): SchedulingPolicyScopeHeadRecord

fun findOverlappingPublishedDefinitions(
    scope: PolicyScopeRef,
    kind: SchedulingPolicyKind,
    from: Instant,
    until: Instant?,
): List<SchedulingPolicyDefinitionRecord>
```

- [ ] **단계 4: lease 및 checkpoint primitive 구현**

claim은 state와 lease가 유효한 row만 update한다. completion은 live `leaseOwner`를
확인하므로 stale worker가 terminal state를 update할 수 없다.

```kotlin
fun claimDueActivation(
    commandId: Long,
    owner: String,
    now: Instant,
    leaseUntil: Instant,
): Boolean

fun checkpointPreview(
    jobId: Long,
    owner: String,
    cursor: PolicyPreviewCursor,
    progress: PolicyPreviewProgress,
): Boolean
```

- [ ] **단계 5: GREEN 실행**

```bash
./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.repository.SchedulingPolicy*Test" \
  --tests "io.bluetape4k.clinic.appointment.model.tables.TableSchemaTest"
```

- [ ] **단계 6: 작업 2 commit**

table, record, repository, mapper, test를 함께 commit한다.

**Rollback/rerun:** V9 배포 전에는 commit을 revert할 수 있다. V9 이후에는 additive
table을 유지하고 모든 policy write flag를 비활성화한다.

---

### 작업 3: 3개 dialect V9 및 rolling-safe outbox 확장

**복잡도:** XL

**선행 조건:** 작업 2

**수정 범위:** Flyway V9, schema registration, migration test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**파일:**
- 생성: `V9__add_scheduling_policy_foundation.sql` file 3개
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt`
- 수정: H2/PostgreSQL/MySQL Flyway migration test
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/AppointmentPlanMigrationTestSupport.kt`

- [ ] **단계 1: RED migration assertion 작성**

6개 policy table, named index/FK/check, 기존 V8 row 보존, outbox compatibility를
모두 assertion한다. V8 plan outbox row를 seed하고 V9로 migration한 뒤 다음을
증명한다.

```text
aggregate_type = APPOINTMENT_PLAN
aggregate_id = CAST(plan_id AS text)
plan_id remains populated
status/next_attempt_at and status/created_at indexes remain usable
```

- [ ] **단계 2: H2 RED 실행**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
```

- [ ] **단계 3: V9 expand/backfill 구현**

V9는 nullable `aggregate_type`과 `aggregate_id`를 추가하고 idempotent하게
backfill하며 legacy `plan_id`를 nullable로 만들고 aggregate lookup index를
추가한다. 향후 V10의 `NOT NULL` constraint는 추가하지 않는다. 새 application이
배포될 때까지 policy event write는 기본적으로 비활성화한다.

migration comment에는 dialect syntax와 공유 logical invariant를 설명한다. V8
checksum을 수정하거나 `scheduling_*` table 이름을 바꾸지 않는다.

- [ ] **단계 4: sequential dialect matrix 실행**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest" \
  -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest" \
  -Dspring.profiles.active=test,test-mysql
```

예상 결과: 기존 singleton launcher를 사용하고 `@Testcontainers` 없이 모두
`PASS`한다.

- [ ] **단계 5: 작업 3 commit**

migration과 schema parity test를 함께 commit한다.

**Rollback/rerun:** V9는 forward-only다. policy write를 비활성화하며 history를
삭제하거나 다시 쓰지 않는다. 모든 writer가 dual-write하는지와 다음 query가
0건을 반환하는지 증명하는 별도 deployment checklist가 있을 때만 V10을 승인한다.
`SELECT COUNT(*) FROM scheduling_outbox_events WHERE aggregate_type IS NULL OR aggregate_id IS NULL`
가 0을 반환해야 한다.

---

### 작업 4: outbox 일반화 및 activation event 하나의 원자적 publish

**복잡도:** L

**선행 조건:** 작업 3

**수정 범위:** `appointment-event` table/repository/event 및 test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**파일:**
- 수정: `SchedulingOutboxEvents.kt`
- 수정: `SchedulingEventRepository.kt`
- 생성: `SchedulingPolicyActivatedEvent.kt`
- 생성: `SchedulingPolicyEventRepository.kt`
- 수정: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedHandlerTest.kt`
- 생성: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/policy/SchedulingPolicyEventRepositoryTest.kt`

- [ ] **단계 1: RED dual-write test 작성**

기존 `AppointmentPlanCreated` row는 다음을 기록해야 한다.

```kotlin
aggregateType = "APPOINTMENT_PLAN"
aggregateId = planId.toString()
legacyPlanId = planId
```

test에는 `aggregate_type IS NULL OR aggregate_id IS NULL`을 세고 legacy plan row와
`APPOINTMENT_PLAN` aggregate value를 비교하며 dual-write parity gauge를 보고하는
operator verification query도 포함한다. backfill/new-writer 수렴 뒤 기대 count는
0이며 count 또는 parity check가 0이 아니면 policy write를 계속 비활성화한다.

policy activation은 `aggregateType=SCHEDULING_POLICY`, definition/version, effective
interval, generation vector, payload hash, redacted actor audit reference,
correlation ID를 기록하며 bearer token이나 policy payload는 기록하지 않는다.

- [ ] **단계 2: RED 실행**

```bash
./gradlew :appointment-event:test \
  --tests "io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedHandlerTest" \
  --tests "io.bluetape4k.clinic.appointment.event.policy.SchedulingPolicyEventRepositoryTest"
```

- [ ] **단계 3: deterministic event ID 및 insert 구현**

```kotlin
fun insertPolicyActivated(
    definition: SchedulingPolicyDefinitionRecord,
    generation: PolicyGenerationVector,
    actor: ActorAuditRef,
    correlationId: String,
): String
```

definition ID, version, effectiveFrom, result generation에서 event ID를 도출한다.
activation service는 lifecycle과 head generation을 변경하는 동일한 Exposed
transaction 내부에서 이 method를 호출한다.

- [ ] **단계 4: GREEN 및 payload leak scan 실행**

```bash
./gradlew :appointment-event:test
rg -n "bearer|patientReference|payloadJson.*SchedulingPolicyActivated" appointment-event/src
```

예상 결과: test가 `PASS`하고 scan에서 secret/PHI inclusion path가 발견되지 않는다.

- [ ] **단계 5: 작업 4 commit**

outbox compatibility와 activation event를 하나의 원자적 동작 변경으로 commit한다.

**Rollback/rerun:** policy event writing은 계속 feature-disabled 상태로 둔다. 기존
plan event dual-write는 V9와 backward compatible하다.

---

### 작업 5: Gateway authentication 강화 및 immutable `ActorContext` 도출

**복잡도:** XL

**선행 조건:** 작업 1

**수정 범위:** API security/config/filter 및 security test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `test-driven-development`

**파일:**
- 수정: `SchedulingUserPrincipal.kt`, `JwtSecurityProperties.kt`, `JwtTokenParser.kt`
- 생성: `ActorContextResolver.kt`, `CorrelationIdFilter.kt`
- 수정: `SecurityConfig.kt`, `GlobalExceptionHandler.kt`
- 수정: `SchedulingApiErrorResponse.kt`
- 수정: `TestJwtProvider.kt`, `JwtTokenParserTest.kt`
- 생성: `ActorContextResolverTest.kt`, `SchedulingPolicySecurityIntegrationTest.kt`
- 수정: `application.yml` 및 integration-test configuration

- [ ] **단계 1: RED JWT validation test 작성**

issuer, audience, `jti`, issued-at/auth-time, actor type, role, allowed tenant set,
allowed clinic set, assurance, optional patient subject를 요구한다. audience 누락,
잘못된 audience, HS384/HS512, 만료 또는 아직 유효하지 않은 token, 과도한 clock
skew, 누락/빈 `jti`, actor/role claim 충돌, 자체 subject가 없는 patient token을
거부한다. malformed, expired, wrong-audience, conflicting-claim case에서도
response/log output에 raw token, claim value, parser detail이 포함되지 않는지
assertion한다. generic authentication/policy stable code와 bounded correlation ID만
노출한다.

- [ ] **단계 2: JJWT 0.13 parser API 고정**

로컬에서 검증한 다음 API를 사용한다.

```kotlin
Jwts.parser()
    .verifyWith(signingKey)
    .requireIssuer(properties.issuer)
    .requireAudience(properties.audience)
    .clockSkewSeconds(properties.allowedClockSkew.seconds)
    .sig().clear().add(Jwts.SIG.HS256).and()
    .build()
    .parseSignedClaims(token)
```

request body field는 `ActorContext`에 포함하지 않는다.

- [ ] **단계 3: principal 및 resolver 구현**

```kotlin
data class ActorContext(
    val actorId: String,
    val actorType: ActorType,
    val roles: Set<ActorRole>,
    val scopes: Set<String>,
    val allowedTenantCodes: Set<String>,
    val allowedClinicIds: Set<Long>,
    val patientSubjectId: String?,
    val assurance: AuthenticationAssurance,
    val issuer: String,
    val tokenId: String,
    val authenticatedAt: Instant,
    val correlationId: String,
) : Serializable
```

`ActorContextResolver`는 principal을 기준으로 path tenant와 clinic을 다시 검증한다.
audit persistence에는 전체 context나 JWT가 아니라 `ActorAuditRef`를 사용한다.

- [ ] **단계 4: correlation 전파 및 stable error 추가**

bounded safe `X-Correlation-Id`만 허용하고 그 외에는 UUID를 생성한다. 이를 request
attribute와 response header에 저장한다. `SchedulingApiErrorResponse`에는 기존
constructor caller를 보존하는 default와 함께 `retryable`, `action`을 추가한다.

- [ ] **단계 5: GREEN 실행**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest" \
  --tests "io.bluetape4k.clinic.appointment.api.security.ActorContextResolverTest" \
  --tests "io.bluetape4k.clinic.appointment.api.security.SchedulingPolicySecurityIntegrationTest"
```

- [ ] **단계 6: 작업 5 commit**

strict JWT parsing, actor context, error/correlation contract, test를 commit한다.

**Rollback/rerun:** policy endpoint는 계속 비활성화한다. 계속 진행하기 전에 기존
catalog/plan security test가 계속 GREEN인지 확인해야 한다.

---

### 작업 6: draft·approval·schedule·activation·retirement·replay 구현

**복잡도:** XL

**선행 조건:** 작업 1, 2, 4, 5

**수정 범위:** policy application service, configuration wiring, transaction test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, `test-driven-development`

**파일:**
- 생성: `SchedulingPolicyCommandService.kt`
- 생성: `SchedulingPolicyCommand.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/SchedulingPolicyErrorCode.kt`
- 생성: `SchedulingPolicyApiException.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingApiErrorResponse.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- 수정: `ServiceConfig.kt`
- 테스트: `SchedulingPolicyCommandServiceTest.kt`
- 테스트: `SchedulingPolicyActivationConcurrencyTest.kt`

- [ ] **단계 1: RED lifecycle 및 authority test 작성**

허용 transition과 stable rejection code를 고정한다.

```text
DRAFT --schedule--> SCHEDULED --activate--> ACTIVE --retire--> RETIRED
DRAFT --activate--> ACTIVE
```

`SchedulingPolicyErrorCode`는 유일한 policy error registry다.

```kotlin
enum class SchedulingPolicyErrorCode(
    val httpStatus: HttpStatus,
    val retryable: Boolean,
    val action: String,
)
```

`SchedulingPolicyApiException`은 enum value 하나와 정제된 detail을 전달하며 wire
DTO를 대체하지 않는다. `GlobalExceptionHandler`는 이를 기존
`SchedulingApiErrorResponse(error, errorCode, correlationId, retryable, action)`으로
매핑한다. OpenAPI는 stack, JWT, claim, payload, raw idempotency-key detail 없이
해당 response schema와 승인된 status/code pair를 노출한다.

`POLICY_PREVIEW_LIMITED`만 `retryable=true`이고 나머지 승인된 policy code는 모두
false다. 다음 command 전에 payload, scope, stale revision/generation, approval,
conflict, missed activation 또는 idempotency intent를 변경해야 하기 때문이다. test는
모든 enum constant와 wire response의 정확한 값을 assertion한다.

draft edit은 revision을 증가시키고 이전 approval/preview를 stale로 만든다.
sensitive kind에는 서로 다른 approval actor와 별도 activation authority가 필요하다.
creator/activator가 dual approval을 묵시적으로 충족할 수 없다. `SYSTEM`에는
service scope와 scheduled command evidence가 필요하다.

- [ ] **단계 2: RED transaction/concurrency test 작성**

하나의 scope/kind/boundary에 대한 concurrent activation 2개는 `COMPLETED` command
하나, active generation 증가 하나, outbox event 하나를 만든다. 같은 key
hash/fingerprint는 original result를 replay하고, 같은 key hash와 다른 fingerprint는
`POLICY_IDEMPOTENCY_CONFLICT`를 반환한다. 주입한 outbox failure는 lifecycle,
generation, command result, snapshot을
rollback한다.

- [ ] **단계 3: short pre-transaction work 구현**

transaction을 열기 전에 decode, validate, canonicalize, hash, `Idempotency-Key`
bound 적용, keyed hash와 command fingerprint 계산을 수행한다. repository lookup와
conflict detection은 `idempotencyKeyHash + commandFingerprint`를 사용하며 raw
header는 persistence, log, event, response에 포함하지 않는다. transaction은 다음
승인된 순서를 정확히 수행한다.

```text
idempotency lookup → revision/preview/approval/authority checks
→ tenant head then clinic head lock → interval overlap check
→ previous active retire → target active → generation increment
→ command complete → outbox insert → commit
```

CAS/lock KDoc에는 보호하는 invariant와 tenant-before-clinic lock 순서를 설명한다.

- [ ] **단계 4: schedule 및 manual replay 구현**

scheduling은 deterministic `PENDING` activation command를 만든다. manual replay는
`MISSED` command를 참조하는 새 command를 만들며 terminal row를 다시 쓰지 않는다.
retirement는 definition이나 snapshot을 삭제하지 않는다.

- [ ] **단계 5: GREEN 실행**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyCommandServiceTest" \
  --tests "io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyActivationConcurrencyTest"
```

- [ ] **단계 6: 작업 6 commit**

lifecyle service와 transaction proof를 commit한다.

**Rollback/rerun:** `admin-write-enabled`와 `scheduled-activation-enabled`를
비활성화한다. forward repair는 명시적인 retire/replay을 사용하며 active row를
수동으로 수정하지 않는다.

---

### 작업 7: authoritative cache check를 포함한 immutable effective snapshot compile

**복잡도:** XL

**선행 조건:** 작업 1, 2, 6

**수정 범위:** compiler orchestration, cache, snapshot test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**파일:**
- 생성: `EffectivePolicyCache.kt`
- 생성: `EffectiveSchedulingPolicyService.kt`
- 테스트: `EffectivePolicyCacheTest.kt`
- 테스트: `EffectiveSchedulingPolicyServiceTest.kt`

- [ ] **단계 1: RED generation-mixing test 작성**

첫 번째와 두 번째 read 사이에 tenant 또는 clinic head를 변경한다. compiler는
mixed result를 버리고 최대 3회 retry한다. snapshot insert는 expected vector를
다시 확인한다. retry 소진 시 stable conflict를 반환하며 mixed generation을
저장하지 않는다.

- [ ] **단계 2: RED cache correctness test 작성**

모든 effective read는 cache lookup 전에 authoritative DB generation vector를
가져온다. DB failure에서는 stale cached snapshot을 반환하지 않는다. 이전 generation
entry가 물리적으로 남아 있을 수는 있지만 반환하지 않는다. `effectiveFrom`,
`effectiveUntil`, scheduled activation, emergency expiry, DST gap/overlap의 boundary
key를 test한다.

- [ ] **단계 3: bounded LRU 구현**

access-order `LinkedHashMap`에는 `synchronized`가 아니라 `ReentrantLock`을
사용한다. 전체 entry 수, tenant별 entry 수, 추정 byte quota를 강제한다. cache
key에는 tenant, clinic, tenant generation, clinic generation, decision boundary,
service boundary를 포함한다. eviction log에는 low-cardinality scope/hash prefix만
기록한다.

- [ ] **단계 4: double-read compile 및 snapshot 재사용 구현**

```kotlin
fun getEffective(
    tenantGroupId: Long,
    clinicId: Long,
    decisionAt: Instant,
    serviceAt: Instant,
): EffectiveSchedulingPolicy
```

head를 읽고 선언한 evaluation time의 active definition을 load한 뒤 compile하고
head를 다시 읽어 expected vector로 저장한다. 동일한 tenant/clinic scope 안에서만
동일 snapshot을 재사용한다.

- [ ] **단계 5: GREEN 실행**

```bash
./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.policy.EffectivePolicyCacheTest"
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.policy.EffectiveSchedulingPolicyServiceTest"
```

- [ ] **단계 6: 작업 7 commit**

correctness-before-optimization evidence와 함께 cache와 effective read를 commit한다.

**Rollback/rerun:** immutable snapshot을 보존하면서 effective read를 비활성화하거나
cache를 우회한다. cache/event invalidation은 source of truth가 아니다.

---

### 작업 8: bounded preview 및 crash-safe scheduled worker 추가

**복잡도:** XL

**선행 조건:** 작업 2, 6, 7

**수정 범위:** impact repository, preview service, worker, properties, metrics, test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, `test-driven-development`

**파일:**
- 생성: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/SchedulingPolicyImpactRepository.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/policy/SchedulingPolicyPreviewService.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/policy/SchedulingPolicyWorker.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/policy/SchedulingPolicyMetrics.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/SchedulingPolicyProperties.kt`
- 수정: actuator를 위한 `appointment-api/build.gradle.kts`
- 테스트: `SchedulingPolicyPreviewServiceTest.kt`
- 테스트: `SchedulingPolicyWorkerTest.kt`
- 테스트: `SchedulingPolicyPropertiesTest.kt`

- [ ] **단계 1: RED preview boundary test 작성**

fake monotonic clock와 keyset fixture로 limit 미만의 synchronous completion, row
10,000 또는 2초에서의 async 전환, partial result discard, 5,000-row partition
checkpoint, stale revision/generation 종료, chunk boundary cancellation, tenant
concurrency 2, queue size 100, saturated 상태에서만 `429`가 발생하는지 증명한다.
partition은 bounded stream으로 처리한다. worker가 영향받은 row 10,000개를 모두
보유해서는 안 되며 test는 partition별 최대 materialized row 수를 기록한다.

`SchedulingPolicyPreviewService`는 caller-owned `transaction {}` block을 통해 core
impact repository를 호출한다. core repository는 bounded keyset query primitive만
공개하고 API DTO, Spring MVC, worker type에 의존하지 않는다.

```kotlin
data class PolicyImpactCursor(
    val scheduledAt: Instant,
    val aggregateType: PolicyImpactAggregateType,
    val aggregateId: String,
) : Serializable

data class PolicyImpactPage(
    val items: List<PolicyImpactKey>,
    val nextCursor: PolicyImpactCursor?,
) : Serializable

fun scanFutureWork(
    scope: PolicyScopeRef,
    horizonFrom: Instant,
    horizonUntil: Instant,
    after: PolicyImpactCursor?,
    limit: Int,
): PolicyImpactPage
```

`limit`은 설정된 최대 5,000 row와 비교해 검증한다. service는 page 사이에서
monotonic deadline을 확인하며 repository SQL은 unbounded count를 수행하거나 전체
appointment/plan payload를 반환하지 않는다.

- [ ] **단계 2: preview evidence 정의**

동기 완료를 포함한 모든 preview는 persisted job row를 가진다. terminal
`COMPLETED`는 result hash와 activation evidence token을 기록한다. `STALE`, `FAILED`,
`CANCELLED`는 token을 노출하지 않는다. RED test는 모든 terminal transition이
tenant concurrency permit과 claim/lease ownership을 해제하고 cursor/checkpoint
resource를 닫거나 inert하게 만들며 reclaim할 수 없고 worker shutdown 뒤 실행 가능한
in-memory work가 변경할 수 없음을 증명한다.

- [ ] **단계 3: RED activation-runner recovery test 작성**

DB-time due selection, deterministic key, lease winner 하나, dead owner 이후 reclaim,
exponential backoff+jitter, startup catch-up, 60초 lateness warning, 5분 또는 설정된
deadline의 `MISSED`, 이전 active 보존, 동일 scope multi-kind bounded retry를
증명한다.

- [ ] **단계 4: persisted worker 구현**

좁은 configuration에서 `@EnableScheduling`을 사용하고 짧은 scheduled scan을
수행한다. worker method는 하나의 transaction에서 ID를 claim하고 각 claim을
분리해 처리하여 failure한 policy가 batch lock을 점유하지 않게 한다. due/lease
판정에는 DB current time을, process별 preview deadline에는 monotonic time을 사용한다.
`maxActivationClaimsPerTick`와 `maxPreviewJobsPerTick`을 고정하고 한 tick이
DB/worker time을 독점하지 않으며 activation burst가 preview work를 굶기지 않는지
test한다. shutdown은 새 claim을 중지하고 bounded grace period를 기다린 뒤 terminal
row를 건드리지 않고 미완료 durable row를 reclaim할 수 있게 남긴다.

- [ ] **단계 5: metric 및 low-cardinality log 추가**

Spring Boot Actuator를 추가한다. activation result/lateness/retry/missed, preview
sync/async/stale/deadline, compile cold/warm, cache hit/eviction/quota/stale rejection,
authoritative generation read failure, outbox pending/published/failed, oldest-pending
age, aggregate-null count, dual-write parity를 기록한다. tag에는 result, kind, scope
type만 포함하고 tenant ID, actor ID, payload, token, correlation ID는 포함하지 않는다.

- [ ] **단계 6: GREEN 실행**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyPreviewServiceTest" \
  --tests "io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyWorkerTest" \
  --tests "io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyPropertiesTest"
```

- [ ] **단계 7: 작업 8 commit**

preview/runner/metrics/config과 deterministic clock/lease test를 commit한다.

**Rollback/rerun:** 두 worker flag를 독립적으로 비활성화한다. pending job/command는
후속 catch-up을 위해 durable 상태로 유지하며 terminal row를 다시 쓰지 않는다.

---

### 작업 9: 범위가 지정된 admin/effective API 및 stable caller contract 공개

**복잡도:** XL

**선행 조건:** 작업 5, 6, 7, 8

**수정 범위:** DTO, controller, security route 순서, OpenAPI, API test

**필요한 스킬:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `test-driven-development`

**파일:**
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/TenantSchedulingPolicyController.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/ClinicSchedulingPolicyController.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyPreviewJobController.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingPolicyRequests.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingPolicyResponses.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CatalogPayloadSizeFilter.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/TenantSchedulingPolicyControllerTest.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/ClinicSchedulingPolicyControllerTest.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyPreviewJobControllerTest.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyOpenApiTest.kt`
- 생성: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/SchedulingPolicySecurityIntegrationTest.kt`

다음 정확한 route set을 구현하고 test한다.

| 동작 | Tenant route | Clinic route |
|---|---|---|
| draft | `POST /api/{tenantCode}/admin/scheduling-policies/drafts` | `POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/drafts` |
| validate | `POST /api/{tenantCode}/admin/scheduling-policies/{id}/validate` | `POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/validate` |
| preview | `POST /api/{tenantCode}/admin/scheduling-policies/{id}/preview` | `POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/preview` |
| preview job | `GET /api/{tenantCode}/admin/scheduling-policies/preview-jobs/{jobId}` | `GET /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/preview-jobs/{jobId}` |
| approve | `POST /api/{tenantCode}/admin/scheduling-policies/{id}/approve` | `POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/approve` |
| schedule | `POST /api/{tenantCode}/admin/scheduling-policies/{id}/schedule` | `POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/schedule` |
| activate | `POST /api/{tenantCode}/admin/scheduling-policies/{id}/activate` | `POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/activate` |
| retire | `POST /api/{tenantCode}/admin/scheduling-policies/{id}/retire` | `POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/retire` |
| replay | `POST /api/{tenantCode}/admin/scheduling-policies/activation-commands/{commandId}/replay` | `POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/activation-commands/{commandId}/replay` |
| effective | `GET /api/{tenantCode}/admin/scheduling-policies/effective` | `GET /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/effective` |

request field는 명시적이다. draft는 `kind`, `schemaVersion`, `effectiveFrom`,
`effectiveUntil`, `payload`, `expectedScopeRevision`, `changeReason`; validate uses
`expectedDraftRevision`; preview uses `expectedDraftRevision`, `expectedGeneration`;
approve uses `expectedDraftRevision`, `previewEvidenceToken`, `changeReason`; schedule
and activate use `expectedDraftRevision`, `expectedActiveRevision`,
`expectedGeneration`, `previewEvidenceToken`, `changeReason` (schedule also requires
`effectiveFrom`); retire uses `expectedActiveRevision`, `expectedGeneration`,
`changeReason`; replay uses `expectedGeneration`, `changeReason`.

response는 해당하는 경우 `definitionId`, `draftRevision`, `lifecycle`, `generation`,
`snapshotHash`, and `correlationId` as applicable. Preview adds `jobId`, `status`,
`pinnedRevision`, `pinnedGeneration`, `progress`, `resultHash`,
`activationEvidenceToken`, and `errorCode`; only `COMPLETED` contains the token.

두 effective route는 UTC 또는 명시적인 offset이 있는 RFC 3339 timestamp query
parameter `decisionAt`, `serviceAt`를 요구한다.

```text
GET /api/{tenantCode}/admin/scheduling-policies/effective?decisionAt=2026-07-27T08:00:00Z&serviceAt=2026-08-15T09:00:00+09:00
GET /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/effective?decisionAt=2026-07-27T08:00:00Z&serviceAt=2026-08-15T09:00:00+09:00
```

server-now default는 없다. offset 없는 local date-time, 누락/잘못된 value,
`serviceAt < decisionAt`은 `400 POLICY_PAYLOAD_INVALID`를 반환한다. controller는
둘 다 `Instant`로 normalize하여 DST gap/overlap ambiguity를 제거한다. effective
response는 normalized `decisionAt`, `serviceAt`, tenant/clinic generation,
`snapshotHash`를 다시 반환한다.

- [ ] **단계 1: RED API contract test 작성**

draft, validate, preview `200|202`, job polling, approve, schedule, activate, retire,
replay, effective read의 tenant 및 clinic route를 모두 다룬다. 다음을 assertion한다.
`Idempotency-Key`, expected revision/generation, preview evidence, and change reason.
`PENDING|RUNNING` polling에서는 scoped primary-key lookup, 설정된
`Retry-After`, and per-tenant/job polling throttling; repeated reads must not trigger an
impact rescan or unbounded DB queries.

request DTO에는 actor type, role, tenant ID, clinic ID, patient subject, booking origin이
없다. unknown escalation field는 `POLICY_PAYLOAD_INVALID`로 거부한다.

- [ ] **단계 2: RED scope 및 error test 작성**

cross-tenant/clinic policy와 job ID는
`404 POLICY_RESOURCE_NOT_FOUND`. Missing/insufficient approval returns 422. Stale
draft/preview/generation, interval conflict, idempotency conflict, missed activation, and
preview saturation map exactly to the approved stable codes, `retryable`, action,
correlation ID, and `Retry-After` where applicable.

- [ ] **단계 3: thin controller 구현**

controller는 path scope와 `ActorContext`를 resolve하고 strict DTO를 매핑한 뒤
delegate한다. transaction을 열거나 request body에서 actor identity를 추론하지
않는다. 구체적인 policy matcher를 `/api/{tenantCode}/admin/**` 및 broad write
matcher보다 앞에 둔다.

- [ ] **단계 4: async response 고정**

```text
202 Accepted
Location (tenant): /api/{tenantCode}/admin/scheduling-policies/preview-jobs/{jobId}
Location (clinic): /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/preview-jobs/{jobId}
Retry-After: <seconds>
body: jobId, status=PENDING, pinned revision/generation
```

polling은 `PENDING|RUNNING|COMPLETED|STALE|FAILED|CANCELLED`, cursor/progress,
result hash를 반환하며 token은 `COMPLETED`일 때만 반환한다.

- [ ] **단계 5: 생성된 OpenAPI contract 검증**

`SchedulingPolicyOpenApiTest`는 MockMvc로 `/v3/api-docs`를 가져와 diagnostic
artifact를 다음 경로에 기록한다.
`appointment-api/build/reports/openapi/scheduling-policy.json`. tenant 및 clinic route
전체, request/response DTO schema, `SchedulingApiErrorResponse`, `date-time` 형식의
필수 effective-read `decisionAt`/`serviceAt` query parameter, `Location`과
`Retry-After`, 모든 승인된 status/code/retryable mapping을 assertion한다. actor,
role, tenant, clinic, patient, booking-origin, JWT, raw idempotency-key field는
포함되지 않아야 한다.

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.controller.SchedulingPolicyOpenApiTest"
```

- [ ] **단계 6: GREEN 및 legacy regression 실행**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.controller.*SchedulingPolicy*Test" \
  --tests "io.bluetape4k.clinic.appointment.api.security.SchedulingPolicySecurityIntegrationTest" \
  --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentControllerTest" \
  --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentPlanControllerTest"
```

- [ ] **단계 7: 작업 9 commit**

API, stable error, OpenAPI annotation, route test를 commit한다.

**Rollback/rerun:** 모든 policy flag는 기본값이 off다. 기존 appointment endpoint는
동작과 security 순서를 유지한다.

---

### 작업 10: dialect·concurrency·performance contract 증명 및 documentation 완료

**복잡도:** XL

**선행 조건:** 작업 1–9

**수정 범위:** integration test, docs, README parity, runbook, 최종 verification

**필요한 스킬:** `bluetape-kotlin-patterns`, `verification-before-completion`, `bluetape-writer`

**파일:**
- 생성: `SchedulingPolicyDialectIntegrationTest.kt`
- 생성: `SchedulingPolicyPerformanceIntegrationTest.kt`
- 수정: evidence가 필요한 경우에만 migration test와 shared `Containers.kt`
- 생성: `docs/api/scheduling-policy.md`
- 생성: `docs/runbooks/scheduling-policy-activation.md`
- 수정: `docs/requirements/architecture.md`
- 수정: `docs/requirements/domain-model.md`
- 수정: `docs/requirements/data-flow.md`
- 수정: `README.md`, `README.ko.md`
- 수정: `docs/superpowers/INDEX.md`, `docs/superpowers/index/2026-07.md`

- [ ] **단계 1: H2 integration 및 concurrency proof 실행**

```bash
./gradlew :appointment-core:test
./gradlew :appointment-event:test
./gradlew :appointment-api:test
```

예상 결과: 영향받은 module test가 모두 `PASS`한다. retry만으로 통과하는 concurrency
결과가 있으면 조사한다. activation과 lease race에는 barrier/latch로 시작한 contender,
bounded join/timeout, fake DB/monotonic clock, `Thread.sleep` 없는 assertion, 작은
반복 race loop를 사용한다. timeout, worker leak, retry만으로 얻은 성공이 하나라도
있으면 이 단계는 실패한다.

- [ ] **단계 2: PostgreSQL 및 MySQL 순차 실행**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.integration.SchedulingPolicyDialectIntegrationTest" \
  -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.integration.SchedulingPolicyDialectIntegrationTest" \
  -Dspring.profiles.active=test,test-mysql
```

activation winner 하나, overlap conflict parity, idempotent replay, immutable
snapshot, effective lookup, preview lease, generic outbox insert/backfill을 assertion한다.

- [ ] **단계 3: 3개 dialect bounded performance/stability proof 실행**

`SchedulingPolicyPerformanceIntegrationTest`를 H2, PostgreSQL, MySQL에서 순차 실행한다.

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.integration.SchedulingPolicyPerformanceIntegrationTest" \
  -Dspring.profiles.active=test
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.integration.SchedulingPolicyPerformanceIntegrationTest" \
  -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.integration.SchedulingPolicyPerformanceIntegrationTest" \
  -Dspring.profiles.active=test,test-mysql
```

세 실행에서 같은 10,000-row future-work fixture와 fixed clock을 사용한다. preview
keyset scan, activation due selection, overlap lookup, outbox backlog의 query count와
dialect `EXPLAIN` 또는 동등한 plan evidence를 수집한다. 의도한 index가 없거나 고정
cardinality에서 full-table scan이 나타나면 실패한다.

cold/warm compile latency, cache hit/eviction, preview cutoff, maximum materialized row,
same-scope due burst, worker recovery를 기록한다. cold/warm compile은 고정한
definition/snapshot fixture를 사용하고, 성공 attempt마다 head read를 최대 2회,
generation retry를 최대 3회 수행하며 관련 없는 tenant row로 query count가 증가하지
않아야 한다. hard gate는 unbounded scan/materialization 없음, async conversion 없이
deadline/SLO 초과 없음, stale cache 반환 없음, `maxActivationClaimsPerTick` 또는
`maxPreviewJobsPerTick`을 초과하는 batch 없음이다. machine별 microbenchmark 순위가
아니다.

- [ ] **단계 4: durable caller 및 operator documentation 작성**

`docs/api/scheduling-policy.md` includes actor-derived fields, admin/customer booking
policy examples, `200|202` polling, idempotency, stable errors, and feature flags.
`docs/runbooks/scheduling-policy-activation.md` includes:

```text
60s lateness warning → inspect lease/DB clock/head conflict
5m/deadline critical → command MISSED, prior active preserved
recovery → create manual replay or retire incompatible draft
forbidden → direct DB edits or terminal-row rewrites
```

runbook은 승인된 모든 alert에 default threshold, 첫 diagnostic metric/query, owner
action, degradation/escalation rule, recovery path도 고정한다.
outbox oldest-pending/failed, preview deadline/stale ratio, activation conflict ratio,
authoritative generation-read failure, aggregate-null count, and dual-write parity.
향후 V10 전에 operator는 aggregate-null verification query를 실행하고 legacy/new
column parity를 확인하며 선언한 window 동안 모든 writer version의 dual-write를
관찰해야 한다.

requirements는 transaction/lock ownership, generation double-read, cache fail-closed,
V9/V10 deployment 분리, confirmed change에 새 consent가 필요한 이유를 설명한다.

off-by-default flag는 다음과 같다.

```text
scheduling.policy.shadow-compile-enabled
scheduling.policy.effective-read-enabled
scheduling.policy.admin-write-enabled
scheduling.policy.preview-worker-enabled
scheduling.policy.scheduled-activation-enabled
```

`SchedulingPolicyPropertiesTest`는 모두 기본값이 false인지 증명하고 잘못된 dependency
order를 거부한다. operator enablement 순서는 반드시 shadow compile → effective read
→ admin write → preview worker → scheduled activation이다. API docs, runbook, 두
README가 같은 checklist를 담는다. 이 foundation PR에는 booking-consumer flag를
추가하지 않는다.

- [ ] **단계 5: KDoc 및 locale parity 검증**

public type/service에는 책임, caller, transaction ownership, invariant, rejection
reason을 설명하는 English KDoc가 있다. README English/Korean heading과 example은
구조적으로 동등하게 유지한다. 이 feature에는 SVG/PNG를 추가하지 않으며 승인된
HTML 동반 문서가 시각적 reading surface를 제공한다.

- [ ] **단계 6: static 및 documentation check 실행**

```bash
./gradlew :appointment-core:build
./gradlew :appointment-event:build
./gradlew :appointment-api:build
git diff --check
rg -n "TODO|TBD|FIXME|System\\.out|System\\.err|println\\(" \
  appointment-core appointment-event appointment-api docs README.md README.ko.md
```

예상 결과: build와 diff check가 `PASS`하고 변경한 production path에 새 placeholder나
console logging이 scan되지 않는다.

- [ ] **단계 7: 작업 10 commit**

test, requirements, API guide, runbook, README parity, index update를 fresh
verification evidence와 함께 Lore trailer 형식으로 commit한다.

**Rollback/rerun:** documentation은 code rollback을 따른다. operator가 shadow
compile, effective read, admin write, preview worker, scheduled activation을 이 순서로
명시적으로 활성화할 때까지 policy flag는 off 상태로 유지한다.

---

## 4. Step 3-P 위험 예측 및 중단 지점

최종 계획 승인 뒤 production code에 들어가기 전에 아래 위험을 각 작업의 RED/GREEN
증거와 rollback 지점에 연결했다. 모든 위험에 조기 신호, 예방 증거, 중단 조건,
재실행 지점이 있으므로 Step 3-P 판정은 **PASS**다. 해당 작업에서 약속한 증거를
만들지 못하면 다음 작업으로 넘어가지 않는다.

| 위험 | 작업 | 조기 신호 | 완화책 및 증거 | 중단·rollback·rerun |
|---|---|---|---|---|
| safety field가 `Disable`되거나 clinic override가 hard ceiling을 완화 | 1 | required path가 unresolved이거나 clinic 값이 tenant/platform 상한 초과 | required-field `Disable` RED test, typed override validation, source-per-path compile proof | Task 1 중단; contract/compiler 변경을 되돌리고 세 policy unit test 재실행 |
| map/set/Jackson 표현에 따른 snapshot hash 비결정성 | 1, 7 | 입력 순서를 섞었을 때 hash가 둘 이상 | sorted map/set, explicit null, semantic list order, 1,000 permutation 단일 hash | snapshot persistence 금지; Task 1 hash/compiler test부터 재실행 |
| kind/scope/schema가 다른 payload를 잘못 decode | 1 | open polymorphic type 또는 kind/payload mismatch가 통과 | explicit `(kind, scope, schemaVersion)` dispatch, unknown-field·256 KiB 거절 | codec 배포 중단; Task 1 validator/hash test 재실행 |
| mixed tenant/clinic generations | 2, 7 | double-read mismatch 또는 snapshot CAS 실패 | bounded retry, expected-vector insert, immutable vector test | effective read 중단; 혼합 snapshot 저장 금지; Task 7 service/cache test 재실행 |
| cross-dialect null uniqueness drift | 2, 3, 10 | duplicate tenant-scope row 또는 dialect별 constraint 차이 | non-null `clinic_scope_key` sentinel, H2/PostgreSQL/MySQL migration test | V9 중단; migration rollback 후 Task 3/10 dialect matrix 재실행 |
| rolling outbox incompatibility | 3, 4, 10 | aggregate 열 null, legacy/new writer parity 불일치 | V9 nullable expand/backfill, 모든 writer dual-write, aggregate-null/parity metric | policy writes off, V10 금지; Task 3/4 integration과 Task 10 query 재실행 |
| activation duplicate 또는 generation/event split | 2, 4, 6 | 두 active interval, generation만 증가, outbox 누락 | scope-head lock 아래 한 Exposed transaction, idempotency fingerprint, rollback injection test | 전체 transaction rollback, 기존 active 유지; Task 6 concurrency test 재실행 |
| authoritative generation 확인 실패 뒤 stale cache 반환 | 7 | cache vector와 DB head 불일치 또는 DB read failure | 모든 cache lookup 전 DB vector 확인, double-read, fail-closed test | effective read/cache bypass; Task 7 cache/service test 재실행 |
| preview/worker가 DB·메모리·queue를 독점하거나 lease를 잃음 | 8 | 10,000 row·2초 초과, queue >100, expired claim, tick batch 초과 | keyset 5,000, async 전환, tenant concurrency 2, bounded claims, lease reclaim/startup catch-up | worker flag off, durable row 보존; Task 8 fake-clock/lease test 재실행 |
| JWT 또는 request body로 tenant/clinic/actor 권한 상승 | 5, 9 | claim 충돌, 잘못된 issuer/audience, actor/scope body field 수용 | strict Gateway JWT, immutable `ActorContext`, escalation field reject, sanitized errors | 인증/command 거절, admin API off; Task 5/9 security test 재실행 |
| 운영 flag 순서·경보·문서가 실제 invariant와 불일치 | 8, 9, 10 | 선행 flag 없이 후행 flag enable, OpenAPI/runbook/README parity 차이 | dependency-order property test, stable error/OpenAPI test, alert/runbook checklist | pre-PR gate 중단; Task 8 properties와 Task 9/10 docs checks 재실행 |

## 5. repository 위험 결정

- module을 추가·이동·이름 변경하지 않으며 settings/BOM/Kover module registration은
  N/A다.
- 새 runtime dependency(`spring-boot-starter-actuator`)와 Jackson 3 core codec 사용은
  기존 version/catalog와 영향받은 build를 통해 검증한다.
- HTTP와 Testcontainers 범위가 적용된다. shared singleton launcher를 재사용하고
  PostgreSQL/MySQL command는 순차 실행한다.
- backend 동작 범위는 하나의 logical feature지만 다음 순서의 commit으로 review
  가능하게 유지한다: pure contract → persistence → migration → event → security →
  command → cache → worker → API → cross-dialect/docs.
- 새 diagram asset은 필요하지 않다. README/requirements prose와 HTML 동반 문서가
  생성된 SVG/PNG 없이 설명을 제공한다.

## 6. Step 3-R review contract

정확한 Markdown plan과 승인된 spec은 다음 6개의 독립적인 read-only 관점으로
검토해야 한다.

1. performance;
2. stability;
3. security;
4. operator/Ops;
5. developer/API;
6. user/caller.

main session은 중복 finding을 통합하고 모든 acceptance row를 확인하며 task 순서와
정확한 command를 검증하고 placeholder/type consistency scan을 수행한다. 최신 통합
표가 `P0=0`, `P1=0`이고 사용자가 이 plan을 승인할 때까지 구현은 blocked 상태다.
승인 후 작업 1을 시작하기 전에 spec과 plan을 commit한다.

| 관점 | 초기 blocker | 보정 증거 | 최종 |
|---|---|---|---|
| 성능 | 3개 dialect performance/index proof 부재 | H2/PostgreSQL/MySQL fixed-fixture query-count + EXPLAIN gate | P0=0, P1=0 |
| 안정성 | 없음 | terminal cleanup 및 deterministic sleep-free race test 추가 | P0=0, P1=0 |
| 보안 | raw idempotency key 보존과 nullable `jti` 모호성 | keyed hash만 사용, `jti` 필수화, auth failure 정제 | P0=0, P1=0 |
| 운영/Ops | outbox parity/V10 evidence 부재 | aggregate-null query, parity metric, alert/runbook/flag 순서 | P0=0, P1=0 |
| 개발/API | 서로 다른 `ActorContext` | canonical type, 정확한 API/error/OpenAPI/impact primitive | P0=0, P1=0 |
| 사용자/caller | effective-read time contract 부재 | offset이 있는 RFC 3339 time과 code별 retryability 필수화 | P0=0, P1=0 |
| 통합 | V9/V10 wording과 exact-path drift | rolling-safe V9, 별도 V10, placeholder/type/path parity scan | P0=0, P1=0 |
