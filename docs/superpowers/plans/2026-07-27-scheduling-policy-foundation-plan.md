# Scheduling Policy Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 최종 사용자 구현 계획 승인 완료, Step 3-R P0=0/P1=0, Step 3-P PASS. Task 1 착수 준비, 생산 코드 미착수.

**Goal:** 병원별 예약 정책을 typed·versioned aggregate로 관리하고, Gateway 인증 주체·승인·CAS·세대·outbox를 근거로 미래 예약 판단에 사용할 불변 `EffectiveSchedulingPolicy` snapshot을 생성한다.

**Architecture:** `appointment-core`가 policy contract, validator, compiler, Exposed table/repository와 순수 hash 규칙을 소유한다. `appointment-api`가 Gateway JWT를 불변 `ActorContext`로 변환하고 draft/preview/approval/activation/effective-read use case와 bounded worker를 제공하며, `appointment-event`의 기존 outbox를 aggregate-neutral envelope로 확장한다. 모든 예약 consumer는 feature flag가 꺼진 상태로 유지되어 기존 예약 생성 동작은 바뀌지 않는다.

**Tech Stack:** Kotlin 2.3 language/API, Java 25 runtime contract, Spring Boot 4 MVC/Security/Scheduling/Actuator, Exposed JDBC, Flyway H2/PostgreSQL/MySQL, Jackson 3, JJWT 0.13.0, JUnit 5, bluetape4k assertions/concurrency/Testcontainers helpers.

**Source Design:** [`2026-07-27-scheduling-policy-foundation-design.md`](../specs/2026-07-27-scheduling-policy-foundation-design.md)

**Readable Companion:** [`2026-07-27-scheduling-policy-foundation-plan.html`](./2026-07-27-scheduling-policy-foundation-plan.html)

---

## 1. Delivery boundary

### Included

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

### Explicitly excluded

| Excluded behavior | Reason |
|---|---|
| 실제 `PROVISIONAL`, `HELD`, `CONFIRMED` 예약 state machine | visit/commitment aggregate가 policy snapshot을 소비하는 후속 issue의 책임 |
| 실제 capacity allocation, overbooking, waitlist, reliability score | 이번 변경은 policy 정의·compile·snapshot까지만 제공 |
| 고객 동의 생성·전자서명·환불·결제 | 외부 commerce/consent 서비스 경계 |
| broker outbox relay와 Pub/Sub transport | 현재 HTTP/admin foundation과 atomic outbox enqueue만 구현 |
| V10 outbox `aggregate_type/aggregate_id NOT NULL` cutover | 모든 writer의 dual-write와 null row 0건 운영 증거 후 별도 배포 |
| frontend 관리 화면 | issue #182 out of scope |

### PR delivery authority

이 계획의 승인 범위는 `bluetape4k/clinic-appointment`, base
`develop`, head `feature/issue-182-scheduling-policy-foundation`의 구현·검증·PR
생성까지다. merge는 CI와 live review가 끝난 merge-ready 보고 후 별도 승인을
받는다.

## 2. File structure

### `appointment-core`

| Path | Responsibility |
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

| Path | Responsibility |
|---|---|
| `event/integration/SchedulingOutboxEvents.kt` | aggregate-neutral outbox columns와 nullable legacy plan reference |
| `event/integration/SchedulingEventRepository.kt` | plan event dual-write 유지 |
| `event/policy/SchedulingPolicyActivatedEvent.kt` | redacted policy activation event contract |
| `event/policy/SchedulingPolicyEventRepository.kt` | activation transaction 안의 deterministic outbox insert |

### `appointment-api`

| Path | Responsibility |
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

### Schema, tests, and documentation

| Path | Responsibility |
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

## 3. Acceptance traceability

| Acceptance criterion | Plan task and proof |
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

### Task 1: Lock typed policy contracts, validation, and deterministic compilation

**Complexity:** XL

**Depends on:** approved design

**Write scope:** new `appointment-core/model/policy`, pure services, and unit tests

**Required skills:** `bluetape-kotlin-patterns`, `test-driven-development`

**Files:**
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/SchedulingPolicyContract.kt`
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/PolicyOverride.kt`
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/BookingCommitmentPolicy.kt`
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/CapacityAndReliabilityPolicies.kt`
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/OperationalSchedulingPolicies.kt`
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/EffectiveSchedulingPolicy.kt`
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyPayloadCodec.kt`
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyValidator.kt`
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyHasher.kt`
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyCompiler.kt`
- Modify `appointment-core/build.gradle.kts`
- Test `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyValidatorTest.kt`
- Test `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyCompilerTest.kt`
- Test `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyHashTest.kt`

- [ ] **Step 1: Write RED contract and invariant tests**

Pin the common envelope and generation contract:

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

Tests reject tenant scope with a clinic, clinic scope without a clinic, version/revision
below one, an empty reason, unknown schema version, invalid effective interval,
kind/payload mismatch, required-field `DISABLE`, and a clinic value that relaxes a
tenant/platform hard ceiling.

- [ ] **Step 2: Write RED booking-origin and TTL tests**

Create the exact booking contract from the approved spec and prove:

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

`provisionalRequestTtl` accepts only `5m..7d`; `HARD_HOLD` requires
`resourceHoldTtl` in `1m..30m` and not longer than request TTL; `NO_HOLD` and
`SOFT_HOLD` forbid it. `confirmedChangeMode` cannot be disabled or weakened.

- [ ] **Step 3: Define all eight tenant and clinic payload pairs**

Use sealed, serializable payloads with schema version 1:

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

Define equivalent explicit contracts for hold/consent, priority/reliability,
reconfirmation, disruption recovery, operating extension, and notification/SLA.
Safety fields such as absolute limits, confirmed-change consent, legal/safety
extension ceilings, and mandatory SLA bounds are not disableable.

- [ ] **Step 4: Run RED**

```bash
./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.policy.SchedulingPolicyValidatorTest" \
  --tests "io.bluetape4k.clinic.appointment.policy.SchedulingPolicyCompilerTest" \
  --tests "io.bluetape4k.clinic.appointment.policy.SchedulingPolicyHashTest"
```

Expected: compilation fails because the policy contracts do not exist.

- [ ] **Step 5: Implement strict codec and canonical hashes**

Add Jackson 3 Kotlin dependencies to `appointment-core`. Decode by explicit
`(kind, scope, schemaVersion)` dispatch; do not enable open polymorphic
deserialization. Reject unknown fields and enforce a 256 KiB UTF-8 payload limit
before decoding or hashing.

`SchedulingPolicyHasher` writes schema version, named fields, explicit null markers,
sorted maps/sets, and semantic list order into SHA-256. Actor audit timestamps and
database IDs are excluded from payload hash; source versions, generation, evaluation
times, compiled payload, warnings, and disabled paths are included in snapshot hash.

- [ ] **Step 6: Implement deterministic compiler**

The merge order is platform defaults → active tenant default → active clinic override.
Every output path records `PLATFORM`, `TENANT`, or `CLINIC` source. Maps and sets are
sorted before hash input. A clinic override cannot raise capacity/extension/retry
ceilings above the stricter tenant/platform value.

- [ ] **Step 7: Run GREEN and determinism stress**

```bash
./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.policy.*"
```

Expected: PASS; 1,000 shuffled map/set input permutations yield one snapshot hash.

- [ ] **Step 8: Commit Task 1**

Commit only Task 1 paths with the Lore protocol. The intent line explains why policy
semantics must be deterministic before persistence.

**Rollback/rerun:** no schema exists; revert the commit and rerun the policy unit package.

---

### Task 2: Persist definitions, approvals, heads, snapshots, and jobs

**Complexity:** XL

**Depends on:** Task 1

**Write scope:** `appointment-core` Exposed tables, records, repositories, H2 tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**Files:**
- Create the six table files and three repository files listed in section 2
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/SchedulingPolicyRecords.kt`
- Modify `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/RecordMappers.kt`
- Modify `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`
- Test `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/SchedulingPolicyRepositoryTest.kt`
- Test `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/SchedulingPolicyJobRepositoryTest.kt`

- [ ] **Step 1: Write RED repository tests**

Prove unique definition identity, `clinic_scope_key=0` tenant sentinel,
revision-scoped approvals, immutable snapshot hash, monotonic generation, activation
keyed idempotency hash+fingerprint, and preview/activation lease reclaim. Bound the
incoming key length/character set, store only `idempotencyKeyHash`, and prove raw keys
never appear in rows, logs, or responses.

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

The non-null `clinic_scope_key` prevents PostgreSQL/MySQL/H2 differences for unique
constraints containing nullable clinic IDs.

- [ ] **Step 2: Run RED**

```bash
./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepositoryTest" \
  --tests "io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepositoryTest"
```

- [ ] **Step 3: Implement caller-transaction repositories**

Public KDoc states transaction ownership. Every write executes inside a caller-owned
`transaction {}`. Use top-level Exposed operators, named local values inside DSL
receivers, `insertIgnore` for scope-head bootstrap, and `forUpdate()` only after
acquiring heads in tenant-before-clinic order.

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

- [ ] **Step 4: Implement lease and checkpoint primitives**

Claims update only rows whose state and lease are eligible. Completion verifies live
`leaseOwner`; stale workers cannot update terminal state.

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

- [ ] **Step 5: Run GREEN**

```bash
./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.repository.SchedulingPolicy*Test" \
  --tests "io.bluetape4k.clinic.appointment.model.tables.TableSchemaTest"
```

- [ ] **Step 6: Commit Task 2**

Commit tables, records, repositories, mappers, and tests together.

**Rollback/rerun:** before V9 deployment the commit is revertible. After V9, keep
additive tables and disable every policy write flag.

---

### Task 3: Add three-dialect V9 and rolling-safe outbox expansion

**Complexity:** XL

**Depends on:** Task 2

**Write scope:** Flyway V9, schema registration, migration tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**Files:**
- Create three `V9__add_scheduling_policy_foundation.sql` files
- Modify `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt`
- Modify H2/PostgreSQL/MySQL Flyway migration tests
- Modify `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/AppointmentPlanMigrationTestSupport.kt`

- [ ] **Step 1: Write RED migration assertions**

Assert all six policy tables, named indexes/FKs/checks, existing V8 rows preserved,
and outbox compatibility. Seed a V8 plan outbox row, migrate to V9, and prove:

```text
aggregate_type = APPOINTMENT_PLAN
aggregate_id = CAST(plan_id AS text)
plan_id remains populated
status/next_attempt_at and status/created_at indexes remain usable
```

- [ ] **Step 2: Run H2 RED**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest"
```

- [ ] **Step 3: Implement V9 expand/backfill**

V9 adds nullable `aggregate_type` and `aggregate_id`, backfills idempotently, makes
legacy `plan_id` nullable, and adds an aggregate lookup index. It does not add the
future V10 `NOT NULL` constraint. Policy event writes stay disabled by default until
the new application is deployed.

Migration comments explain dialect syntax and the shared logical invariant. Do not
edit V8 checksums or rename `scheduling_*` tables.

- [ ] **Step 4: Run the sequential dialect matrix**

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

Expected: all PASS, using existing singleton launchers without `@Testcontainers`.

- [ ] **Step 5: Commit Task 3**

Commit migration and schema parity tests together.

**Rollback/rerun:** V9 is forward-only. Disable policy writes; never drop or rewrite
history. V10 is authorized only by a separate deployment checklist proving all writers
dual-write and
`SELECT COUNT(*) FROM scheduling_outbox_events WHERE aggregate_type IS NULL OR aggregate_id IS NULL`
returns zero.

---

### Task 4: Generalize the outbox and publish one activation event atomically

**Complexity:** L

**Depends on:** Task 3

**Write scope:** `appointment-event` table/repository/event and tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**Files:**
- Modify `SchedulingOutboxEvents.kt`
- Modify `SchedulingEventRepository.kt`
- Create `SchedulingPolicyActivatedEvent.kt`
- Create `SchedulingPolicyEventRepository.kt`
- Modify `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/PurchaseCompletedHandlerTest.kt`
- Create `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/policy/SchedulingPolicyEventRepositoryTest.kt`

- [ ] **Step 1: Write RED dual-write tests**

Existing `AppointmentPlanCreated` rows must write:

```kotlin
aggregateType = "APPOINTMENT_PLAN"
aggregateId = planId.toString()
legacyPlanId = planId
```

Tests also expose an operator verification query that counts
`aggregate_type IS NULL OR aggregate_id IS NULL`, compares legacy plan rows with
`APPOINTMENT_PLAN` aggregate values, and reports a dual-write parity gauge. The expected
count is zero after backfill/new-writer convergence; policy writes remain disabled when
the count or parity check is non-zero.

Policy activation writes `aggregateType=SCHEDULING_POLICY`, definition/version,
effective interval, generation vector, payload hash, redacted actor audit reference,
correlation ID, and no bearer token or policy payload.

- [ ] **Step 2: Run RED**

```bash
./gradlew :appointment-event:test \
  --tests "io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedHandlerTest" \
  --tests "io.bluetape4k.clinic.appointment.event.policy.SchedulingPolicyEventRepositoryTest"
```

- [ ] **Step 3: Implement deterministic event IDs and inserts**

```kotlin
fun insertPolicyActivated(
    definition: SchedulingPolicyDefinitionRecord,
    generation: PolicyGenerationVector,
    actor: ActorAuditRef,
    correlationId: String,
): String
```

Derive the event ID from definition ID, version, effectiveFrom, and result generation.
The activation service calls this method inside the same Exposed transaction that
changes lifecycle and head generation.

- [ ] **Step 4: Run GREEN and payload leak scan**

```bash
./gradlew :appointment-event:test
rg -n "bearer|patientReference|payloadJson.*SchedulingPolicyActivated" appointment-event/src
```

Expected: tests PASS; the scan finds no secret/PHI inclusion path.

- [ ] **Step 5: Commit Task 4**

Commit outbox compatibility and activation event as one atomic behavior change.

**Rollback/rerun:** policy event writing remains feature-disabled. Old plan event
dual-write is backward compatible with V9.

---

### Task 5: Harden Gateway authentication and derive immutable `ActorContext`

**Complexity:** XL

**Depends on:** Task 1

**Write scope:** API security/config/filter and security tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `test-driven-development`

**Files:**
- Modify `SchedulingUserPrincipal.kt`, `JwtSecurityProperties.kt`, `JwtTokenParser.kt`
- Create `ActorContextResolver.kt`, `CorrelationIdFilter.kt`
- Modify `SecurityConfig.kt`, `GlobalExceptionHandler.kt`
- Modify `SchedulingApiErrorResponse.kt`
- Modify `TestJwtProvider.kt`, `JwtTokenParserTest.kt`
- Create `ActorContextResolverTest.kt`, `SchedulingPolicySecurityIntegrationTest.kt`
- Modify `application.yml` and integration-test configuration

- [ ] **Step 1: Write RED JWT validation tests**

Require issuer, audience, `jti`, issued-at/auth-time, actor type, roles, allowed tenant
set, allowed clinic set, assurance, and optional patient subject. Reject missing
audience, wrong audience, HS384/HS512, expired/not-yet-valid tokens, excessive clock
skew, missing/blank `jti`, conflicting actor/role claims, and patient tokens without
their own subject. Malformed, expired, wrong-audience, and conflicting-claim cases also
assert that response/log output contains no raw token, claim value, or parser detail:
only a generic authentication/policy stable code and bounded correlation ID are exposed.

- [ ] **Step 2: Pin the JJWT 0.13 parser API**

Use the locally verified API:

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

No request body field participates in `ActorContext`.

- [ ] **Step 3: Implement principal and resolver**

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

`ActorContextResolver` verifies the path tenant and clinic again against the principal.
Audit persistence uses `ActorAuditRef`, not the full context or JWT.

- [ ] **Step 4: Add correlation propagation and stable errors**

Accept only bounded safe `X-Correlation-Id`; otherwise generate UUID. Store it in a
request attribute and response header. `SchedulingApiErrorResponse` adds
`retryable` and `action` with defaults that preserve existing constructor callers.

- [ ] **Step 5: Run GREEN**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest" \
  --tests "io.bluetape4k.clinic.appointment.api.security.ActorContextResolverTest" \
  --tests "io.bluetape4k.clinic.appointment.api.security.SchedulingPolicySecurityIntegrationTest"
```

- [ ] **Step 6: Commit Task 5**

Commit strict JWT parsing, actor context, error/correlation contract, and tests.

**Rollback/rerun:** policy endpoints remain disabled. Existing catalog/plan security
tests must remain green before continuing.

---

### Task 6: Implement draft, approval, schedule, activation, retirement, and replay

**Complexity:** XL

**Depends on:** Tasks 1, 2, 4, 5

**Write scope:** policy application service, configuration wiring, transaction tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, `test-driven-development`

**Files:**
- Create `SchedulingPolicyCommandService.kt`
- Create `SchedulingPolicyCommand.kt`
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/SchedulingPolicyErrorCode.kt`
- Create `SchedulingPolicyApiException.kt`
- Modify `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingApiErrorResponse.kt`
- Modify `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- Modify `ServiceConfig.kt`
- Test `SchedulingPolicyCommandServiceTest.kt`
- Test `SchedulingPolicyActivationConcurrencyTest.kt`

- [ ] **Step 1: Write RED lifecycle and authority tests**

Pin allowed transitions and stable rejection codes:

```text
DRAFT --schedule--> SCHEDULED --activate--> ACTIVE --retire--> RETIRED
DRAFT --activate--> ACTIVE
```

`SchedulingPolicyErrorCode` is the sole policy error registry:

```kotlin
enum class SchedulingPolicyErrorCode(
    val httpStatus: HttpStatus,
    val retryable: Boolean,
    val action: String,
)
```

`SchedulingPolicyApiException` carries one enum value and sanitized detail; it does not
replace the wire DTO. `GlobalExceptionHandler` maps it into the existing
`SchedulingApiErrorResponse(error, errorCode, correlationId, retryable, action)`.
OpenAPI exposes that response schema and the approved status/code pairs without stack,
JWT, claim, payload, or raw idempotency-key detail.

Only `POLICY_PREVIEW_LIMITED` has `retryable=true`; every other approved policy code is
false because payload, scope, stale revision/generation, approval, conflict, missed
activation, or idempotency intent must change before another command. Tests assert the
exact value for every enum constant and wire response.

Draft edits increment revision and stale prior approval/preview. Sensitive kinds require
distinct approval actors and separate activation authority. The creator/activator cannot
silently satisfy dual approval. `SYSTEM` requires service scope and scheduled command
evidence.

- [ ] **Step 2: Write RED transaction/concurrency tests**

Two concurrent activations against one scope/kind/boundary produce one `COMPLETED`
command, one active generation increment, and one outbox event. Same key hash/fingerprint
replays the original result; same key hash/different fingerprint returns
`POLICY_IDEMPOTENCY_CONFLICT`. An injected outbox failure rolls back lifecycle,
generation, command result, and snapshot.

- [ ] **Step 3: Implement short pre-transaction work**

Decode, validate, canonicalize, hash, bound the `Idempotency-Key`, and compute both its
keyed hash and command fingerprint before opening the transaction. Repository lookup and
conflict detection use `idempotencyKeyHash + commandFingerprint`; the raw header is never
persisted, logged, included in an event, or returned. The transaction performs exactly
the approved sequence:

```text
idempotency lookup → revision/preview/approval/authority checks
→ tenant head then clinic head lock → interval overlap check
→ previous active retire → target active → generation increment
→ command complete → outbox insert → commit
```

CAS/lock KDoc explains the protected invariant and tenant-before-clinic lock order.

- [ ] **Step 4: Implement schedule and manual replay**

Scheduling creates a deterministic `PENDING` activation command. Manual replay creates
a new command referencing the `MISSED` command; it never rewrites terminal rows.
Retirement never deletes definitions or snapshots.

- [ ] **Step 5: Run GREEN**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyCommandServiceTest" \
  --tests "io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyActivationConcurrencyTest"
```

- [ ] **Step 6: Commit Task 6**

Commit the lifecycle service and its transaction proof.

**Rollback/rerun:** turn off `admin-write-enabled` and `scheduled-activation-enabled`.
Forward repair uses explicit retire/replay; never edit active rows manually.

---

### Task 7: Compile immutable effective snapshots with authoritative cache checks

**Complexity:** XL

**Depends on:** Tasks 1, 2, 6

**Write scope:** compiler orchestration, cache, snapshot tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `test-driven-development`

**Files:**
- Create `EffectivePolicyCache.kt`
- Create `EffectiveSchedulingPolicyService.kt`
- Test `EffectivePolicyCacheTest.kt`
- Test `EffectiveSchedulingPolicyServiceTest.kt`

- [ ] **Step 1: Write RED generation-mixing tests**

Change a tenant or clinic head between the first and second read. The compiler discards
the mixed result and retries at most three times. Snapshot insert rechecks the expected
vector. Exhaustion returns a stable conflict; it never stores mixed generations.

- [ ] **Step 2: Write RED cache correctness tests**

Every effective read fetches the authoritative DB generation vector before cache lookup.
DB failure returns no stale cached snapshot. Old generation entries may remain physically
present but are never returned. Test boundary keys at `effectiveFrom`, `effectiveUntil`,
scheduled activation, emergency expiry, and DST gap/overlap.

- [ ] **Step 3: Implement bounded LRU**

Use `ReentrantLock`, not `synchronized`, around an access-order `LinkedHashMap`.
Enforce total entries, per-tenant entries, and estimated byte quota. Cache keys contain
tenant, clinic, tenant generation, clinic generation, decision boundary, and service
boundary. Eviction logs only low-cardinality scope/hash prefixes.

- [ ] **Step 4: Implement double-read compile and snapshot reuse**

```kotlin
fun getEffective(
    tenantGroupId: Long,
    clinicId: Long,
    decisionAt: Instant,
    serviceAt: Instant,
): EffectiveSchedulingPolicy
```

Read heads, load active definitions at their declared evaluation time, compile, reread
heads, and store by expected vector. Reuse an identical snapshot only inside the same
tenant/clinic scope.

- [ ] **Step 5: Run GREEN**

```bash
./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.policy.EffectivePolicyCacheTest"
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.policy.EffectiveSchedulingPolicyServiceTest"
```

- [ ] **Step 6: Commit Task 7**

Commit cache and effective read with correctness-before-optimization evidence.

**Rollback/rerun:** disable effective reads or bypass the cache while preserving immutable
snapshots. Cache/event invalidation is never the source of truth.

---

### Task 8: Add bounded preview and crash-safe scheduled workers

**Complexity:** XL

**Depends on:** Tasks 2, 6, 7

**Write scope:** impact repository, preview service, worker, properties, metrics, tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, `test-driven-development`

**Files:**
- Create `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/SchedulingPolicyImpactRepository.kt`
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/policy/SchedulingPolicyPreviewService.kt`
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/policy/SchedulingPolicyWorker.kt`
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/policy/SchedulingPolicyMetrics.kt`
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/SchedulingPolicyProperties.kt`
- Modify `appointment-api/build.gradle.kts` for actuator
- Test `SchedulingPolicyPreviewServiceTest.kt`
- Test `SchedulingPolicyWorkerTest.kt`
- Test `SchedulingPolicyPropertiesTest.kt`

- [ ] **Step 1: Write RED preview boundary tests**

With a fake monotonic clock and keyset fixtures, prove synchronous completion below the
limit, async conversion at row 10,000 or 2 seconds, partial result discard, 5,000-row
partition checkpoints, stale revision/generation termination, cancellation at chunk
boundaries, tenant concurrency 2, queue size 100, and `429` only when saturated. Process
partitions as a bounded stream: no worker may retain all 10,000 impacted rows, and tests
record the maximum materialized rows per partition.

`SchedulingPolicyPreviewService` invokes the core impact repository through
caller-owned `transaction {}` blocks. The core repository exposes only bounded keyset
query primitives and has no dependency on API DTOs, Spring MVC, or worker types.

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

`limit` is validated against the configured 5,000-row maximum. The service checks its
monotonic deadline between pages; repository SQL never performs an unbounded count or
returns full appointment/plan payloads.

- [ ] **Step 2: Define preview evidence**

Every preview, including synchronous completion, owns a persisted job row. Terminal
`COMPLETED` records result hash and activation evidence token. `STALE`, `FAILED`, and
`CANCELLED` never expose a token. RED tests prove every terminal transition releases the
tenant concurrency permit and claim/lease ownership, closes or makes cursor/checkpoint
resources inert, cannot be reclaimed, and cannot be mutated by runnable in-memory work
after worker shutdown.

- [ ] **Step 3: Write RED activation-runner recovery tests**

Prove DB-time due selection, deterministic key, one lease winner, reclaim after a dead
owner, exponential backoff+jitter, startup catch-up, 60-second lateness warning, 5-minute
or configured-deadline `MISSED`, prior active preservation, and same-scope multi-kind
bounded retry.

- [ ] **Step 4: Implement persisted workers**

Use `@EnableScheduling` in a narrow configuration and short scheduled scans. Worker
methods claim IDs in one transaction and process each claim separately so a failed policy
does not hold a batch lock. Use DB current time for due/lease decisions and monotonic time
for per-process preview deadline. Pin `maxActivationClaimsPerTick` and
`maxPreviewJobsPerTick`; test that one tick cannot monopolize DB/worker time and that an
activation burst cannot starve preview work. Shutdown stops new claims, waits a bounded
grace period, then leaves unfinished durable rows reclaimable without touching terminal
rows.

- [ ] **Step 5: Add metrics and low-cardinality logs**

Add Spring Boot Actuator. Record activation result/lateness/retry/missed, preview
sync/async/stale/deadline, compile cold/warm, cache hit/eviction/quota/stale rejection,
authoritative generation read failure, outbox pending/published/failed, oldest-pending
age, aggregate-null count, and dual-write parity. Tags contain result, kind, and scope
type—not tenant IDs, actor IDs, payloads, tokens, or correlation IDs.

- [ ] **Step 6: Run GREEN**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyPreviewServiceTest" \
  --tests "io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyWorkerTest" \
  --tests "io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyPropertiesTest"
```

- [ ] **Step 7: Commit Task 8**

Commit preview/runner/metrics/config and their deterministic clock/lease tests.

**Rollback/rerun:** disable the two worker flags independently. Pending jobs/commands stay
durable for later catch-up; never rewrite terminal rows.

---

### Task 9: Publish scoped admin/effective APIs and stable caller contracts

**Complexity:** XL

**Depends on:** Tasks 5, 6, 7, 8

**Write scope:** DTOs, controllers, security route ordering, OpenAPI, API tests

**Required skills:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `test-driven-development`

**Files:**
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/TenantSchedulingPolicyController.kt`
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/ClinicSchedulingPolicyController.kt`
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyPreviewJobController.kt`
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingPolicyRequests.kt`
- Create `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingPolicyResponses.kt`
- Modify `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- Modify `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- Modify `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- Modify `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CatalogPayloadSizeFilter.kt`
- Create `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/TenantSchedulingPolicyControllerTest.kt`
- Create `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/ClinicSchedulingPolicyControllerTest.kt`
- Create `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyPreviewJobControllerTest.kt`
- Create `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyOpenApiTest.kt`
- Create `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/SchedulingPolicySecurityIntegrationTest.kt`

Implement and test these exact route sets:

| Operation | Tenant route | Clinic route |
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

Request fields are explicit: draft uses `kind`, `schemaVersion`, `effectiveFrom`,
`effectiveUntil`, `payload`, `expectedScopeRevision`, `changeReason`; validate uses
`expectedDraftRevision`; preview uses `expectedDraftRevision`, `expectedGeneration`;
approve uses `expectedDraftRevision`, `previewEvidenceToken`, `changeReason`; schedule
and activate use `expectedDraftRevision`, `expectedActiveRevision`,
`expectedGeneration`, `previewEvidenceToken`, `changeReason` (schedule also requires
`effectiveFrom`); retire uses `expectedActiveRevision`, `expectedGeneration`,
`changeReason`; replay uses `expectedGeneration`, `changeReason`.

Responses use `definitionId`, `draftRevision`, `lifecycle`, `generation`,
`snapshotHash`, and `correlationId` as applicable. Preview adds `jobId`, `status`,
`pinnedRevision`, `pinnedGeneration`, `progress`, `resultHash`,
`activationEvidenceToken`, and `errorCode`; only `COMPLETED` contains the token.

Both effective routes require query parameters `decisionAt` and `serviceAt` as RFC 3339
timestamps with UTC or an explicit offset:

```text
GET /api/{tenantCode}/admin/scheduling-policies/effective?decisionAt=2026-07-27T08:00:00Z&serviceAt=2026-08-15T09:00:00+09:00
GET /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/effective?decisionAt=2026-07-27T08:00:00Z&serviceAt=2026-08-15T09:00:00+09:00
```

There is no server-now default. Local date-time without an offset, missing/invalid
values, and `serviceAt < decisionAt` return `400 POLICY_PAYLOAD_INVALID`. Controllers
normalize both to `Instant`, eliminating DST gap/overlap ambiguity. The effective
response echoes normalized `decisionAt`, `serviceAt`, tenant/clinic generation, and
`snapshotHash`.

- [ ] **Step 1: Write RED API contract tests**

Cover tenant and clinic routes for draft, validate, preview `200|202`, job polling,
approve, schedule, activate, retire, replay, and effective read. Assert
`Idempotency-Key`, expected revision/generation, preview evidence, and change reason.
For `PENDING|RUNNING` polling, assert a scoped primary-key lookup, configured
`Retry-After`, and per-tenant/job polling throttling; repeated reads must not trigger an
impact rescan or unbounded DB queries.

Request DTOs contain no actor type, role, tenant ID, clinic ID, patient subject, or booking
origin. Unknown escalation fields are rejected with `POLICY_PAYLOAD_INVALID`.

- [ ] **Step 2: Write RED scope and error tests**

Cross-tenant/clinic policy and job IDs return
`404 POLICY_RESOURCE_NOT_FOUND`. Missing/insufficient approval returns 422. Stale
draft/preview/generation, interval conflict, idempotency conflict, missed activation, and
preview saturation map exactly to the approved stable codes, `retryable`, action,
correlation ID, and `Retry-After` where applicable.

- [ ] **Step 3: Implement thin controllers**

Controllers resolve path scope and `ActorContext`, map strict DTOs, and delegate. They do
not open transactions or infer actor identity from request bodies. Put specific policy
matchers before `/api/{tenantCode}/admin/**` and broad write matchers.

- [ ] **Step 4: Pin the async response**

```text
202 Accepted
Location (tenant): /api/{tenantCode}/admin/scheduling-policies/preview-jobs/{jobId}
Location (clinic): /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/preview-jobs/{jobId}
Retry-After: <seconds>
body: jobId, status=PENDING, pinned revision/generation
```

Polling returns `PENDING|RUNNING|COMPLETED|STALE|FAILED|CANCELLED`, cursor/progress,
result hash, and token only for `COMPLETED`.

- [ ] **Step 5: Verify generated OpenAPI contract**

`SchedulingPolicyOpenApiTest` fetches `/v3/api-docs` through MockMvc and writes the
diagnostic artifact to
`appointment-api/build/reports/openapi/scheduling-policy.json`. It asserts every tenant
and clinic route above, request/response DTO schemas, `SchedulingApiErrorResponse`,
required effective-read `decisionAt`/`serviceAt` query parameters with `date-time`
format, `Location` and `Retry-After`, every approved status/code/retryable mapping, and
the absence of actor, role, tenant, clinic, patient, booking-origin, JWT, and raw
idempotency-key fields.

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.controller.SchedulingPolicyOpenApiTest"
```

- [ ] **Step 6: Run GREEN and legacy regression**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.controller.*SchedulingPolicy*Test" \
  --tests "io.bluetape4k.clinic.appointment.api.security.SchedulingPolicySecurityIntegrationTest" \
  --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentControllerTest" \
  --tests "io.bluetape4k.clinic.appointment.api.controller.AppointmentPlanControllerTest"
```

- [ ] **Step 7: Commit Task 9**

Commit API, stable errors, OpenAPI annotations, and route tests.

**Rollback/rerun:** all policy flags default off. Existing appointment endpoints retain
their behavior and security order.

---

### Task 10: Prove dialect/concurrency/performance contracts and finish documentation

**Complexity:** XL

**Depends on:** Tasks 1–9

**Write scope:** integration tests, docs, README parity, runbook, final verification

**Required skills:** `bluetape-kotlin-patterns`, `verification-before-completion`, `bluetape-writer`

**Files:**
- Create `SchedulingPolicyDialectIntegrationTest.kt`
- Create `SchedulingPolicyPerformanceIntegrationTest.kt`
- Modify migration tests and shared `Containers.kt` only if evidence requires it
- Create `docs/api/scheduling-policy.md`
- Create `docs/runbooks/scheduling-policy-activation.md`
- Modify `docs/requirements/architecture.md`
- Modify `docs/requirements/domain-model.md`
- Modify `docs/requirements/data-flow.md`
- Modify `README.md`, `README.ko.md`
- Modify `docs/superpowers/INDEX.md` and `docs/superpowers/index/2026-07.md`

- [ ] **Step 1: Run H2 integration and concurrency proof**

```bash
./gradlew :appointment-core:test
./gradlew :appointment-event:test
./gradlew :appointment-api:test
```

Expected: all affected module tests PASS. Investigate any retry-only concurrency pass.
Activation and lease races use barrier/latch-started contenders, bounded joins/timeouts,
fake DB/monotonic clocks, no `Thread.sleep` assertions, and a small repeated race loop.
Any timeout, leaked worker, or retry-only success fails this step.

- [ ] **Step 2: Run PostgreSQL and MySQL sequentially**

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.integration.SchedulingPolicyDialectIntegrationTest" \
  -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.integration.SchedulingPolicyDialectIntegrationTest" \
  -Dspring.profiles.active=test,test-mysql
```

Assert one activation winner, overlap conflict parity, idempotent replay, immutable
snapshot, effective lookup, preview lease, and generic outbox insert/backfill.

- [ ] **Step 3: Run three-dialect bounded performance/stability proof**

Run `SchedulingPolicyPerformanceIntegrationTest` sequentially on H2, PostgreSQL, and
MySQL:

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

Use the same 10,000-row future-work fixture and fixed clocks in all three runs. Capture
query counts plus dialect `EXPLAIN` or equivalent plan evidence for preview keyset scans,
activation due selection, overlap lookup, and outbox backlog; fail when the intended
index is absent or a full-table scan appears at the fixed cardinality.

Record cold/warm compile latency, cache hit/eviction, preview cutoff, maximum materialized
rows, same-scope due burst, and worker recovery. Cold/warm compile uses a pinned
definition/snapshot fixture, at most two head reads per successful attempt, at most three
generation retries, and no query count growth with unrelated tenant rows. The hard gate
is no unbounded scan or materialization, no deadline/SLO overrun without async conversion,
no stale cache return, and no batch above `maxActivationClaimsPerTick` or
`maxPreviewJobsPerTick`—not a machine-specific microbenchmark ranking.

- [ ] **Step 4: Write durable caller and operator documentation**

`docs/api/scheduling-policy.md` includes actor-derived fields, admin/customer booking
policy examples, `200|202` polling, idempotency, stable errors, and feature flags.
`docs/runbooks/scheduling-policy-activation.md` includes:

```text
60s lateness warning → inspect lease/DB clock/head conflict
5m/deadline critical → command MISSED, prior active preserved
recovery → create manual replay or retire incompatible draft
forbidden → direct DB edits or terminal-row rewrites
```

The runbook also pins every approved alert with default threshold, first diagnostic
metric/query, owner action, degradation/escalation rule, and recovery path:
outbox oldest-pending/failed, preview deadline/stale ratio, activation conflict ratio,
authoritative generation-read failure, aggregate-null count, and dual-write parity.
Before any future V10, operators must run the aggregate-null verification query, verify
legacy/new column parity, and observe every writer version dual-writing for the declared
window.

Requirements explain transaction/lock ownership, generation double-read, cache
fail-closed, V9/V10 deployment split, and why confirmed changes need new consent.

The exact off-by-default flags are:

```text
scheduling.policy.shadow-compile-enabled
scheduling.policy.effective-read-enabled
scheduling.policy.admin-write-enabled
scheduling.policy.preview-worker-enabled
scheduling.policy.scheduled-activation-enabled
```

`SchedulingPolicyPropertiesTest` proves all are false by default and rejects an invalid
dependency order. Operator enablement is strictly shadow compile → effective read →
admin write → preview worker → scheduled activation. API docs, runbook, and both READMEs
carry the same checklist. No booking-consumer flag is added in this foundation PR.

- [ ] **Step 5: Verify KDoc and locale parity**

Public types/services have English KDoc describing responsibility, caller, transaction
ownership, invariant, and rejection reason. README English/Korean headings and examples
remain structurally equivalent. No SVG/PNG is added for this feature; the approved HTML
companions provide the visual reading surface.

- [ ] **Step 6: Run static and documentation checks**

```bash
./gradlew :appointment-core:build
./gradlew :appointment-event:build
./gradlew :appointment-api:build
git diff --check
rg -n "TODO|TBD|FIXME|System\\.out|System\\.err|println\\(" \
  appointment-core appointment-event appointment-api docs README.md README.ko.md
```

Expected: builds and diff check PASS; scan has no introduced placeholder or console
logging in changed production paths.

- [ ] **Step 7: Commit Task 10**

Commit tests, requirements, API guide, runbook, README parity, and index updates with
fresh verification evidence in the Lore trailers.

**Rollback/rerun:** documentation follows code rollback. Policy flags remain off until
operators explicitly enable shadow compile, effective read, admin write, preview worker,
and scheduled activation in that order.

---

## 4. Step 3-P risk prediction and stop points

최종 계획 승인 뒤 생산 코드에 들어가기 전에 아래 위험을 각 Task의 RED/GREEN
증거와 rollback 지점에 연결했다. 모든 위험에 조기 신호, 예방 증거, 중단 조건,
재실행 지점이 있으므로 Step 3-P 판정은 **PASS**다. 해당 Task에서 약속한 증거를
만들지 못하면 다음 Task로 넘어가지 않는다.

| Risk | Task | Early signal | Mitigation and proof | Stop, rollback, and rerun |
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

## 5. Repository hazard decisions

- No module is added, moved, or renamed; settings/BOM/Kover module registration is N/A.
- A new runtime dependency (`spring-boot-starter-actuator`) and Jackson 3 core codec usage
  are verified through the existing version/catalog and affected builds.
- HTTP and Testcontainers scope is triggered. Shared singleton launchers are reused and
  PostgreSQL/MySQL commands run sequentially.
- Broad backend behavior is one logical feature but remains reviewable in ordered commits:
  pure contract → persistence → migration → event → security → commands → cache →
  workers → API → cross-dialect/docs.
- No new diagram asset is required. README/requirements prose and the HTML companions
  carry the explanation without generated SVG/PNG.

## 6. Step 3-R review contract

The exact Markdown plan and approved spec must receive six independent read-only
perspectives:

1. performance;
2. stability;
3. security;
4. operator/Ops;
5. developer/API;
6. user/caller.

The main session integrates duplicate findings, checks every acceptance row, validates
task ordering and exact commands, and performs the placeholder/type-consistency scan.
Implementation remains blocked until the latest integrated table is `P0=0`, `P1=0` and
the user approves this plan. After approval, the spec and plan are committed before
Task 1 begins.

| Perspective | Initial blocker | Corrective evidence | Final |
|---|---|---|---|
| Performance | three-dialect performance/index proof absent | H2/PostgreSQL/MySQL fixed-fixture query-count + EXPLAIN gate | P0=0, P1=0 |
| Stability | none | terminal cleanup and deterministic sleep-free race tests added | P0=0, P1=0 |
| Security | raw idempotency key retention and nullable `jti` ambiguity | keyed hash only, required `jti`, auth failure redaction | P0=0, P1=0 |
| Operator/Ops | outbox parity/V10 evidence absent | aggregate-null query, parity metrics, alert/runbook/flag order | P0=0, P1=0 |
| Developer/API | divergent `ActorContext` | canonical type, exact APIs/errors/OpenAPI/impact primitives | P0=0, P1=0 |
| User/caller | effective-read time contract absent | required offset RFC 3339 times and per-code retryability | P0=0, P1=0 |
| Main integration | V9/V10 wording and exact-path drift | rolling-safe V9, separate V10, placeholder/type/path parity scans | P0=0, P1=0 |
