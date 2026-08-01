# Issue #170 Waitlist Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 예약 서비스가 이름·전화번호를 복제하지 않고, 당일 빈자리에 대해 대기 항목·offer·durable capacity hold를 하나의 DB 권위로 안전하게 선택·claim·복구할 수 있는 코어를 구현한다.

**Architecture:** `appointment-core`에 대기 목록 typed model, Exposed table/repository, 결정적 후보 matcher, claim/recovery service를 추가한다. `ResourceAllocationRepository`의 기존 자원 mutex와 overlap 계산에 `OFFERED`/`ACCEPTED` hold를 포함하고, `appointment-api`에는 V18 additive migration과 세 dialect schema 검증만 추가한다. HTTP, 알림, scheduler, replacement appointment 생성은 이 계획의 경계 밖에 둔다.

**Tech Stack:** Kotlin 2.3 language/API, Java 21 toolchain, Spring Boot 4, Exposed v1 JDBC, Flyway, H2/PostgreSQL/MySQL, JUnit 5, MockK, bluetape4k assertions/test launchers, `bluetape4k-states` catalog dependency.

---

## 구현 경계와 파일 소유권

| 소유 영역 | 생성/수정 파일 | 책임 |
|---|---|---|
| 의존성·상태 머신 | `gradle/libs.versions.toml`, `appointment-core/build.gradle.kts`, `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/statemachine/AppointmentStateMachine.kt` | BOM-managed `bluetape4k-states` compile probe, 기존 예약 FSM API 호환 facade |
| 대기 typed model | `WaitlistEntryState.kt`, `WaitlistOfferState.kt`, `WaitlistCapacityHoldState.kt`, `WaitlistRecords.kt`, `WaitlistCommands.kt`, `WaitlistResults.kt`, `WaitlistExceptions.kt`, `WaitlistEvents.kt` under `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/` | state, scope, descriptor, command/result, exception, event 계약 |
| Exposed table | `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistEntries.kt`, `WaitlistOffers.kt`, `WaitlistCapacityHolds.kt`, `WaitlistOfferEvents.kt` | V18과 동일한 table/column/index 모델 |
| 대기 persistence | `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/WaitlistRepository.kt` | scope predicate, candidate keyset, CAS, history, hold lifecycle |
| 자원·결정 persistence | `ResourceAllocationRepository.kt`, `BookingReliabilityRepository.kt` | hold occupancy/mutex와 page batch decision snapshot |
| matching/claim | `WaitlistVacancyKeyHasher.kt`, `WaitlistCandidateMatcher.kt`, `WaitlistOfferService.kt`, `WaitlistOfferClaimService.kt` under `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/` | canonical vacancy key, hard eligibility, deterministic ordering, offer/claim/recovery transaction |
| migration | `appointment-api/src/main/resources/db/migration/{h2,postgresql,mysql}/V18__add_waitlist_core.sql` | 세 dialect additive schema |
| 검증 | `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/`의 명시된 test files, 기존 `TableSchemaTest`, `ResourceAllocationRepositoryTest`; `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/WaitlistCoreMigrationTestSupport.kt`와 세 Flyway test | RED/GREEN, dialect matrix, concurrency, EXPLAIN, PII/parameterization |
| 운영 문서 | `docs/runbooks/waitlist-core.md` | 이미 생성된 readiness/rollout/rollback/recovery 절차를 코드 metric과 동기화 |

이 계획은 root checkout의 Issue #176 변경과 무관한 feature worktree에서만 실행한다. `appointment-api` endpoint, `appointment-notification`, `appointment-event`, frontend, outbox, Redis leader election은 수정하지 않는다.

## 공통 검증 규칙

- 모든 Exposed 동작은 호출자가 연 `transaction {}` 안에서 수행한다. 열린 transaction 안에서 외부 service/network를 호출하지 않는다.
- caller 입력은 `requirePositiveNumber`, `requireNotBlank` 등 기존 bluetape4k helper로 검증하고, 내부 invariant는 `check`/`checkNotNull`로 구분한다. production `!!`, raw SQL 문자열 결합, 동적 `ORDER BY`, 자유 로그 payload는 사용하지 않는다.
- 새 Kotlin test는 JUnit 5, MockK, bluetape4k assertions(`assertFailsWith`, `shouldBeEqualTo`, collection matcher)을 사용한다. DB/컨테이너 테스트는 기존 singleton launcher와 `TestDB`/`withTables`를 재사용하고 `@Testcontainers`를 만들지 않는다.
- 각 행동은 `RED → GREEN → REFACTOR`로 진행한다. RED test를 먼저 실행해 의도한 실패를 확인하고, 최소 구현 후 같은 test와 module compile/test를 다시 실행한다.
- 변경 commit은 Lore 형식을 사용하고, 각 단계 후 `git diff --check`를 실행한다. 계획 승인 전에는 production source/migration을 변경하지 않는다.

### Task 0: 의존성·artifact compile probe

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `appointment-core/build.gradle.kts`
- Test/Probe: Gradle dependency resolution and `AppointmentStateMachine` compile probe

- [ ] **Step 1: BOM 좌표와 기존 artifact를 확인한다**

`~/.gradle/caches/modules-2/files-2.1/io.github.bluetape4k/bluetape4k-bom/1.11.0/` 아래의 POM에서 `io.github.bluetape4k:bluetape4k-states`와 관리 version을 확인하고, catalog에는 version을 중복 선언하지 않는다.

- [ ] **Step 2: versionless catalog alias와 core dependency를 추가한다**

`gradle/libs.versions.toml`에 다음 alias를 추가하고 `appointment-core/build.gradle.kts`에 동일한 public visibility의 dependency를 추가한다.

```toml
bluetape4k-states = { module = "io.github.bluetape4k:bluetape4k-states" }
```

```kotlin
api(libs.bluetape4k.states)
```

기존 BOM이 version을 관리하므로 alias에 `version =`을 쓰지 않는다.

- [ ] **Step 3: compile probe를 RED/GREEN 경계로 실행한다**

실행:

```bash
./gradlew :appointment-core:dependencies --configuration compileClasspath --refresh-dependencies
./gradlew :appointment-core:compileKotlin --refresh-dependencies
```

기대 결과: `io.github.bluetape4k:bluetape4k-states:<BOM managed version>`이 resolve되고 compile task가 통과한다. artifact 또는 DSL symbol이 resolve되지 않으면 local FSM을 복제하지 말고 이 계획을 dependency release-train blocker로 중단한다.

- [ ] **Step 4: probe 결과를 기록하고 commit한다**

`docs/superpowers/plans`나 source에 실패한 artifact를 우회하는 임시 코드가 없는지 확인한 뒤 catalog/build 파일만 commit한다. 이 commit의 테스트는 위 dependency/compile 출력과 `git diff --check`다.

### Task 1: typed state, identity, command/result 계약

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistEntryState.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistOfferState.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistCapacityHoldState.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistRecords.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistCommands.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistResults.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistExceptions.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistEvents.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistModelTest.kt`

- [ ] **Step 1: 허용 상태와 scope validation RED test를 작성한다**

다음 행동을 한 test씩 만든다.

```kotlin
@Test
fun `entry lifecycle accepts only the phase one terminal transitions`() {
    val result = WaitlistEntryTransitions.transition(
        WaitlistEntryState.WAITING,
        WaitlistEvent.OfferSelected,
    )
    result.currentState shouldBeEqualTo WaitlistEntryState.OFFERED
}

@Test
fun `scope rejects blank member and non-positive tenant or clinic`() {
    assertFailsWith<IllegalArgumentException> { WaitlistScope(0L, 1L, MemberId("member-1")) }
}

@Test
fun `correlation id rejects newline and profile-shaped input`() {
    assertFailsWith<IllegalArgumentException> { CorrelationId("line1\nline2") }
}
```

`assertFailsWith`로 exception 종류를 확인하고 입력 원문을 exception/log에 포함하지 않는지 검증한다.

- [ ] **Step 2: immutable typed model을 구현한다**

`WaitlistScope(tenantGroupId: Long, clinicId: Long, memberId: MemberId)`, `VacancyDescriptor(tenantGroupId, clinicId, treatmentTypeId, doctorId: Long?, startsAt, endsAt, resourceType, resourceId, capacityUnits, maximumCapacity, now)`, `DecisionStamp`, `WaitlistEntryRecord`, `WaitlistOfferRecord`, `WaitlistCapacityHoldRecord`를 immutable `Serializable` data class로 둔다. `CorrelationId`, `ActorRef`, `WaitlistReasonCode`, `WaitlistCursor`, `NewOffer`, `NewHold`, `OfferHoldIds`도 이 task에서 값의 범위와 생성 규칙을 함께 고정한다. 모든 시간은 UTC `Instant`이며 `startsAt < endsAt`, `capacityUnits <= maximumCapacity`, `now < endsAt`를 검증한다.

`WaitlistOfferState`는 `OFFERED`, `ACCEPTED`, `DECLINED`, `EXPIRED`, `WITHDRAWN`, hold state는 `OFFERED`, `ACCEPTED`, `CONSUMED`, `RELEASED`, `EXPIRED`로 닫는다. `WaitlistEvent.OfferSelected`를 포함한 typed event와 `WaitlistEntryTransitions.transition` 구현은 `WaitlistEvents.kt`에 둔다. `WaitlistOfferCommand`는 `offerId`, `scope`, `expectedVersion`, `correlationId`, `actorRef`를 포함하고 claim/release/reconcile command를 별도 typed class로 둔다.

- [ ] **Step 3: bounded 결과와 예외를 구현한다**

다음 결과와 stable reason을 sealed interface로 고정한다.

```kotlin
sealed interface WaitlistResult
data class CandidateFound(val offerId: Long, val holdId: Long, val rank: Int) : WaitlistResult
data class OfferClaimed(val offerId: Long, val holdId: Long, val memberId: MemberId, val holdExpiresAt: Instant) : WaitlistResult
data class OfferReleased(val offerId: Long, val holdId: Long, val reason: WaitlistReasonCode) : WaitlistResult
data class CapacityHoldExpired(val count: Int, val lastId: Long?) : WaitlistResult
```

`NoEligibleCandidate`, `OfferAlreadyExists`, `OfferExpired`, `OfferStateConflict`, `VersionConflict`, `SlotOccupied`, `OfferScopeMismatch`, `HoldScopeMismatch`, `DecisionStale`, `DecisionUnavailable`, `RecoveryConflict`, `RecoveryBudgetExceeded`를 domain exception/reason으로 제공한다. HTTP status와 notification payload는 model에 넣지 않는다.

- [ ] **Step 4: model test를 GREEN으로 만든다**

실행:

```bash
./gradlew :appointment-core:test --tests '*WaitlistModelTest'
```

기대 결과: 새 test 전부 PASS, 기존 `MemberIdTest`/state test도 PASS. `git diff --check` 후 model-only commit을 만든다.

### Task 2: ecosystem state machine 호환

**Files:**
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/statemachine/AppointmentStateMachine.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/statemachine/AppointmentStateMachineTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistStateMachineTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/statemachine/StatesCompileProbeTest.kt`

- [ ] **Step 1: 기존 동등성 test를 먼저 확장한다**

현재 `AppointmentStateMachineTest`의 모든 정상/invalid/terminal/callback case를 parameterized expected table로 고정하고, 같은 입력에 대해 compatibility facade와 `bluetape4k-states` machine 결과가 동일해야 한다는 failing assertion을 추가한다. callback은 성공 전이에 정확히 한 번만 호출되는지 확인한다.

- [ ] **Step 2: 실제 states DSL symbol을 compile probe로 고정한다**

Task 0에서 resolve한 artifact의 실제 `suspendStateMachine`, `TransitionResult`, builder import를 IDE/source jar로 확인한다. 계획에서 추측한 alias를 추가하지 말고 실제 symbol로 작은 `StatesCompileProbeTest` 또는 main compile fixture를 만든다. probe가 compile되지 않으면 구현을 중단하고 release-train blocker로 기록한다.

- [ ] **Step 3: 동일 선언에서 facade를 구현한다**

기존 public `nextState`, `canTransition`, `allowedEvents`, `suspend transition` signature를 유지하고, 허용 전이 선언을 ecosystem DSL 한 곳에서만 갖는다. `Map<Pair<AppointmentState, Class<out AppointmentEvent>>, AppointmentState>`를 새 코드에 남기지 않는다. callback과 error mapping은 기존 exception contract를 보존한다.

- [ ] **Step 4: state parity test를 GREEN으로 실행한다**

```bash
./gradlew :appointment-core:test --tests '*AppointmentStateMachineTest' --tests '*WaitlistStateMachineTest'
```

실패하면 facade에 별도 전이 목록을 추가하지 말고 DSL 선언/adapter를 수정한다. PASS 후 state-machine commit을 만든다.

### Task 3: Exposed waitlist tables와 schema model

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistEntries.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistOffers.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistCapacityHolds.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistOfferEvents.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistTableSchemaTest.kt`

- [ ] **Step 1: schema expectation RED test를 작성한다**

`WaitlistTableSchemaTest`에 `TestDB` method source를 사용해 네 table을 `withTables`로 생성하고 다음을 확인한다.

```kotlin
withTables(testDB, WaitlistEntries, WaitlistOffers, WaitlistCapacityHolds, WaitlistOfferEvents) {
    WaitlistEntries.tableName shouldBeEqualTo "scheduling_waitlist_entries"
}
```

`TableSchemaTest.allTables`에는 FK 순서대로 `WaitlistEntries`, `WaitlistOffers`, `WaitlistCapacityHolds`, `WaitlistOfferEvents`를 추가한다.

- [ ] **Step 2: table column과 check/index를 구현한다**

각 `LongIdTable`은 기존 `TenantGroups`, `Clinics`, `TreatmentTypes`, `Doctors` FK 규칙을 재사용한다.

`WaitlistEntries`: `tenantGroupId`, `clinicId`, `memberId`, `treatmentTypeId`, nullable `doctorId`, date/time window, bounded `priorityRank`, `status`, `waitingSince`, `version`, UTC timestamps. 인덱스는 date-window와 일반/doctor-specific 정렬 인덱스를 `priorityRank DESC`, `waitingSince ASC`, `id ASC` 방향으로 선언한다.

`WaitlistOffers`: immutable scope snapshot, entry FK, immutable `vacancyKey`, nullable active keys, slot/resource snapshot, expiry, status, reliability decision stamp, candidate rank, bounded reason, version/timestamps. active key는 nullable unique로 terminal 전이에 NULL을 기록한다.

`WaitlistCapacityHolds`: offer unique FK, scope snapshot, vacancy/active key, `ResourceType`/resource ID, half-open times, capacity units/maximum, hold status/expiry, CAS/audit timestamps. active overlap index는 `(tenantGroupId, clinicId, resourceType, resourceId, status, startsAt, endsAt, id)`다.

`WaitlistOfferEvents`: entry/nullable offer/hold FK, from/to state 문자열, bounded reason/actor/correlation, occurred/event version. reason/actor/correlation validation은 command model과 동일하게 적용한다.

- [ ] **Step 3: table schema test를 GREEN으로 실행한다**

```bash
./gradlew :appointment-core:test --tests '*WaitlistTableSchemaTest' --tests '*TableSchemaTest'
```

기대 결과: enabled H2 및 configured dialect에서 table/index 생성과 drop/recreate가 PASS한다. schema model-only commit을 만든다.

### Task 4: V18 additive migration과 Flyway matrix

**Files:**
- Create: `appointment-api/src/main/resources/db/migration/h2/V18__add_waitlist_core.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V18__add_waitlist_core.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V18__add_waitlist_core.sql`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/WaitlistCoreMigrationTestSupport.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`

- [ ] **Step 1: V18 migration contract test를 RED로 만든다**

`WaitlistCoreMigrationTestSupport`는 Flyway clean install과 V17→V18 upgrade를 각각 실행해 `migrationsExecuted == 1`, 네 table, FK, columns, named index, nullable active-key unique semantics를 확인한다. H2/PostgreSQL/MySQL test class는 기존 support/launcher와 순차 실행 규칙을 사용한다.

- [ ] **Step 2: 세 dialect에 동일 의미의 DDL을 작성한다**

네 table의 공통 column과 constraint는 다음을 사용한다.

```text
waitlist_entries: id, tenant_group_id, clinic_id, member_id, treatment_type_id,
doctor_id, preferred_date_from, preferred_date_to, preferred_start_time,
preferred_end_time, priority_rank, status, waiting_since, version, created_at, updated_at
waitlist_offers: id, tenant_group_id, clinic_id, member_id, waitlist_entry_id,
vacancy_key, active_entry_key, active_vacancy_key, doctor_id, treatment_type_id,
starts_at, ends_at, expires_at, status, booking_reliability_decision_id,
booking_reliability_policy_version_id, booking_reliability_policy_hash,
booking_reliability_evaluation_digest, booking_reliability_expires_at,
candidate_rank, selection_reason_code, version, created_at, updated_at
waitlist_capacity_holds: id, tenant_group_id, clinic_id, member_id, offer_id,
vacancy_key, active_vacancy_key, resource_type, resource_id, starts_at, ends_at,
capacity_units, maximum_capacity, status, hold_expires_at, version, created_at,
updated_at, released_at, consumed_at
waitlist_offer_events: id, waitlist_entry_id, offer_id, hold_id, from_state, to_state,
reason_code, actor_ref, correlation_id, occurred_at, event_version
```

PostgreSQL는 identity/regex check, H2는 existing V17 syntax와 compatible check, MySQL은 `AUTO_INCREMENT`, `TIMESTAMP(6)`, InnoDB/utf8mb4를 사용한다. 각 dialect에서 FK는 tenant/clinic/member scope를 domain predicate로 보완하고, nullable active keys는 terminal NULL 재사용 fixture로 검증한다. raw down migration은 추가하지 않는다.

- [ ] **Step 3: migration metadata와 EXPLAIN assertion을 GREEN으로 만든다**

metadata test는 candidate index의 `priority_rank:D`, `waiting_since:A`, `id:A`와 hold overlap index의 equality prefix/time columns를 확인한다. PostgreSQL/MySQL representative dataset에 대해 `EXPLAIN` 결과에 full scan/filesort가 없음을 기록한다. H2는 동일 column order와 semantic fixture를 검증한다.

```bash
./gradlew :appointment-api:test --tests '*FlywayMigrationTest' --tests '*FlywayPostgreSQLMigrationTest' --tests '*FlywayMySQLMigrationTest'
```

컨테이너/네트워크 오류는 재시도 성공만으로 통과시키지 말고 launcher/log evidence와 함께 분류한다. migration commit 후 `./gradlew :appointment-api:compileTestKotlin`을 실행한다.

### Task 5: waitlist repository와 scope/CAS/history

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/WaitlistRepository.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/WaitlistRepositoryTest.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/RecordMappers.kt`

- [ ] **Step 1: repository RED tests를 작성한다**

`withTables`/기존 seeded tenant-clinic fixture를 사용해 다음을 test한다.

1. 다른 tenant/clinic/member의 entry/offer/hold가 candidate/direct read에 나오지 않는다.
2. `WAITING` entry의 `waitingSince`가 retry/requeue에서 바뀌지 않는다.
3. same entry와 same vacancy의 active key unique conflict가 안정적인 `OfferAlreadyExists`가 된다.
4. offer/hold/entry/history insert 중 하나가 실패하면 모두 rollback된다.
5. wrong-member hold replay/consume/reconcile가 `HoldScopeMismatch`이고 row/capacity/history가 그대로다.
6. `OFFERED`/`ACCEPTED` hold만 active로 읽고 terminal row는 삭제하지 않는다.

- [ ] **Step 2: repository public transaction contract를 구현한다**

다음 메서드는 transaction을 열지 않고 caller transaction을 요구한다.

```kotlin
fun findCandidatePage(vacancy: VacancyDescriptor, cursor: WaitlistCursor?, limit: Int): List<WaitlistEntryRecord>
fun findOfferForUpdate(scope: WaitlistScope, offerId: Long): WaitlistOfferRecord?
fun findHoldForUpdate(scope: WaitlistScope, holdId: Long): WaitlistCapacityHoldRecord?
fun insertOfferAndHold(scope: WaitlistScope, entry: WaitlistEntryRecord, offer: NewOffer, hold: NewHold): OfferHoldIds
fun casOffer(scope: WaitlistScope, offerId: Long, expectedVersion: Long, from: WaitlistOfferState, to: WaitlistOfferState): Boolean
fun casHold(scope: WaitlistScope, holdId: Long, expectedVersion: Long, from: WaitlistCapacityHoldState, to: WaitlistCapacityHoldState): Boolean
fun casEntry(scope: WaitlistScope, entryId: Long, expectedVersion: Long, from: WaitlistEntryState, to: WaitlistEntryState): Boolean
fun appendEvent(event: WaitlistOfferEvent): Long
fun findExpiredHolds(limit: Int, now: Instant): List<WaitlistCapacityHoldRecord>
```

모든 query는 `(tenant_group_id, clinic_id, id)`와 연결 row scope equality를 함께 사용한다. candidate keyset predicate는 `(slotFit DESC, priorityRank DESC, waitingSince ASC, id ASC)`의 cursor를 사용하며 offset/random order를 금지한다. `insertOfferAndHold`는 hold와 offer를 별도 성공으로 반환하지 않고 같은 transaction result로만 반환한다.

- [ ] **Step 3: CAS/history test를 GREEN으로 만든다**

```bash
./gradlew :appointment-core:test --tests '*WaitlistRepositoryTest'
```

update count가 0이면 `VersionConflict` 또는 stable lifecycle conflict로 매핑하고, unknown SQL error는 rollback 후 상위로 전파한다. PASS 후 repository commit을 만든다.

### Task 6: ResourceAllocationRepository durable hold integration

**Files:**
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ResourceAllocationRepository.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/ResourceAllocationRepositoryTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/WaitlistCapacityHoldIntegrationTest.kt`

- [ ] **Step 1: hold occupancy RED tests를 작성한다**

기존 confirmed allocation fixture와 waitlist hold fixture를 한 transaction에서 준비한다. 아래 이름의 private test fixture helper도 이 test file 안에 함께 정의하고, 각 helper가 명시된 상태·row count를 반환하도록 한다.

```kotlin
@Test
fun `offered hold blocks a confirmed allocation on the same resource`() {
    assertFailsWith<ResourceAllocationConflictException> { createConfirmedAllocationOnHeldResource() }
}

@Test
fun `accepted hold is consumed only after replacement allocation succeeds`() {
    holdAfterFailedReplacement().status shouldBeEqualTo WaitlistCapacityHoldState.ACCEPTED
}

@Test
fun `released or expired hold returns capacity without deleting audit row`() {
    activeHoldCountAfterRelease() shouldBeEqualTo 0
}
```

`createConfirmedAllocationOnHeldResource()`는 `ResourceAllocationConflictException`을
관찰하는 호출이고, `holdAfterFailedReplacement()`는 replacement allocation을
rollback한 뒤의 hold row를 읽으며, `activeHoldCountAfterRelease()`는 terminal hold를
제외한 count를 반환한다. 이 helper들은 production API가 아니라 fixture 조립을 위한
private test 함수다.

exclusive/shared/capacity bucket 각각에 대해 half-open overlap과 capacity sum을 검증한다.

- [ ] **Step 2: common lock and occupancy path를 구현한다**

기존 `lockResourceMutex`/`validateExistingConflicts`에 hold row를 active allocation과 같은 `tenantGroupId, clinicId, resourceType, resourceId, status, startsAt, endsAt` 기준으로 포함한다. 새 public/internal 경계는 다음 의미를 갖는다.

```kotlin
fun reserveWaitlistCapacityHold(hold: NewHold): WaitlistCapacityHoldRecord
fun consumeWaitlistCapacityHold(scope: WaitlistScope, holdId: Long, expectedVersion: Long): Boolean
fun releaseWaitlistCapacityHold(scope: WaitlistScope, holdId: Long, terminal: WaitlistCapacityHoldState): Boolean
```

자원 mutex → hold → offer → entry → reliability snapshot 순서를 모든 mutation에서 지킨다. proposal을 synthetic하게 생성하지 않는다. hold scope mismatch는 변경 전 fail closed다.

- [ ] **Step 3: capacity/rollback/concurrency test를 GREEN으로 만든다**

```bash
./gradlew :appointment-core:test --tests '*ResourceAllocationRepositoryTest' --tests '*WaitlistCapacityHoldIntegrationTest'
```

실패 transaction에 orphan hold/offer/allocation이 남지 않는지 row count와 active capacity로 확인한다. 이후 dialect별 DB concurrency test를 Task 12에서 실행한다.

### Task 7: decision snapshot batch port

**Files:**
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/BookingReliabilityRepository.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/BookingReliabilityRepositoryTest.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/reliability/BookingReliabilityDecisionBatchPort.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/reliability/BookingReliabilityDecisionBatchPortTest.kt`

- [ ] **Step 1: batch contract RED test를 작성한다**

page에 100 member ID를 넣으면 repository 호출 1회와 같은 tenant/clinic/member scope stamp가 반환되고, 누락 member는 `DecisionUnavailable`이 되는 test를 작성한다. candidate별 single lookup을 mock verify하지 않는다.

- [ ] **Step 2: scoped batch query를 구현한다**

`findLatestDecisions(tenantGroupId: Long, clinicId: Long, memberIds: Collection<MemberId>, evaluatedAt: Instant)`를 추가한다. SQL은 scope와 member `IN` predicate를 포함하고, evaluatedAt 이전 최신 row를 deterministic `evaluatedAt DESC, id DESC`로 선택한다. 반환 record마다 scope stamp와 digest/expiry를 보존한다.

provider는 local immutable snapshot repository다. 외부 evaluator refresh는 transaction 밖에서만 수행한다. transaction 안에서는 row/stamp equality와 expiry만 확인한다.

- [ ] **Step 3: batch test와 compile을 GREEN으로 만든다**

```bash
./gradlew :appointment-core:test --tests '*BookingReliabilityRepositoryTest' --tests '*BookingReliabilityDecisionBatchPortTest'
```

round-trip counter가 page 수와 일치하지 않으면 test를 통과시키지 않는다. PASS 후 decision port commit을 만든다.

### Task 8: canonical vacancy key와 deterministic candidate matcher

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistVacancyKeyHasher.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistCandidateMatcher.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistVacancyKeyHasherTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistCandidateMatcherTest.kt`

- [ ] **Step 1: canonical hash RED tests를 작성한다**

동일 descriptor의 field order/재시도는 같은 lowercase SHA-256을 만들고, clinic/resource/time/capacity가 하나라도 다르면 다른 key가 되는 test를 작성한다. client가 `vacancyKey`/active key를 전달할 수 없는 command constructor test도 추가한다.

- [ ] **Step 2: server-owned canonicalizer를 구현한다**

`ProposalHasher`의 length-framed SHA-256 패턴을 재사용하되 `tenantGroupId`, `clinicId`, resource type/ID, capacity units/maximum, half-open starts/ends, treatment/doctor를 모두 named field로 hash한다. raw member name/phone/profile은 descriptor에 존재하지 않는다.

- [ ] **Step 3: hard eligibility와 keyset matcher RED/GREEN을 구현한다**

SQL predicate는 tenant, clinic, treatment, doctor, date/time-window, `WAITING`, startsAt > now를 먼저 적용한다. doctor-specific query를 먼저 실행하고 unspecified query를 다음에 실행한다. 정렬과 cursor는 다음으로 고정한다.

```text
slotFit DESC, priorityRank DESC, waitingSince ASC, entryId ASC
cursor = (slotFit, priorityRank, waitingSince, entryId)
pageSize = min(requested, 500), default = 100
```

page당 batch decision provider 1회, 최대 10 page/1,000 candidate 또는 2초 budget을 적용한다. candidate read는 non-locking이고 promotion 시 resource mutex를 먼저 잡은 뒤 entry를 `forUpdate`로 다시 읽는다.

- [ ] **Step 4: matcher test를 실행한다**

```bash
./gradlew :appointment-core:test --tests '*WaitlistVacancyKeyHasherTest' --tests '*WaitlistCandidateMatcherTest'
```

expected: scope leak 0, deterministic in-memory/DB order 동일, page round-trip count = provider call count, budget 초과 시 cursor를 반환하고 다음 tick으로 넘긴다.

### Task 9: offer creation transaction

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistOfferService.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistOfferServiceTest.kt`

- [ ] **Step 1: 원자성 RED test를 작성한다**

다음 실패/성공을 service test에 고정한다.

1. one vacancy에서 active offer/hold가 하나만 생성된다.
2. entry CAS, offer insert, hold insert, history insert 중 하나가 실패하면 전체 rollback이다.
3. decision unavailable candidate는 건너뛰고 다음 page를 읽는다.
4. same vacancy 경쟁 loser는 bounded max 3 retry 후 `OfferAlreadyExists`/`NoEligibleCandidate`다.
5. wrong scope/active entry는 `OfferScopeMismatch`다.

- [ ] **Step 2: resource-first offer transaction을 구현한다**

`selectAndOffer(vacancy, now)`는 호출자 transaction에서 `now`를 한 번 캡처하고 다음 순서만 사용한다.

```text
resource mutex
→ bounded WAITING keyset page + local decision batch
→ candidate entry forUpdate/scope/version recheck
→ WaitlistOffers(OFFERED, active keys) insert
→ WaitlistCapacityHolds(OFFERED) insert
→ entry CAS WAITING→OFFERED
→ append history
→ commit
```

active unique conflict만 예상 가능한 retry로 분류하고 unknown SQL error는 rollback/throw한다. hold가 생성되지 않은 offer는 절대 성공으로 반환하지 않는다.

- [ ] **Step 3: offer service test를 GREEN으로 만든다**

```bash
./gradlew :appointment-core:test --tests '*WaitlistOfferServiceTest'
```

test output에 active offer/hold count, retry count, stable result를 남기되 member/vacancy raw value는 log/metric에 넣지 않는다.

### Task 10: claim, release, expiry, reconcile service

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistOfferClaimService.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistOfferClaimServiceTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistRecoveryServiceTest.kt`

- [ ] **Step 1: claim/replay/expiry RED tests를 작성한다**

1. `OFFERED` claim은 resource mutex → hold → offer → entry → decision 순서를 사용한다.
2. 같은 caller retry는 `ACCEPTED`와 기존 hold ID/expiry를 replay하고 새 row를 만들지 않는다.
3. `now >= expiresAt` 또는 `now >= startsAt`이면 hold/offer/entry가 같은 transaction에서 `EXPIRED`다.
4. stale/expired/scope mismatch/occupied capacity/version conflict는 deterministic failure이고 offer/entry를 `ACCEPTED`로 만들지 않는다.
5. existing `OFFERED` hold가 없으면 same-tx reserve repair를 시도하되 capacity conflict면 `SlotOccupied`다.

- [ ] **Step 2: claim/release CAS를 구현한다**

transaction 시작 시 `Clock.now()`를 한 번 캡처한다. decision stamp는 transaction 밖에서 준비하고 local row/scope/digest/expiry만 transaction 안에서 재확인한다. 성공 결과는 `OfferClaimed(offerId, holdId, memberId, holdExpiresAt)`이고 appointment 생성은 수행하지 않는다. decline/withdraw는 hold release, active key NULL, offer/entry terminal CAS, history append를 하나의 transaction으로 묶는다.

- [ ] **Step 3: bounded recovery RED/GREEN을 구현한다**

`reconcileWaitlistHolds(limit, now)`는 limit을 100으로 cap하고 config max 500을 넘지 않는다. 각 row에 resource mutex를 잡고 연결 offer/entry/hold scope/state를 `forUpdate`로 확인한 뒤, `OFFERED` expiry 또는 startsAt 이후 `ACCEPTED` hold를 모두 `EXPIRED`로 terminal 전이한다. 불일치는 `OfferStateConflict` backlog로 남기고 row/capacity를 변경하지 않는다. connection failure는 rollback/retry다.

```bash
./gradlew :appointment-core:test --tests '*WaitlistOfferClaimServiceTest' --tests '*WaitlistRecoveryServiceTest'
```

### Task 11: PII, actor, correlation, observability

**Files:**
- Modify: waitlist model/repository/service files from Tasks 1, 5, 9, 10
- Modify: `docs/runbooks/waitlist-core.md`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistPrivacyAndObservabilityTest.kt`

- [ ] **Step 1: malicious-input RED test를 작성한다**

email, phone, JWT-like actor ref, raw SHA actor ref, newline correlation, oversized correlation, member/profile text, SQL metacharacters를 command boundary에 넣고 모두 reject/sanitize되는지 확인한다. event/log/metric capture에는 raw value가 없음을 assert한다.

- [ ] **Step 2: validation/redaction/metrics를 구현한다**

`actorRef`는 `SYSTEM`, 내부 opaque staff ID, domain-separated HMAC digest, bounded recovery command ID만 허용한다. `correlationId`는 1..128 `[A-Za-z0-9._:-]`이고 log/metric/exception에는 sanitized 값만 전달한다. metric label은 allowlisted low-cardinality tenant/clinic만 허용한다. required metric names는 `waitlist_offer_active`, `waitlist_hold_active`, `waitlist_claim_conflict_total`, `waitlist_decision_unavailable_total`, `waitlist_expiry_backlog`, `waitlist_hold_reconcile_age_seconds`다.

- [ ] **Step 3: privacy test와 runbook parity를 GREEN으로 만든다**

```bash
./gradlew :appointment-core:test --tests '*WaitlistPrivacyAndObservabilityTest'
git diff --check
```

runbook의 threshold/triage가 실제 metric name과 command result를 그대로 가리키는지 확인한다. 이름·전화번호를 채우는 회원관리/알림 adapter는 추가하지 않는다.

### Task 12: parity, performance, stability, and migration completion proof

**Files:**
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistContentionLoadTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistRecoveryRestartTest.kt`
- Modify: migration support/query-plan tests from Task 4
- Modify: `docs/superpowers/checklists/2026-08-01-issue-170-waitlist-core-workflow.md`

- [ ] **Step 1: RED load/stability tests를 작성한다**

bounded JDBC pool에서 popular vacancy에 100 concurrent offer/claim attempt를 실행한다. 승자는 active offer/hold 또는 claim 하나이고, losers는 stable conflict/no-candidate이며 deadlock/unexpected SQL failure는 0이다. p95와 pool 설정을 결과 artifact에 기록한다. `MultithreadingTester`가 DB connection/transaction 증거를 충분히 표현하지 못하면 그 근거를 test KDoc에 쓰고 existing launcher와 bounded `ExecutorService`를 사용한다.

- [ ] **Step 2: sequential DB matrix를 실행한다**

Testcontainers raw instance를 만들지 않고 `TestDB`/bluetape4k launcher를 사용한다. H2, PostgreSQL, MySQL 순서로 다음을 실행한다.

```bash
./gradlew :appointment-core:test --tests '*WaitlistContentionLoadTest' --tests '*WaitlistRecoveryRestartTest'
./gradlew :appointment-api:test --tests '*FlywayMigrationTest' --tests '*FlywayPostgreSQLMigrationTest' --tests '*FlywayMySQLMigrationTest'
```

중간 rollback/restart 후 duplicate release, active count drift, state mismatch backlog 보존을 확인한다. 컨테이너 test는 다른 Gradle process와 병렬 실행하지 않는다.

- [ ] **Step 3: full affected module proof를 실행한다**

```bash
./gradlew :appointment-core:test :appointment-api:test
./gradlew :appointment-core:build :appointment-api:build
```

Kover는 full module test 이후 report-only로 실행하며 새 hard threshold를 추가하지 않는다. compile warnings/deprecations, `!!`, raw SQL, log payload, unregistered files를 `rg`와 diff review로 확인한다.

- [ ] **Step 4: plan/spec/checklist traceability를 갱신한다**

spec §13 각 검증 항목에 test class/command를 연결하고, checklist `CG-06..CG-10`, `A-04..A-09`, `Step 4-P`의 evidence를 실제 SHA/output으로 채운다. 미실행 conditional PR rows는 authority가 없으면 N/A로 바꾸지 않고 pending으로 둔다.

### Task 13: final Kotlin/repository review and handoff

**Files:**
- Review all changed source/tests/docs/migrations; no new implementation file unless a prior task names it

- [ ] **Step 1: Kotlin final checklist를 수행한다**

`bluetape-kotlin-patterns/references/checklist.md`의 KT-FIN-01..11과 testing reference KT-TEST-01..05를 evidence와 함께 완료한다. Exposed import/deprecation, transaction ownership, receiver shadowing, `Clock`/rollback, blocking/cancellation, KDoc Korean, source-doc parity를 확인한다.

- [ ] **Step 2: six-lens code review와 performance scan을 실행한다**

성능·안정성·보안·Ops·개발자/API·사용자/호출자 관점으로 exact final diff를 읽고, P0/P1이 생기면 해당 task로 되돌아가 test-first repair 후 재검토한다. runbook, migration metadata, active hold semantics, API out-of-scope를 함께 확인한다.

- [ ] **Step 3: final verification and commit hygiene를 수행한다**

```bash
git diff --check
git status --short
./gradlew :appointment-core:compileKotlin :appointment-core:compileTestKotlin
./gradlew :appointment-core:test :appointment-api:test
```

최종 report에는 변경 파일, fresh command/결과, P0/P1/P2/P3, unchecked rows, implementation scope, known gaps를 기록한다. PR/merge/push는 별도 사용자의 명시 권한이 있을 때만 다음 workflow로 진행한다.

## 계획 자체의 self-review

- **A-04 gate evidence:** reviewed design commit `b041179` and iteration-2 review `2abd0e7` both report P0=0/P1=0; the integrated review is PASS with only two explicitly deferred P2 follow-ups (developer/API compile probe and caller replacement-idempotency examples), both covered by Tasks 0, 2, 9, and 10.
- **Spec coverage:** §1–§7은 Tasks 0–2, §8은 Task 3, §9는 Tasks 7–9, §10은 Tasks 6/9/10, §11은 Task 11, §12는 Task 4, §13은 Tasks 5/6/8/10–12, §14 운영 산출물은 Task 11/13에 매핑했다.
- **No placeholders:** 각 task에 정확한 파일, public method shape, schema columns/index, command와 기대 결과를 적었고 비어 있는 설계 단계를 남기지 않았다.
- **Type consistency:** `WaitlistScope`, `VacancyDescriptor`, `DecisionStamp`, `WaitlistOfferState`, `WaitlistCapacityHoldState`, `OfferClaimed`, `CapacityHoldExpired`를 앞 Task에서 정의하고 repository/service/test가 같은 이름을 사용한다.
- **Known deliberate deferrals:** HTTP/notification adapter, replacement appointment consume command, scheduler, full policy evaluator, outbox, Redis leader election, PR/merge는 Issue #170 core 다음 단계로 명시적으로 제외한다.

## 실행 handoff

계획 파일: `docs/superpowers/plans/2026-08-01-issue-170-waitlist-core.md`

계획은 A-04 계획 검토와 사용자 승인 이후에만 Task 0부터 실행한다. 승인 전에는 source/migration을 변경하지 않는다.
