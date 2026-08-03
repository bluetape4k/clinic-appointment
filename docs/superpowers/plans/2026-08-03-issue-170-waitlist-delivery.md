# Issue #170 Waitlist Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** V18 waitlist core를 유지하면서 clinic policy, durable vacancy 처리, 알림 전달, staff API, expiry/recovery 운영까지 Issue #170의 남은 백엔드 범위를 완성한다.

**Architecture:** `appointment-core`가 policy·adjustment·vacancy·command의 DB 권위와 deterministic 결정을 소유하고, `appointment-event`는 after-commit fast signal/outbox contract만 제공한다. `appointment-notification`은 DB transaction 밖에서 provider IO를 수행하며, `appointment-api`가 scope/JWT, application transaction, scheduler, metric/health와 Flyway V19를 조립한다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed ORM, Flyway, Micrometer, Redis leader election, JUnit 5, AssertJ/Kluent, H2/PostgreSQL/MySQL Testcontainers singleton launchers

---

## 구현 단위와 파일 책임

| 단위 | 주요 파일 | 책임 |
|---|---|---|
| V19 domain/schema | `appointment-core/.../model/waitlist/WaitlistDelivery*.kt`, `model/tables/Waitlist*.kt` | typed value/state와 Exposed table |
| policy | `repository/waitlist/WaitlistPolicyRepository.kt`, `service/waitlist/WaitlistPolicyEvaluator.kt` | clinic row lock, generation CAS, strict canonical policy |
| vacancy/command | `repository/waitlist/WaitlistDeliveryRepository.kt` | lease fence, generation uniqueness, idempotency reservation |
| orchestration | `service/waitlist/WaitlistDeliveryService.kt` | 후보 선택, offer/hold/outbox 원자성, expiry progression |
| event/outbox | `appointment-event/.../waitlist/*.kt` | `SlotAvailable`, notification outbox payload/audit adapter |
| notification | `appointment-notification/.../WaitlistOfferNotification*.kt` | claim/read, pre-send CAS, provider IO, result CAS |
| HTTP/security | `appointment-api/.../waitlist/*.kt`, `controller/Waitlist*.kt` | `TenantScope`, DTO/controller, JWT role/permission, OpenAPI |
| scheduler/ops | `appointment-api/.../waitlist/WaitlistDeliveryScheduling.kt` | leader runner, expiry/recovery, metric/health |
| migration | `appointment-api/src/main/resources/db/migration/*/V19__add_waitlist_delivery.sql` | 세 dialect additive schema/index/FK |
| 문서 | module READMEs, `docs/runbooks/waitlist-delivery.md`, `docs/lessons/...` | 운영·caller 계약과 교훈 |

## 3-R 반영 실행 순서와 선행 gate

아래 순서는 Task 번호보다 우선한다. Task 1 뒤에는 Task 12 Step 1~4A의 V19 additive
migration과 H2/PostgreSQL/MySQL 최소 activation/claim/query contract를 먼저 구현·검증한다.
그 증거가 PASS일 때만 Task 2~11을 진행하며, Task 12 Step 5의 전체 concurrency/query-plan
matrix는 Task 11 뒤에 다시 실행한다. 이로써 repository/service가 검증되지 않은 dialect
semantic에 의존하지 않는다.

각 하위 gate는 실패 시 다음 task를 시작하지 않는다.

| 선행 gate | 필수 증거 |
|---|---|
| V19 dialect-first | 세 dialect table/index/FK/check 동등성, first activation, one-worker claim, ranked-query index 사용 |
| core compile boundary | `appointment-core`가 `appointment-event`/`appointment-notification`에 의존하지 않음 |
| contention | 2초 lock wait, SQLSTATE `40001`/`40P01` 최대 3회 jitter retry, 소진 시 `409 WAITLIST_CONTENTION` |
| deterministic time | 주입 `Clock`, clinic IANA zone, DST overlap `Instant`, gap rejection, clinic-local next generation |
| command recovery | reservation 후 crash는 stable `FAILED`, appointment 생성 후 crash는 offer ID로 `SUCCEEDED` reconcile |
| HTTP contract | endpoint별 scope, replay, cursor, error, redaction, OpenAPI matrix PASS |
| operability | authenticated Actuator, retention, V19 pre/postcheck, recovery command, shadow preview, alert dry-run PASS |

## Task 1: V19 typed domain과 Exposed table 고정

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistDeliveryStates.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistDeliveryRecords.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistPolicyVersions.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistPolicyEvents.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/BookingRestrictions.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/DisruptionRecoveryCredits.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/BookingBenefitGrants.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistVacancyJobs.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistCommandRecords.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryModelTest.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryTableSchemaTest.kt`

- [ ] **Step 1: 상태와 semantic constraint RED 테스트 작성**

```kotlin
@Test
fun `vacancy lease는 만료 전 fence만 terminal 전이를 허용한다`() {
    val lease = VacancyLease("worker-a", version = 3, expiresAt = instant("2026-08-03T10:00:30Z"))
    lease.isValid("worker-a", 3, instant("2026-08-03T10:00:29Z")) shouldBe true
    lease.isValid("worker-a", 3, instant("2026-08-03T10:00:30Z")) shouldBe false
}

@Test
fun `command key는 scope command digest를 모두 요구한다`() {
    shouldThrow<IllegalArgumentException> {
        WaitlistCommandKey(tenantGroupId = 1, clinicId = 2, commandType = "", keyDigest = "hmac-sha256:x")
    }
}
```

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-core:test --tests "*WaitlistDeliveryModelTest" --no-build-cache`

Expected: `WaitlistDeliveryStates`와 record type 미정의로 compilation FAIL.

- [ ] **Step 3: 최소 domain type 구현**

```kotlin
enum class VacancyJobState { READY, PROCESSING, OFFERED, NO_CANDIDATE, EXPIRED, FAILED }
enum class WaitlistCommandState { PROCESSING, SUCCEEDED, FAILED }
enum class WaitlistPolicyState { DRAFT, ACTIVE, RETIRED }

data class VacancyLease(val owner: String, val version: Long, val expiresAt: Instant) {
    init { require(owner.isNotBlank()); require(version >= 0) }
    fun isValid(owner: String, version: Long, now: Instant): Boolean =
        this.owner == owner && this.version == version && expiresAt.isAfter(now)
}

data class WaitlistCommandKey(
    val tenantGroupId: Long,
    val clinicId: Long,
    val commandType: String,
    val keyDigest: String,
) {
    init {
        require(tenantGroupId > 0 && clinicId > 0)
        require(commandType.isNotBlank() && keyDigest.startsWith("hmac-sha256:"))
    }
}

data class ClinicWaitlistScope(val tenantGroupId: Long, val clinicId: Long) {
    init { require(tenantGroupId > 0 && clinicId > 0) }
}

sealed class WaitlistDeliveryException(message: String) : RuntimeException(message)
class VacancyGenerationConflict : WaitlistDeliveryException("VACANCY_GENERATION_CONFLICT")
class WaitlistPolicyConflict : WaitlistDeliveryException("POLICY_CONFLICT")
class IdempotencyRequestMismatch : WaitlistDeliveryException("IDEMPOTENCY_REQUEST_MISMATCH")
class WaitlistContention : WaitlistDeliveryException("WAITLIST_CONTENTION")
```

- [ ] **Step 4: table schema RED 테스트와 최소 table 구현**

```kotlin
@Test
fun `V19 table 이름과 active key column을 고정한다`() = transaction {
    SchemaUtils.createMissingTablesAndColumns(
        WaitlistPolicyVersions,
        WaitlistVacancyJobs,
        WaitlistCommandRecords,
    )
    WaitlistVacancyJobs.tableName shouldBe "scheduling_waitlist_vacancy_jobs"
    WaitlistVacancyJobs.columns.map { it.name } shouldContainAll
        listOf("vacancy_generation", "active_vacancy_key", "lease_owner", "lease_expires_at")
}
```

Table은 기존 `LongIdTable`, `timestamp`, `enumerationByName`, FK naming pattern을 따르고
KDoc은 한국어로 작성한다. 모든 Exposed query는 test의 `transaction {}` 안에서 실행한다.

- [ ] **Step 5: core model 테스트 GREEN 확인**

Run: `./gradlew :appointment-core:test --tests "*WaitlistDeliveryModelTest" --tests "*WaitlistDeliveryTableSchemaTest" --no-build-cache`

Expected: PASS, schema test가 7개 V19 table과 active-key column을 확인.

- [ ] **Step 6: Lore 커밋**

```text
Establish waitlist delivery invariants before persistence behavior

Constraint: Keep V18 offer and hold tables authoritative.
Confidence: high
Scope-risk: moderate
Directive: Preserve nullable active-key semantics across all dialect migrations.
Tested: Waitlist delivery model and table schema tests.
Not-tested: Repository concurrency and Flyway dialect parity.
```

## Task 2: strict policy document와 deterministic evaluator

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/waitlist/WaitlistPolicyDocument.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistPolicyEvaluator.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistPolicyDocumentTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistPolicyEvaluatorTest.kt`

- [ ] **Step 1: parser boundary RED 테스트 작성**

```kotlin
@Test
fun `unknown field와 oversized policy를 거부한다`() {
    shouldThrow<WaitlistPolicyValidationException> { codec.decode("""{"unknown":1}""") }
    shouldThrow<WaitlistPolicyValidationException> { codec.decode("{" + "x".repeat(65_536) + "}") }
}

@Test
fun `같은 policy는 key 순서와 무관하게 같은 digest를 만든다`() {
    codec.decode("""{"waitingAgeWeight":2,"urgencyWeight":10}""").digest shouldBe
        codec.decode("""{"urgencyWeight":10,"waitingAgeWeight":2}""").digest
}
```

같은 RED fixture에 depth 9, `-1`/`10_001`, unknown enum, Jackson
`@class`/default-typing metadata, duplicate/conflicting key를 추가한다. parser가 duplicate key를
허용하지 않도록 설정하고 integer/key order가 다른 의미 동등 JSON은 같은 canonical digest를
만드는지 검증한다.

- [ ] **Step 2: parser RED 실행**

Run: `./gradlew :appointment-core:test --tests "*WaitlistPolicyDocumentTest" --no-build-cache`

Expected: codec/type 미정의로 FAIL.

- [ ] **Step 3: 64 KiB/depth 8/strict enum canonical codec 구현**

```kotlin
data class WaitlistPolicyDocument(
    val urgencyWeight: Int,
    val recoveryWeight: Int,
    val benefitWeight: Int,
    val reliabilityWeight: Int,
    val waitingAgeWeight: Int,
    val slotFitWeight: Int,
) {
    init { listOf(urgencyWeight, recoveryWeight, benefitWeight, reliabilityWeight, waitingAgeWeight, slotFitWeight)
        .forEach { require(it in 0..10_000) } }
}

data class PolicyDecision(
    val eligible: Boolean,
    val reasonCodes: List<WaitlistReasonCode>,
    val scoreTuple: List<Long>,
    val policyDigest: String,
)
```

Jackson mapper는 default typing을 끄고 unknown property를 실패시키며, canonical JSON을
key 정렬 후 SHA-256 digest한다.

- [ ] **Step 4: hard eligibility와 score tuple RED/GREEN**

```kotlin
@Test
fun `restriction 실패는 scoring보다 먼저 종료한다`() {
    val result = evaluator.evaluate(candidate(restricted = true), vacancy(), policy())
    result.eligible shouldBe false
    result.reasonCodes shouldBe listOf(WaitlistReasonCode("RESTRICTION_ACTIVE"))
    result.scoreTuple shouldBe emptyList()
}

@Test
fun `동점은 entry id 오름차순으로 결정한다`() {
    evaluator.rank(listOf(candidate(id = 12), candidate(id = 7)), vacancy(), policy())
        .map { it.entryId } shouldBe listOf(7L, 12L)
}
```

- [ ] **Step 5: evaluator 테스트 GREEN 확인**

Run: `./gradlew :appointment-core:test --tests "*WaitlistPolicy*Test" --no-build-cache`

Expected: strict parser, eligibility reason, integer tuple, tie-breaker 모두 PASS.

- [ ] **Step 6: Lore 커밋**

```text
Make waitlist policy decisions reproducible and bounded

Constraint: Automatic delivery rules must be expressible by all supported dialects.
Rejected: Floating-point aggregate score | It weakens deterministic replay.
Confidence: high
Scope-risk: narrow
Tested: Strict policy codec and deterministic evaluator tests.
Not-tested: Database policy activation races.
```

## Task 3: policy/adjustment repository와 clinic-scope activation lock

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistPolicyRepository.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistAdjustmentRepository.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistPolicyRepositoryTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistAdjustmentRepositoryTest.kt`

- [ ] **Step 1: 최초 activation/overlap race RED 테스트 작성**

```kotlin
@Test
fun `clinic row lock 아래 최초 generation zero만 activation한다`() = transaction {
    fixtures.createClinic(tenantGroupId = 1, clinicId = 2)
    val draft = repository.insertDraft(scope, document, effectiveFrom, effectiveUntil, actor)
    repository.activate(scope, draft.id, expectedGeneration = 0, actor = actor).generation shouldBe 1
    shouldThrow<WaitlistPolicyConflict> {
        repository.activate(scope, anotherDraft.id, expectedGeneration = 0, actor = actor)
    }
}
```

- [ ] **Step 2: RED 실행**

Run: `./gradlew :appointment-core:test --tests "*WaitlistPolicyRepositoryTest" --no-build-cache`

Expected: repository 미정의로 FAIL.

- [ ] **Step 3: caller-owned transaction repository 구현**

```kotlin
fun activate(
    scope: ClinicWaitlistScope,
    policyId: Long,
    expectedGeneration: Long,
    actor: ActorRef,
    now: Instant,
): ClinicWaitlistPolicyRecord {
    Clinics.selectAll()
        .where { (Clinics.id eq scope.clinicId) and (Clinics.tenantGroupId eq scope.tenantGroupId) }
        .forUpdate().single()
    requireCurrentGeneration(scope, expectedGeneration)
    rejectOverlappingWindow(scope, policyId)
    return activateAndAppendEvent(scope, policyId, expectedGeneration + 1, actor, now)
}
```

Adjustment repository는 restriction release, recovery credit reverse/consume, benefit revoke/cap을
version CAS하고 append-only event/decision reference를 남긴다.

- [ ] **Step 4: expiry/release/reversal/cap 테스트 GREEN**

Run: `./gradlew :appointment-core:test --tests "*WaitlistPolicyRepositoryTest" --tests "*WaitlistAdjustmentRepositoryTest" --no-build-cache`

Expected: first activation, overlap rejection, stale generation, restriction expiry, credit reversal,
grant cap/revoke PASS.

- [ ] **Step 5: Lore 커밋**

```text
Serialize clinic policy changes at the existing clinic authority

Constraint: Cross-dialect overlap prevention cannot rely on one vendor-only constraint.
Confidence: high
Scope-risk: moderate
Directive: Keep every policy mutation inside the caller-owned transaction.
Tested: Policy activation and adjustment lifecycle repository tests.
Not-tested: Real PostgreSQL and MySQL concurrent activation.
```

## Task 4: durable vacancy lease와 command reservation repository

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistCommandIdempotencyKeyHasher.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryRepositoryTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistCommandReservationTest.kt`

- [ ] **Step 1: claim fence와 generation RED 테스트 작성**

```kotlin
@Test
fun `expired worker는 terminal update를 수행하지 못한다`() = transaction {
    val job = repository.insertVacancy(vacancy(generation = 1))
    val claim = repository.claim(job.id, "worker-a", now, now.plusSeconds(30))!!
    repository.completeOffer(claim, now.plusSeconds(31), offerId = 10) shouldBe false
}

@Test
fun `terminal generation 뒤에만 다음 generation을 만든다`() = transaction {
    shouldThrow<VacancyGenerationConflict> { repository.nextGeneration(activeJobId, now) }
}
```

- [ ] **Step 2: command reservation RED 테스트 작성**

```kotlin
@Test
fun `같은 key 다른 digest는 conflict다`() = transaction {
    repository.reserve(key, requestDigest = "sha256:a", now = now)
    shouldThrow<IdempotencyRequestMismatch> {
        repository.reserve(key, requestDigest = "sha256:b", now = now)
    }
}
```

같은 test class에 ASCII 16~128자 경계, domain-separated HMAC-SHA-256 secret 32 byte 미만
거부, scope/command 분리, raw key 비저장·비로그·비응답·metric-label 금지, 24시간 retention을
고정한다. secret은 `appointment.waitlist.idempotency-hmac-secret`로 주입하고 저장 key는
`hmac-sha256:` digest만 허용한다.

- [ ] **Step 3: RED 실행**

Run: `./gradlew :appointment-core:test --tests "*WaitlistDeliveryRepositoryTest" --tests "*WaitlistCommandReservationTest" --no-build-cache`

Expected: repository와 claim type 미정의로 FAIL.

- [ ] **Step 4: dialect-neutral claim/fence/CAS 구현**

```kotlin
data class VacancyClaim(val jobId: Long, val owner: String, val version: Long, val expiresAt: Instant)

fun completeOffer(claim: VacancyClaim, now: Instant, offerId: Long): Boolean =
    WaitlistVacancyJobs.update({
        (WaitlistVacancyJobs.id eq claim.jobId) and
            (WaitlistVacancyJobs.status eq VacancyJobState.PROCESSING) and
            (WaitlistVacancyJobs.leaseOwner eq claim.owner) and
            (WaitlistVacancyJobs.version eq claim.version) and
            (WaitlistVacancyJobs.leaseExpiresAt greater now)
    }) {
        it[status] = VacancyJobState.OFFERED
        it[resultOfferId] = offerId
        it[version] = claim.version + 1
    } == 1

sealed interface CommandReservation {
    data class Acquired(val recordId: Long) : CommandReservation
    data class InProgress(val retryAfterSeconds: Long = 1) : CommandReservation
    data class ReplaySucceeded(val status: Int, val resultBody: String) : CommandReservation
    data class ReplayFailed(val status: Int, val errorBody: String) : CommandReservation
}
```

PostgreSQL/MySQL claim은 locked selection adapter, H2는 version update adapter로 분리하되
repository public contract는 동일하게 유지한다.

- [ ] **Step 5: repository 테스트 GREEN**

Run: `./gradlew :appointment-core:test --tests "*WaitlistDeliveryRepositoryTest" --tests "*WaitlistCommandReservationTest" --no-build-cache`

Expected: fence loss no-op, one active generation/entry key, PROCESSING/SUCCEEDED/FAILED replay PASS.

추가 RED/GREEN fixture는 reservation 직후 process loss를 만들어 stable `FAILED` replay를,
appointment insert 직후 result 저장 전 process loss를 만들어 offer ID 기반 `SUCCEEDED`
reconcile을 증명한다. retryable SQLSTATE `40001`/`40P01`은 deterministic jitter clock으로
최대 3회 재시도하고 2초 lock wait 또는 재시도 소진은 `WaitlistContention`으로 닫는다.

- [ ] **Step 6: Lore 커밋**

```text
Keep vacancy and command authority durable across worker loss

Constraint: Redis leadership cannot authorize a terminal database write.
Rejected: In-memory idempotency cache | It cannot survive process loss.
Confidence: high
Scope-risk: moderate
Tested: Vacancy fencing, generation, and command reservation tests.
Not-tested: Dialect-specific concurrent claim integration.
```

## Task 5: globally ordered candidate query와 policy snapshot

**Files:**
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistCandidateMatcher.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistDecisionService.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistCandidateMatcherTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDecisionServiceTest.kt`

- [ ] **Step 1: global winner와 4x100 cutoff RED 테스트 작성**

```kotlin
@Test
fun `첫 page 첫 row가 전체 eligible 후보의 deterministic winner다`() = transaction {
    fixtures.insertCandidates(450, highestRankedId = 449)
    val page = matcher.findCandidates(vacancy, WaitlistCandidateRequest(pageSize = 100, maxPages = 4))
    page.candidates.first().entry.id shouldBe 449L
}
```

- [ ] **Step 2: RED 실행**

Run: `./gradlew :appointment-core:test --tests "*WaitlistCandidateMatcherTest" --no-build-cache`

Expected: 기존 waiting-age keyset ordering으로 winner assertion FAIL.

- [ ] **Step 3: repository projection/order contract 구현**

```kotlin
data class RankedWaitlistCandidateRow(
    val entry: WaitlistEntryRecord,
    val eligibilityDigest: String,
    val scoreTuple: List<Long>,
    val policyVersion: Long,
    val policyDigest: String,
)

fun findRankedCandidatePage(
    vacancy: VacancyDescriptor,
    policy: ClinicWaitlistPolicyRecord,
    cursor: RankedWaitlistCursor?,
    limit: Int = 100,
): List<RankedWaitlistCandidateRow>
```

Query는 scope/state/treatment/doctor/time predicate와 active offer/restriction anti-join을 먼저
적용하고 score tuple 전체 + entry ID로 정렬한다. Kotlin service는 lock 재검증 실패 후보만
다음 cursor로 건너뛴다.

- [ ] **Step 4: stale preview와 override permission 테스트 추가**

```kotlin
@Test
fun `override는 hard eligibility를 우회하지 않는다`() {
    shouldThrow<WaitlistOverrideRejected> {
        decisionService.override(defaultWinner, ineligibleCandidate, actorWithOverridePermission)
    }
}
```

- [ ] **Step 5: matcher/decision 테스트 GREEN**

Run: `./gradlew :appointment-core:test --tests "*WaitlistCandidateMatcherTest" --tests "*WaitlistDecisionServiceTest" --no-build-cache`

Expected: global winner, scan cutoff, stale digest, typed override audit PASS.

- [ ] **Step 6: Lore 커밋**

```text
Select the deterministic global waitlist winner without unbounded scans

Constraint: Candidate ordering must be equivalent on H2, PostgreSQL, and MySQL.
Confidence: medium
Scope-risk: broad
Directive: Recheck the selected row under the canonical mutation lock order.
Tested: Candidate ordering, cutoff, stale preview, and override tests.
Not-tested: Real-dialect query plans and scale fixture.
```

## Task 6: offer/hold/outbox/vacancy orchestration과 expiry progression

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistOfferNotificationPort.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistDeliveryService.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistOfferService.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistOfferClaimService.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistRecoveryService.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryServiceTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistExpiryProgressionTest.kt`

- [ ] **Step 1: atomic orchestration RED 테스트 작성**

```kotlin
@Test
fun `outbox 실패는 offer hold vacancy terminal을 모두 rollback한다`() = transaction {
    outbox.failNextInsert()
    shouldThrow<OutboxWriteFailed> { service.process(claim, now) }
    repository.findActiveOffer(vacancyKey) shouldBe null
    repository.findJob(claim.jobId)!!.status shouldBe VacancyJobState.PROCESSING
}
```

- [ ] **Step 2: expiry/withdraw next-generation RED 테스트 작성**

```kotlin
@Test
fun `expired offer는 유효 slot에 다음 generation을 만든다`() = transaction {
    val result = service.expireOffer(scope, offerId, clinicClock.instant())
    result.nextGeneration shouldBe 2
    result.reasonCode shouldBe WaitlistReasonCode("OFFER_EXPIRED")
}
```

- [ ] **Step 3: RED 실행**

Run: `./gradlew :appointment-core:test --tests "*WaitlistDeliveryServiceTest" --tests "*WaitlistExpiryProgressionTest" --no-build-cache`

Expected: service 미정의로 FAIL.

- [ ] **Step 4: canonical lock order와 원자적 process 구현**

Task 6에서 core-owned `WaitlistOfferNotificationDraft`와
`fun interface WaitlistOfferNotificationPort { fun enqueue(draft: WaitlistOfferNotificationDraft) }`
를 먼저 정의한다. draft는 opaque ID/reason/correlation만 포함하며 Spring/event/provider type을
참조하지 않는다. `WaitlistDeliveryService`는 이 port만 호출하고 Task 7은 adapter만 구현한다.
`./gradlew :appointment-core:dependencies` 결과에서 `appointment-event`와
`appointment-notification` 의존이 없음을 확인한다.

```kotlin
fun process(claim: VacancyClaim, now: Instant): WaitlistDeliveryResult {
    repository.requireValidFence(claim, now)
    val vacancy = repository.lockVacancy(claim.jobId)
    val winner = decisionService.selectWinner(vacancy)
        ?: return repository.completeNoCandidate(claim, now)
    val created = offerService.createOfferAndHold(winner.scope, winner.entry, winner.offer, winner.hold, now)
    decisionAuditPort.append(winner.decision)
    notificationPort.enqueue(WaitlistOfferNotificationDraft.of(created, winner, now))
    check(repository.completeOffer(claim, now, created.offerId)) { "LEASE_FENCED" }
    return WaitlistDeliveryResult.Offered(created.offerId)
}
```

Lock 호출 순서는 command → vacancy → offer → entry → hold → appointment → resource다.
기존 `WaitlistOfferClaimService`의 claim/release/expiry/recovery 경로도 이 순서로 변경한다.
lock-order recorder test와 confirm/decline/withdraw/expiry 병행 test는 역순 획득 0건을
검증한다. claim transaction A를 commit한 직후 process loss를 주입하고 lease 만료 뒤
transaction B가 중복 terminal effect 없이 reclaim하는 integration fixture도 필수다.

- [ ] **Step 5: core delivery/expiry GREEN**

Run: `./gradlew :appointment-core:test --tests "*WaitlistDeliveryServiceTest" --tests "*WaitlistExpiryProgressionTest" --no-build-cache`

Expected: rollback atomicity, no-candidate, decline/withdraw/expiry N+1, slot-start terminal PASS.

- [ ] **Step 6: Lore 커밋**

```text
Make one database transaction own each waitlist offer decision

Constraint: Offer, hold, decision audit, outbox, and vacancy completion are one unit.
Confidence: high
Scope-risk: broad
Tested: Delivery atomicity and expiry progression tests.
Not-tested: Spring event and notification provider integration.
```

## Task 7: `SlotAvailable` fast signal과 waitlist notification outbox

**Files:**
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/SlotAvailable.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistSlotAvailablePublisher.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxAdapter.kt`
- Create: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/SlotAvailableTest.kt`
- Create: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxAdapterTest.kt`

- [ ] **Step 1: opaque event와 outbox payload RED 테스트 작성**

```kotlin
@Test
fun `SlotAvailable은 member와 appointment detail을 노출하지 않는다`() {
    SlotAvailable::class.memberProperties.map { it.name }.toSet() shouldBe
        setOf("vacancyJobId", "tenantGroupId", "clinicId", "correlationId", "occurredAt")
}
```

- [ ] **Step 2: RED 실행**

Run: `./gradlew :appointment-event:test --tests "*SlotAvailableTest" --tests "*WaitlistNotificationOutboxAdapterTest" --no-build-cache`

Expected: event/adapter 미정의로 FAIL.

- [ ] **Step 3: after-commit publisher와 outbox adapter 구현**

```kotlin
data class SlotAvailable(
    val vacancyJobId: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val correlationId: String,
    val occurredAt: Instant,
)

fun interface WaitlistSlotAvailablePublisher {
    fun publishAfterCommit(event: SlotAvailable)
}
```

Publisher는 `TransactionSynchronization.afterCommit`에서 Spring event를 발행하고 실패를
metric/log로 남기되 예약 transaction을 되돌리지 않는다.
`WaitlistNotificationOutboxAdapter`는 Task 6의 core-owned
`WaitlistOfferNotificationPort`를 구현하고 canonical JSON/outbox row로 변환한다. event
module은 새 core contract를 소유하거나 core에 역의존을 만들지 않는다.

- [ ] **Step 4: event 테스트 GREEN**

Run: `./gradlew :appointment-event:test --tests "*SlotAvailableTest" --tests "*WaitlistNotificationOutboxAdapterTest" --no-build-cache`

Expected: opaque payload, after-commit only, canonical outbox codec/audit adapter PASS.

- [ ] **Step 5: Lore 커밋**

```text
Use events only to accelerate durable vacancy work

Constraint: The vacancy job remains the recovery authority after event loss.
Confidence: high
Scope-risk: narrow
Tested: SlotAvailable payload and outbox adapter tests.
Not-tested: Full application transaction synchronization.
```

## Task 8: notification pre-send fence와 provider IO 분리

**Files:**
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/WaitlistOfferNotificationWorker.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/WaitlistOfferNotificationStore.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationTemplateCatalog.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/WaitlistOfferNotificationWorkerTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/WaitlistOfferNotificationLifecycleTest.kt`

- [ ] **Step 1: terminal suppression와 provider-crossing-expiry RED 테스트**

```kotlin
@Test
fun `provider 호출 직전 만료되면 전송하지 않는다`() {
    store.claimResult = claimedOffer(expiresAt = now.plusMillis(50))
    clock.advance(Duration.ofMillis(51))
    worker.runOnce()
    verify(exactly = 0) { channel.send(any()) }
    store.recordedOutcome shouldBe DeliveryOutcome.SUPPRESSED
}
```

- [ ] **Step 2: RED 실행**

Run: `./gradlew :appointment-notification:test --tests "*WaitlistOfferNotification*Test" --no-build-cache`

Expected: worker/store 미정의로 FAIL.

- [ ] **Step 3: claim → resolve → pre-send CAS → IO → result CAS 구현**

```kotlin
fun runOnce(): WaitlistDeliveryAttempt {
    val claim = store.claim(clock.instant()) ?: return WaitlistDeliveryAttempt.Idle
    val resolved = profileResolver.resolve(claim.memberId)
    if (!store.authorizeSend(claim, clock.instant())) return WaitlistDeliveryAttempt.Suppressed
    val result = channel.send(render(claim, resolved), deadline = claim.deliveryDeadline)
    store.recordResult(claim, result, clock.instant())
    return result.toAttempt()
}
```

`authorizeSend`와 `recordResult`만 transaction을 열고 profile/provider 호출 동안 DB
transaction을 유지하지 않는다. unknown은 manual-review로 남고 자동 재전송하지 않는다.

- [ ] **Step 4: auto-configuration 조건 검증**

`@ConditionalOnProperty(prefix="appointment.waitlist.delivery", name=["enabled"], havingValue="true")`,
`@ConditionalOnBean`과 `@ConditionalOnMissingBean` 순서를 기존 notification pattern에 맞춘다.

- [ ] **Step 5: notification 테스트 GREEN**

Run: `./gradlew :appointment-notification:test --tests "*WaitlistOfferNotification*Test" --tests "*NotificationAutoConfigurationTest" --no-build-cache`

Expected: external IO transaction 밖, suppression, late result no-revive, timeout/bulkhead, feature-off PASS.

- [ ] **Step 6: Lore 커밋**

```text
Prevent notification latency from holding appointment database locks

Constraint: A provider result can never accept or revive an offer.
Confidence: high
Scope-risk: moderate
Tested: Waitlist notification lifecycle and auto-configuration tests.
Not-tested: Real provider latency fixture.
```

## Task 9: appointment state transition, confirm exactly-once와 application ports

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistApplicationPorts.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistApplicationService.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistVacancyAtomicityTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistConfirmServiceTest.kt`

- [ ] **Step 1: cancellation/vacancy atomicity RED 테스트**

```kotlin
@Test
fun `당일 cancellation은 같은 transaction에서 하나의 vacancy job을 만든다`() {
    service.cancelConfirmedAppointment(scope, appointmentId, command)
    transaction { deliveryRepository.countBySourceTransition(appointmentId, "CANCELLED") } shouldBe 1
}
```

- [ ] **Step 2: confirm exactly-once RED 테스트**

```kotlin
@Test
fun `같은 confirm 두 번은 같은 replacement appointment를 반환한다`() {
    val first = service.confirm(scope, offerId, key, request)
    val second = service.confirm(scope, offerId, key, request)
    second.appointmentId shouldBe first.appointmentId
    appointmentRepository.countReplacementForOffer(offerId) shouldBe 1
}
```

- [ ] **Step 3: RED 실행**

Run: `./gradlew :appointment-api:test --tests "*WaitlistVacancyAtomicityTest" --tests "*WaitlistConfirmServiceTest" --no-build-cache`

Expected: application service 미정의로 FAIL.

- [ ] **Step 4: short reservation + business transaction 구현**

```kotlin
data class TenantScope(val tenantGroupId: Long, val tenantCode: String, val clinicId: Long)

data class ConfirmWaitlistOfferCommand(
    val expectedVersion: Long,
    val confirmationSource: String,
    val requestDigest: String,
)

fun confirm(scope: TenantScope, offerId: Long, key: String, command: ConfirmWaitlistOfferCommand): AppointmentReference {
    return when (val reservation = commandStore.reserve(scope, CONFIRM, key, command.requestDigest)) {
        is CommandReservation.ReplaySucceeded -> decodeAppointmentReference(reservation.resultBody)
        is CommandReservation.InProgress -> throw IdempotencyInProgress(retryAfterSeconds = reservation.retryAfterSeconds)
        is CommandReservation.ReplayFailed -> throw StoredCommandFailure(reservation.status, reservation.errorBody)
        is CommandReservation.Acquired -> transaction {
            confirmUnderCanonicalLocks(scope, offerId, command, reservation)
        }
    }
}
```

Crash reconciliation은 offer ID로 replacement appointment를 조회해 command를 SUCCEEDED로
복구하고, 생성되지 않았으면 FAILED stable result로 닫는다.

동일한 주입 `Clock`과 clinic IANA zone을 cancellation, expiry, scheduler, controller에
전달한다. cancellation same-day/start-before, DST overlap의 저장 slot `Instant`, DST gap
request rejection, clinic-local date 기준 expiry/withdraw N+1 generation을 RED/GREEN test로
고정한다. lock contention test는 retryable SQLSTATE, 3회 소진, jitter/backoff clock과
`WAITLIST_CONTENTION` HTTP mapping을 포함한다.

- [ ] **Step 5: application 테스트 GREEN**

Run: `./gradlew :appointment-api:test --tests "*WaitlistVacancyAtomicityTest" --tests "*WaitlistConfirmServiceTest" --no-build-cache`

Expected: cancellation/no-show atomicity, event-loss durability, same/different-key concurrency,
occupied slot conflict, crash reconciliation PASS.

- [ ] **Step 6: Lore 커밋**

```text
Bind waitlist confirmation to appointment creation exactly once

Constraint: Command reservation survives the business transaction and process loss.
Confidence: high
Scope-risk: broad
Tested: Vacancy atomicity and confirm idempotency tests.
Not-tested: HTTP authorization and three-dialect races.
```

## Task 10: staff DTO, controller, scope factory와 JWT permission

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistTenantScopeFactory.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistPublicIdCodec.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistApiException.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/WaitlistApiErrorResponse.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/WaitlistRequests.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/WaitlistResponses.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/WaitlistController.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/WaitlistPolicyController.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/WaitlistOperationsController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/WaitlistControllerTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/WaitlistSecurityIntegrationTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/WaitlistOpenApiTest.kt`

- [ ] **Step 1: role/scope/idempotency RED 테스트 작성**

```kotlin
@Test
fun `다른 clinic STAFF token은 403이다`() {
    mvc.post("/api/tenant-a/clinics/2/waitlist/entries") {
        with(jwtStaff(clinicId = 1)); header("Idempotency-Key", validKey); json(entryRequest)
    }.andExpect { status { isForbidden() }; jsonPath("$.reasonCode") { value("AUTH_SCOPE_DENIED") } }
}
```

- [ ] **Step 2: RED 실행**

Run: `./gradlew :appointment-api:test --tests "*WaitlistControllerTest" --tests "*WaitlistSecurityIntegrationTest" --no-build-cache`

Expected: controller/routes 미정의로 404/compilation FAIL.

- [ ] **Step 3: scope resolver와 controller 구현**

```kotlin
@PostMapping("/offers/{offerRef}/confirm")
fun confirm(
    @PathVariable tenantCode: String,
    @PathVariable clinicId: Long,
    @PathVariable offerRef: String,
    @RequestHeader("Idempotency-Key") key: String,
    @Valid @RequestBody request: ConfirmOfferRequest,
): ResponseEntity<AppointmentReferenceResponse>
```

public API의 entry/offer/policy/adjustment/appointment reference는 opaque `String`이다.
`WaitlistPublicIdCodec`은 tenant/clinic scope와 resource kind를 포함한 versioned reference를
검증해 내부 `Long` ID로 변환하며 controller 밖으로 raw numeric ID를 노출하지 않는다.
`e-1`/`o-1` canonical fixture, malformed/wrong-kind/wrong-scope reference 404, encode/decode
round-trip과 OpenAPI `type:string`/example을 검증한다. application/core port는 내부 `Long`을
유지하고 controller만 `offerRef`를 decode해 Task 9의 `confirm(..., offerId: Long, ...)`을 호출한다.
confirm success/replay OpenAPI test는 `appointmentRef`와 `offerRef`가 모두 `type:string`이고
numeric raw ID가 없으며 replay body가 최초 opaque reference body와 byte-equivalent임을 고정한다.

`TenantScope`는 Task 9의 application port type을 재사용한다. `WaitlistTenantScopeFactory`는
새 권한 모델을 만들지 않고 기존 `TenantClinicAccessChecker`와 `ActorContextResolver`를
합성해 issuer/audience/subject/path tenant/clinic/role을 검증한다. waitlist 전용 matcher를
generic `/api/{tenantCode}/**`보다 먼저 두고 entries/offers/decision/audit/backlog/policy/
adjustment/requeue/suppress 전 endpoint family에 clinic membership와 required capability를
적용한다. unknown tenant/hidden clinic/cross-scope resource ID는 404, 유효 tenant의 다른
clinic staff는 403이며 repository predicate도 동일 scope를 강제한다. override는
`WAITLIST_OVERRIDE` permission을 별도 확인한다.

- [ ] **Step 4: signed cursor와 redaction 테스트 작성/구현**

```kotlin
@Test
fun `clinic STAFF decision에는 approval reference를 노출하지 않는다`() {
    mvc.get("$base/offers/o-1/decision") { with(jwtStaff(1)) }
        .andExpect { jsonPath("$.benefitApprovalRef") { doesNotExist() } }
}
```

`WaitlistApiErrorResponse(reasonCode, correlationId, retryable, retryAfterSeconds?)`와 stable
reason registry를 waitlist handler에만 적용하고 내부 score/PII를 제외한다. 모든 mutation은
ASCII 16~128자의 required `Idempotency-Key`를 사용하며 누락/blank/15/129/non-ASCII는
`400 INVALID_IDEMPOTENCY_KEY`다. 각 mutation endpoint별로 first success, byte-equivalent
success replay(`Idempotent-Replay: true`), in-progress replay(`202`, `Retry-After: 1`, replay
header 없음), failed stable replay, digest mismatch `409`를 표 기반 parameterized test로
고정한다.

entries/offers/audits/backlog query는 default limit 50, max 100과 signed
`{v,updatedAt,id,filterDigest}` cursor를 사용한다. tamper/version/filter/audience/scope 변경은
`400 INVALID_CURSOR`, 마지막 page는 `nextCursor=null`이며 stable keyset ordering을 검증한다.
redaction matrix는 ADMIN, same-clinic STAFF, unrelated staff, patient를 모두 다루고 decision,
audit, override before/after snapshot을 포함한다. auth denial/cross-scope/override attempt/
idempotency mismatch/malformed policy는 bounded reason, actor audit ref, correlation, scope만
가진 security audit를 남기며 raw token/key/contact/clinical note가 response/error/outbox/
event/log/metric label 어디에도 나타나지 않는 fixture를 둔다.

DTO inventory는 spec §9의 endpoint별 request/response, required/nullability, `expectedVersion`
body precondition을 표로 옮긴다. 이 API는 batch/client command body와 원자적으로 digest하기
위해 `If-Match` 대신 required `expectedVersion`을 사용한다. `/v3/api-docs` test는 모든 path의
success/replay/validation/auth/conflict example, required header, response header, schema ref와
PII field 부재를 검증한다. `201` response는 body reference를 안정적 navigation contract로
사용하며 별도 `Location` header는 제공하지 않는다고 문서화한다.

- [ ] **Step 5: HTTP/OpenAPI GREEN 확인**

Run: `./gradlew :appointment-api:test --tests "*WaitlistControllerTest" --tests "*WaitlistSecurityIntegrationTest" --tests "*WaitlistOpenApiTest" --no-build-cache`

Expected: 401/403/404/409/503, canonical JSON/replay headers, signed cursor, ADMIN/STAFF/override matrix,
redaction/OpenAPI examples PASS.

- [ ] **Step 6: Lore 커밋**

```text
Expose waitlist operations without weakening tenant and clinic boundaries

Constraint: Path scope, JWT claims, and repository predicates must agree.
Confidence: high
Scope-risk: broad
Directive: Keep patient self-service outside this phase-two API.
Tested: Waitlist controller, security, cursor, redaction, and OpenAPI tests.
Not-tested: Scheduler and real dialect migrations.
```

## Task 11: scheduler, feature mode matrix, metrics와 health

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryProperties.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryScheduling.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryMetrics.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryHealthIndicator.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistRetentionRunner.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- Modify: `appointment-api/src/main/resources/application.yml`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliverySchedulingTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryHealthTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistCommandRecoveryTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistRetentionRunnerTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ActuatorSecurityIntegrationTest.kt`

- [ ] **Step 1: flag mode와 leader loss RED 테스트**

```kotlin
@Test
fun `global off에서도 expiry suppression reconcile은 실행한다`() {
    runner.tick(mode = DeliveryMode.GLOBAL_OFF)
    verify(exactly = 0) { vacancyDispatcher.dispatch(any()) }
    verify { expiryRunner.run(any()); suppressionRunner.run(any()); holdReconciler.run(any()) }
}
```

- [ ] **Step 2: RED 실행**

Run: `./gradlew :appointment-api:test --tests "*WaitlistDeliverySchedulingTest" --tests "*WaitlistDeliveryHealthTest" --tests "*WaitlistCommandRecoveryTest" --tests "*WaitlistRetentionRunnerTest" --tests "*ActuatorSecurityIntegrationTest" --no-build-cache`

Expected: scheduling/property/health type 미정의로 FAIL.

- [ ] **Step 3: bounded runner와 properties 구현**

```kotlin
@ConfigurationProperties("appointment.waitlist.delivery")
data class WaitlistDeliveryProperties(
    val enabled: Boolean = false,
    val clinicAllowlist: Set<Long> = emptySet(),
    val batchSize: Int = 25,
    val jobLease: Duration = Duration.ofSeconds(30),
    val maxAttempts: Int = 5,
)

enum class DeliveryMode { ACTIVE, CLINIC_DISABLED, GLOBAL_OFF }
```

각 job 전에 leader lease를 확인하고 job 단위 transaction을 사용한다. Redis outage/churn은
dispatch 중복 억제만 잃으며 DB fence가 terminal write를 막는다.
stale command recovery runner는 PROCESSING reservation을 bounded batch로 가져와 replacement
appointment 유무에 따라 SUCCEEDED 또는 stable FAILED로 닫는다.

- [ ] **Step 4: metric/health threshold 구현**

`appointment_waitlist_*` metric 이름과 `UP/DEGRADED/OUT_OF_SERVICE` 기준을 spec 그대로
구현하고 high-cardinality ID label을 거부하는 test를 둔다.

retention properties/scheduler/store는 command 24시간과 spec의 terminal vacancy/offer/audit/
adjustment TTL을 100-row transaction으로 purge한다. active/unresolved/legal/audit-hold row는
제외하고 retry/backoff, deleted/skipped/failed metric과 health detail을 검증한다.
`application.yml`은 `health,info,profileReevaluation,metrics`를 명시적으로 노출하되 waitlist
health/metrics는 anonymous를 거부한다. `ActuatorSecurityIntegrationTest`는 management-role
JWT의 성공과 anonymous 401을 모두 증명한다. alert artifact는 oldest vacancy, expired
backlog, retry exhaustion, failed job, adapter unavailable의 metric/threshold/경고-ticket/
critical-page routing과 dry-run을 고정한다.

- [ ] **Step 5: scheduling/health GREEN**

Run: `./gradlew :appointment-api:test --tests "*WaitlistDeliverySchedulingTest" --tests "*WaitlistDeliveryHealthTest" --no-build-cache`

Expected: active/allowlist/global-off matrix, lease loss, stale command reconcile, bounded retention,
authenticated actuator, failed requeue, readiness/alert threshold PASS.

- [ ] **Step 6: Lore 커밋**

```text
Keep waitlist recovery active while delivery is rolled back

Constraint: Safety schedulers continue when new dispatch and delivery are disabled.
Confidence: high
Scope-risk: moderate
Tested: Delivery mode, leader-loss, metrics, and health tests.
Not-tested: Live Redis failover.
```

## Task 12: Flyway V19 세 dialect와 실제 claim/activation matrix

**Files:**
- Create: `appointment-api/src/main/resources/db/migration/h2/V19__add_waitlist_delivery.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V19__add_waitlist_delivery.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V19__add_waitlist_delivery.sql`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/WaitlistDeliveryMigrationTestSupport.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/WaitlistDeliveryMigrationContractTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/WaitlistDeliveryDialectConcurrencyTest.kt`

- [ ] **Step 1: V19 migration RED assertion 작성**

```kotlin
fun assertWaitlistDeliveryV19(connection: Connection) {
    assertTable(connection, "scheduling_waitlist_policy_versions")
    assertTable(connection, "scheduling_waitlist_vacancy_jobs")
    assertUniqueKey(connection, "scheduling_waitlist_command_records", listOf("tenant_group_id", "clinic_id", "command_type", "key_digest"))
}
```

- [ ] **Step 2: migration RED 실행**

Run: `./gradlew :appointment-api:test --tests "*FlywayMigrationTest" --no-build-cache`

Expected: V19 table 없음 assertion FAIL.

- [ ] **Step 3: additive SQL 구현**

세 SQL은 V18 table을 rename/drop하지 않는다. PostgreSQL은 partial unique index,
MySQL/H2는 nullable `active_vacancy_key`/`active_entry_key` unique index를 사용한다. 기존
`scheduling_*` 이름을 그대로 유지한다.
ranked candidate query는 `(tenant_group_id, clinic_id, status, updated_at, id)` scope/order
index와 exclusion anti-join용 active entry/offer index를 dialect별 동등하게 만든다. 정확한
index 이름/column order를 migration support assertion에 고정한다.

- [ ] **Step 4: 세 dialect migration 순차 검증**

Run:

```bash
./gradlew :appointment-api:test --tests "*FlywayMigrationTest" --no-build-cache
./gradlew :appointment-api:test --tests "*FlywayPostgreSQLMigrationTest" --no-build-cache
./gradlew :appointment-api:test --tests "*FlywayMySQLMigrationTest" --no-build-cache
```

Expected: table/column/index/FK/check 의미 동등성 PASS. Testcontainers는 repo singleton
launcher를 사용하고 세 command를 병렬 실행하지 않는다.

- [ ] **Step 4A: dialect-first 최소 activation/claim/query contract 검증**

Task 2 전에 migration support의 JDBC fixture로 policy first activation race, expired lease
one-worker claim, ranked candidate SQL skeleton의 scope/order index와 4×100 cutoff를 세 dialect에
실행한다.

Run: `./gradlew :appointment-api:test --tests "*WaitlistDeliveryMigrationContractTest" --no-build-cache`

Expected: H2/PostgreSQL/MySQL 최소 contract PASS. 실패하면 Task 2 이후 구현을 시작하지 않는다.

- [ ] **Step 5: real-dialect concurrency와 query plan 검증**

Run: `./gradlew :appointment-api:test --tests "*WaitlistDeliveryDialectConcurrencyTest" --no-build-cache`

Expected: concurrent first activation 하나 성공, two-worker claim 하나의 fence, confirm 하나의
appointment, candidate query scope/order index plan PASS.
PostgreSQL/MySQL `EXPLAIN`은 sequential/full table scan, filesort, 4×100 cutoff 초과 candidate
row를 실패로 간주한다. H2는 결과/limit semantic을 검증한다. SQLSTATE `40001`/`40P01`,
2초 lock wait, 최대 3회 retry와 최종 `WAITLIST_CONTENTION`도 real-dialect matrix에 포함한다.

- [ ] **Step 6: Lore 커밋**

```text
Preserve waitlist delivery authority across supported databases

Constraint: H2, PostgreSQL, and MySQL must enforce equivalent active-state semantics.
Confidence: high
Scope-risk: broad
Directive: Keep V19 additive and retain every V18 scheduling table name.
Tested: Three-dialect migration, concurrency, and query-plan tests.
Not-tested: Production-size migration duration.
```

## Task 13: 성능·recovery drill과 전체 backend regression

**Files:**
- Create: `appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryScaleSimulation.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/WaitlistDeliveryPerformanceTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/WaitlistDeliveryRecoveryDrillTest.kt`

- [ ] **Step 1: scale fixture와 numeric assertion 작성**

```kotlin
val workload = WaitlistScaleWorkload(
    activeEntries = 10_000,
    pendingVacancies = 1_000,
    notificationBacklog = 5_000,
    profileP95 = 100.milliseconds,
    providerP95 = 200.milliseconds,
)
data class WaitlistScaleWorkload(
    val activeEntries: Int,
    val pendingVacancies: Int,
    val notificationBacklog: Int,
    val profileP95: Duration,
    val providerP95: Duration,
)
result.vacanciesPerMinute shouldBeGreaterThanOrEqual 300.0
result.firstOfferP95 shouldBeLessThanOrEqual 2.seconds
result.lockWaitP99 shouldBeLessThanOrEqual 500.milliseconds
```

숫자 acceptance gate는 Gradle test task에 연결된 `WaitlistDeliveryPerformanceTest`가 소유한다.
Gatling simulation은 staging 재현용 보조 artifact이며 gate가 아니다. test fixture는
PostgreSQL singleton/profile-provider stub, 60초 warmup, 5분 measurement, HDR histogram
percentile과 `build/reports/tests/waitlist-delivery-performance.json` 출력을 고정한다.

- [ ] **Step 2: performance/recovery test 실행**

Run: `./gradlew :appointment-api:test --tests "*WaitlistDeliveryPerformanceTest" --tests "*WaitlistDeliveryRecoveryDrillTest" --no-build-cache`

Expected: 300 vacancy/min, p95 2초, backlog 10분, lock p99 500ms; Redis churn 중 duplicate terminal 0;
unknown suppression/failed requeue/flag-off drain PASS.

- [ ] **Step 3: module 순차 regression**

Run:

```bash
./gradlew :appointment-core:test --no-build-cache
./gradlew :appointment-event:test --no-build-cache
./gradlew :appointment-notification:test --no-build-cache
./gradlew :appointment-api:test --no-build-cache
```

Expected: 모든 module PASS. 재시도로만 통과한 concurrency/lifecycle test는 원인을 기록하고
fresh rerun한다.

- [ ] **Step 4: affected build/static 검증**

Run: `./gradlew :appointment-core:build :appointment-event:build :appointment-notification:build :appointment-api:build --no-build-cache`

Expected: BUILD SUCCESSFUL, compiler warning/Detekt/static failure 없음.

- [ ] **Step 5: Lore 커밋**

```text
Prove waitlist delivery remains bounded under backlog and failover

Constraint: Numeric acceptance uses the approved provider and profile latency fixture.
Confidence: medium
Scope-risk: moderate
Tested: Scale, recovery drill, module tests, and affected builds.
Not-tested: Staging canary traffic.
```

## Task 14: bilingual docs, runbook, KDoc와 lesson

**Files:**
- Modify: `appointment-core/README.md`
- Modify: `appointment-core/README.ko.md`
- Modify: `appointment-event/README.md`
- Modify: `appointment-event/README.ko.md`
- Modify: `appointment-notification/README.md`
- Modify: `appointment-notification/README.ko.md`
- Modify: `appointment-api/README.md`
- Modify: `appointment-api/README.ko.md`
- Create: `docs/runbooks/waitlist-delivery.md`
- Create: `docs/api/waitlist-delivery.md`
- Create: `docs/alerts/waitlist-delivery.yml`
- Create: `docs/lessons/2026-08-03-waitlist-delivery-fencing-and-recovery.md`

- [ ] **Step 1: README/source-equivalence와 Korean KDoc 갱신**

영문/한글 README에 동일한 staff flow, feature mode matrix, config, API, metric/health와
unsupported patient self-service를 같은 heading/source structure로 기록한다. public/internal
KDoc은 한국어로 유지한다.

- [ ] **Step 2: V19 delivery runbook 작성**

Runbook에 다음 command와 expected evidence를 정확히 기록한다.

```bash
./gradlew :appointment-api:test --tests "*FlywayMigrationTest" --no-build-cache
curl -fsS -H "Authorization: Bearer ${MANAGEMENT_TOKEN}" http://localhost:8080/actuator/health/waitlistDelivery
curl -fsS -H "Authorization: Bearer ${MANAGEMENT_TOKEN}" http://localhost:8080/actuator/metrics/appointment_waitlist_oldest_vacancy_seconds
```

allowlist/global flag 변경, failed job requeue, unknown delivery suppress, rollback mode matrix,
expiry/suppression drain, reactivation `UP` gate와 evidence template을 포함한다.
V19 preflight/postcheck helper는 dialect/Flyway version, table collision, 30초 migration lock,
blocking transaction, row/index/space estimate, constraint/index count와 timestamp를 출력하고
실패 시 hold/Flyway repair 절차를 따른다. requeue/suppress curl에는 management JWT,
`Idempotency-Key`, `expectedVersion`, typed `reasonCode`, replay/conflict/audit와 post-action
backlog/health 증거를 포함한다. shadow preview는 real backlog에 no-mutation decision/audit
sample을 만들고 allowlist 진입 hold 기준을 통과해야 한다. alert YAML은 dry-run command와
warning/critical escalation destination을 포함한다.

`docs/api/waitlist-delivery.md`는 endpoint/auth/header/replay/cursor/redaction/error/rollout flag를
caller 관점에서 설명하고 root 및 API README 영문/한글 pair에서 링크한다.

- [ ] **Step 3: lesson 작성과 GNO 검증**

Lesson은 DB fence가 Redis leader보다 강한 권위인 이유, short idempotency reservation,
provider IO transaction 분리, active-key dialect 전략을 한국어로 기록한다.

Run:

```bash
gno update
gno embed --collection bluetape4k-docs
gno search "waitlist delivery fencing recovery" -c bluetape4k-docs
```

Expected: 새 lesson 또는 계획/설계 문서가 대표 검색 결과에 나타남.

- [ ] **Step 4: 문서 parity/링크/diff 검증**

Run:

```bash
git diff --check
rg -n "appointment.waitlist.delivery|waitlist-delivery" appointment-*/README*.md docs/runbooks docs/lessons
```

Expected: 영문/한글 README에 동일 contract가 존재하고 broken relative link나 미완성 표식 없음.

- [ ] **Step 5: Lore 커밋**

```text
Make waitlist delivery operable without implementation archaeology

Constraint: Reader-facing README pairs remain source-equivalent while KDoc stays Korean.
Confidence: high
Scope-risk: moderate
Directive: Update the delivery runbook whenever feature modes or recovery states change.
Tested: Documentation parity, diff check, and GNO retrieval.
Not-tested: Operator staging rehearsal.
```

## Task 15: exact-head review, PR/CI와 merge-ready handoff

**Files:**
- Review: all changed paths from `git diff --name-only origin/develop...HEAD`
- Update: GitHub PR body and Issue #170 metadata only through `gh`

- [ ] **Step 1: spec-to-task/acceptance coverage self-review**

각 spec §18 acceptance와 §19 DoD를 Task 1–14의 test/command에 일대일 매핑하고 누락이 0인지
표로 기록한다. plan 미완성 표식/type consistency scan도 실행한다.

- [ ] **Step 2: exact-head Kotlin checklist와 6-R/7-Tier review**

최신 head SHA를 고정하고 Kotlin pattern checklist, performance/stability/security/ops/API/user
관점 6-R 및 strict 7-Tier를 실행한다. 종료 조건은 P0=0/P1=0이며 P2/P3는 수정하거나 근거를
남겨 follow-up issue로 분리한다.

- [ ] **Step 3: 최종 검증**

Run:

```bash
./gradlew :appointment-core:build :appointment-event:build :appointment-notification:build :appointment-api:build --no-build-cache
git diff --check
git status --short --branch
```

Expected: BUILD SUCCESSFUL, diff check PASS, intended branch만 ahead이며 worktree clean.

- [ ] **Step 4: PR 생성과 CI 확인**

Base `develop`, head `feat/issue-170-waitlist-delivery`, English PR title/body를 사용하고 Issue #170의
assignee/milestone/labels를 맞춘다. PR body 마지막 section은 `## DoD Status`다. live PR body,
exact head, review thread, mergeability, required CI가 모두 green인지 재조회한다.

- [ ] **Step 5: fresh merge 승인 gate**

사용자에게 exact PR head와 CI/review 증거를 제시하고 fresh `승인`을 받기 전에는 merge하지
않는다. 승인 후 merge, local `develop` sync, worktree/branch cleanup, upstream parity를 검증한다.

---

## Spec traceability

| Spec acceptance/DoD | Plan task와 증거 |
|---|---|
| cancellation/no-show durable vacancy atomicity | Task 9 `WaitlistVacancyAtomicityTest` |
| event loss/restart recovery | Task 7 after-commit test, Task 11 leader/recovery test, Task 13 drill |
| hard eligibility before scoring | Task 2 evaluator tests, Task 5 ranked query tests |
| deterministic decision snapshot/audit | Task 2, Task 5, Task 6 atomic audit |
| candidate scope index/cutoff/query plan | Task 5 global winner, Task 12 real-dialect plan, Task 13 scale |
| offer/hold/outbox/vacancy atomicity | Task 6 rollback test |
| notification failure/unknown no state corruption | Task 8 lifecycle tests |
| exactly-once confirm | Task 4 reservation, Task 9 confirm tests, Task 12 dialect race |
| command/generation/lock/fence concurrency | Task 4, Task 6, Task 12 |
| expiry/stale/occupied conflict | Task 6 expiry, Task 9 confirm, Task 10 HTTP mapping |
| expiry/withdraw next-generation and clinic DST | Task 6 progression tests, Task 9 injected clock fixture |
| staff scope/role/idempotency/redaction | Task 10 security/controller/OpenAPI tests |
| opaque public reference/internal ID boundary | Task 10 public ID codec/controller/OpenAPI tests |
| JWT and mutation DTO/status/example contract | Task 10 negative matrix/OpenAPI |
| leader failover/Redis non-authority | Task 11 leader-loss tests, Task 13 drill |
| stale command recovery와 retention | Task 4 crash fixture, Task 11 recovery/retention tests |
| readiness/runbook/no-policy/flag-off recovery | Task 11 health/mode tests, Task 14 runbook |
| numeric performance baseline | Task 13 performance fixture |
| H2/PostgreSQL/MySQL + module tests | Task 12 migration/concurrency, Task 13 regression |
| Korean KDoc, requirements/runbook, bilingual README | Task 14 |
| exact-head 6-R/7-Tier and PR lifecycle | Task 15 |

| Spec DoD | Plan closure |
|---|---|
| 2-R P0=0/P1=0 | 계획 작성 전 완료, baseline spec commit `3c67167`; 3-R contract repairs는 이 plan commit에 포함 |
| 3-R mapping/P0=0/P1=0 | 6개 최신 독립 관점 모두 P0=0/P1=0 |
| TDD RED/GREEN | Task 1–13의 각 RED/GREEN command |
| docs/metric/health sync | Task 11, Task 14 |
| implementation 6-R/7-Tier | Task 15 Step 2 |
| lesson과 GNO retrieval | Task 14 Step 3 |
| Issue/PR/CI | Task 15 Step 4 |
| fresh merge approval/sync/cleanup | Task 15 Step 5 |

## 3-R 검토 종료 조건

- 모든 spec acceptance/DoD가 Task 1–15에 매핑된다.
- task ordering에 later-artifact dependency가 없다.
- success/failure/edge/concurrency/lifecycle/dialect/performance/security/rollback test가 exact command를 갖는다.
- README 영문/한글 parity, 한국어 KDoc, V19 runbook과 lesson이 명시된다.
- plan 최신본의 6개 독립 관점 결과가 P0=0/P1=0이다.
