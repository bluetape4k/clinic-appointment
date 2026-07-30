# 프로필 변경 기반 진행 중 예약 재평가 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CRM 프로필 변경 시 `PROPOSED`와 `HELD` 예약만 비동기로 재평가하고, `CONFIRMED` 예약과 유효한 기존 선점을 보호하면서 대규모 병원에서도 공정성과 처리 목표를 지키는 기능을 구현한다.

**Architecture:** 신뢰 검증을 통과한 최소 이벤트를 최신 revision head와 작업 inbox에 병합하고, clinic 공정 디스패처가 제한된 페이지 단위로 예약을 처리한다. 각 예약은 짧은 트랜잭션에서 현재 상태와 revision을 다시 확인하며, `HELD` 교체·해제와 outbox 기록을 원자적으로 처리한다. CRM 원본과 설명 정보는 저장하지 않고 처리 시점에 허용된 평가 결과만 조회한다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed ORM, Flyway, PostgreSQL/MySQL/H2, Micrometer, Resilience4j, Gatling, Node.js Playwright

---

## 1. 전달 범위

### 포함

- `PROPOSED`, `HELD` 예약의 프로필 변경 기반 비동기 재평가
- `CONFIRMED` 예약의 자동 변경 금지
- 최신 revision 병합, clinic 공정 처리, bounded page, lease·retry·redrive
- `HELD` 유지·원자적 교체·`PROPOSED` 복귀
- 플랫폼 기본값과 tenant/clinic override가 있는 처리 목표
- CRM 평가 결과의 처리 시점 조회와 최소 보존
- H2, PostgreSQL, MySQL 마이그레이션 및 동시성 검증
- dry run, 점진 활성화, 메트릭, health, runbook
- 한국어·영어와 light·dark theme를 지원하는 HTML·PNG 업무 흐름

### 제외

- `CONFIRMED` 예약 자동 재평가 또는 자동 변경
- CRM 원본 프로필, 객관적 특징, 점수 근거, 설명 문구의 예약 시스템 저장
- 결제·상품·CRM 데이터 정정 책임의 예약 서비스 이전
- 새로운 Gradle module, 외부 의존성, 신규 공개 REST endpoint
- sequence, class, ERD를 HTML로 전환하는 작업

## 2. 기존 구성요소 재사용 지도

| 요구사항 | 재사용할 구성요소 | 확장 방식 |
|---|---|---|
| 예약 상태 보호 | `AppointmentCommitmentRepository` | 상태·revision 조건부 갱신 추가 |
| 기존 `HELD` 선점 검증 | `ResourceAllocationRepository` | 예약 단위 active allocation 잠금·교체 추가 |
| 새 후보 계산 | `AppointmentProposalService` | 내부 재평가용 후보 계산 진입점 추출 |
| 정책 고정성 | `EffectiveSchedulingPolicySnapshots` | 기존 선점에는 pinned snapshot, 새 후보에는 현재 정책 사용 |
| 이벤트 신뢰성 | `SchedulingEventTrustVerifier` | 프로필 변경 이벤트의 발행 주체·schema·서명 검증 |
| inbox/outbox 관례 | `SchedulingInboxEvents`, `SchedulingOutboxEvents` | 전용 head/job/outcome 저장소와 outbox payload 추가 |
| worker 운영 방식 | `SchedulingPolicyWorker` | lease, 제한된 batch, retry 메트릭 구조 재사용 |
| 설정 검증 | `SchedulingPolicyProperties` | 전용 `ProfileReevaluationProperties` 추가 |
| 성능 검증 | `MultiClinicScaleSimulation` | 100 clinic·10,000 active reservation 시나리오 추가 |
| 시각 자료 계약 | `docs/visual-companions/manifest.json` | HTML companion과 4개 PNG 산출물 등록 |

`SchedulingPolicyKind`는 현재 8종의 닫힌 계약을 유지한다. 프로필 재평가 처리 목표는
기존 tenant가 아홉 번째 정책을 의무적으로 정의하게 만들지 않는다. 기존
`NOTIFICATION_AND_SLA` payload에 optional `profileReevaluationHeldTargetSeconds`와
`profileReevaluationProposedTargetSeconds`를 추가하고, clinic override에도 같은
optional 경로를 추가한다. 이전 schema 1 payload에서 필드가 없으면 platform
environment 기본값을 사용한다. compiler·validator·codec·canonical hash는
`PLATFORM → TENANT → CLINIC` 출처를 `EffectiveSchedulingPolicy.sourceByPath`에
기록한다.

새 Kotlin 타입은 필요한 module 경계보다 넓게 공개하지 않는다. core record와
event/API module 사이에서 실제로 공유되는 event·assessment 계약만 public으로 두고
KDoc과 README에 안정 계약을 설명한다. repository, worker, resolver, client 구현과
Spring wiring은 프레임워크 제약이 없는 한 `internal`로 제한한다. 각 Task의
reflection/API surface 테스트는 의도하지 않은 public 타입 증가를 거부한다.

## 3. 설정과 저장 계약

```yaml
appointment:
  profile-reevaluation:
    enabled: false
    mutation-mode: DRY_RUN
    clinic-allowlist: []
    held-target: 5m
    proposed-target: 30m
    page-size: 100
    max-appointments-per-tick: 500
    max-clinics-per-cycle: 32
    global-concurrency: 16
    clinic-concurrency: 2
    lease-duration: 30s
    lease-renew-interval: 10s
    max-attempts: 8
    max-elapsed-time: 6h
    initial-backoff: 5s
    max-backoff: 5m
    retry-jitter: 0.20
    automatic-redrive-limit: 2
    automatic-redrive-cooldown: 30m
    assessment:
      base-url: ""
      connect-timeout: 1s
      read-timeout: 3s
      schema-version: 1
      max-concurrency: 8
```

허용 범위:

- `held-target`: 1분 이상 15분 이하
- `proposed-target`: 5분 이상 120분 이하
- tenant/clinic override는 플랫폼 기본값보다 짧아지면 이미 대기 중인 작업의
  `next_attempt_at`을 앞당긴다.
- 더 긴 override는 새로 접수되는 작업부터 적용하고 이미 대기 중인 작업을 늦추지 않는다.

신규 테이블:

- `scheduling_profile_reevaluation_heads`: 환자·clinic별 최신 revision과 예약 스캔 cursor
- `scheduling_profile_reevaluation_jobs`: 상태, lease, retry, 처리 목표, redrive 정보
- `scheduling_profile_reevaluation_outcomes`: 예약별 결과와 최소 감사 정보
- `scheduling_appointments(clinic_id, patient_reference_fingerprint, id)` 대상 조회 index
- `scheduling_appointment_commitments(commitment_status, appointment_id)` 상태 join index

tenant 경계는 `scheduling_clinics(tenant_group_id, id)`를 통해 검증한다. 현재
`scheduling_appointments`에 없는 `tenant_group_id`를 이 기능만을 위해 중복
저장하지 않는다.

작업 상태는 `PENDING`, `RUNNING`, `RETRY_WAIT`, `COMPLETED`, `STALE`, `FAILED`로
제한한다. 예약별 결과는 `PROPOSAL_SUPERSEDED`, `HOLD_KEPT`, `HOLD_REPLACED`,
`FALLBACK_TO_PROPOSED`, `SKIPPED_INELIGIBLE`, `SKIPPED_UNCHANGED`만 사용한다.

## 4. 요구사항 추적표

| 설계 요구사항 | 구현 작업 | 핵심 검증 |
|---|---:|---|
| `PROPOSED`, `HELD`만 재평가 | 1, 6, 7 | `CONFIRMED` 불변 테스트 |
| 유효한 기존 선점과 만료 시각 유지 | 7 | allocation identity·expiry 비교 |
| 교체와 해제의 원자성 | 7, 10 | 동시성·rollback 통합 테스트 |
| 최신 revision만 처리 | 2, 4, 8 | out-of-order·중복 이벤트 테스트 |
| clinic 공정성·bounded work | 6, 8, 11 | 100 clinic starvation 검증 |
| 플랫폼 기본값·override | 1, 9 | 범위·precedence·due-time 테스트 |
| 개인정보 최소화 | 4, 5, 10 | schema·로그·DB 금지어 검증 |
| 처리 실패 시 예약 무변경 | 5, 7, 10 | timeout·5xx·decode failure 테스트 |
| 운영 가능한 점진 배포 | 9, 12 | dry run·allowlist·health·runbook |
| 한/영·light/dark 시각 자료 | 12 | contract·capture·pixel smoke 테스트 |

## Task 1: 재평가 상태·결과·처리 목표 계약을 순수 Kotlin으로 고정

**Files:**

- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/profile/ProfileReevaluationModel.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/ProfileReevaluationTargetResolver.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/OperationalSchedulingPolicies.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyCompiler.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyValidator.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyPayloadCodec.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyHasher.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/profile/ProfileReevaluationModelTest.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/profile/ProfileReevaluationTargetResolverTest.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyCompilerTest.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyValidatorTest.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyHashTest.kt`

- [ ] **Step 1: 허용 상태와 결과를 고정하는 실패 테스트 작성**

```kotlin
@Test
fun `확정 예약은 재평가 대상이 아니다`() {
    AppointmentCommitmentStatus.CONFIRMED.isProfileReevaluationEligible shouldBe false
    AppointmentCommitmentStatus.PROPOSED.isProfileReevaluationEligible shouldBe true
    AppointmentCommitmentStatus.HELD.isProfileReevaluationEligible shouldBe true
}
```

- [ ] **Step 2: 대상 테스트를 실행해 확장 함수와 enum 부재로 실패하는지 확인**

Run:

```bash
./gradlew :appointment-core:test \
  --tests "*.ProfileReevaluationModelTest"
```

Expected: `isProfileReevaluationEligible` 또는 신규 enum을 찾지 못해 실패한다.

- [ ] **Step 3: 닫힌 상태·결과 계약 구현**

```kotlin
enum class ProfileReevaluationJobStatus {
    PENDING, RUNNING, RETRY_WAIT, COMPLETED, STALE, FAILED
}

enum class ProfileReevaluationOutcomeType {
    PROPOSAL_SUPERSEDED,
    HOLD_KEPT,
    HOLD_REPLACED,
    FALLBACK_TO_PROPOSED,
    SKIPPED_INELIGIBLE,
    SKIPPED_UNCHANGED,
}
```

`CONFIRMED`를 포함한 다른 상태에는 대상 여부를 추가하지 않는다.

- [ ] **Step 4: 플랫폼·tenant·clinic 우선순위와 범위 실패 테스트 작성**

```kotlin
@Test
fun `clinic 값이 tenant와 플랫폼 기본값보다 우선한다`() {
    val target = resolver.resolve(
        status = AppointmentCommitmentStatus.HELD,
        platform = targets(held = 5.minutes),
        tenant = targets(held = 4.minutes),
        clinic = targets(held = 2.minutes),
    )
    target shouldBe 2.minutes
}
```

1분 미만 `HELD`, 15분 초과 `HELD`, 5분 미만 `PROPOSED`,
120분 초과 `PROPOSED`를 각각 거부하는 테스트도 작성한다.

- [ ] **Step 5: 처리 목표 resolver 구현 후 테스트 통과 확인**

`NOTIFICATION_AND_SLA`의 두 optional target을 codec·validator·hasher·compiler에
연결한다. 기존 payload fixture를 그대로 decode하면 platform default가 선택되고,
tenant/clinic 값이 있으면 precedence와 source path가 재현되는 compatibility
테스트를 추가한다. 허용 범위를 벗어난 definition은 activation 전에 거부한다.

```kotlin
data class NotificationAndSlaPolicy(
    val notificationChannels: Set<String>,
    val disruptionNoticeSeconds: Long,
    val mandatoryResponseSeconds: Long,
    val profileReevaluationHeldTargetSeconds: Long? = null,
    val profileReevaluationProposedTargetSeconds: Long? = null,
)

data class NotificationAndSlaOverride(
    val notificationChannels: OverrideValue<Set<String>>,
    val disruptionNoticeSeconds: OverrideValue<Long>,
    val profileReevaluationHeldTargetSeconds: OverrideValue<Long> = OverrideValue.Inherit,
    val profileReevaluationProposedTargetSeconds: OverrideValue<Long> = OverrideValue.Inherit,
)
```

Run:

```bash
./gradlew :appointment-core:test \
  --tests "*.ProfileReevaluationModelTest" \
  --tests "*.ProfileReevaluationTargetResolverTest" \
  --tests "*.SchedulingPolicyCompilerTest" \
  --tests "*.SchedulingPolicyValidatorTest" \
  --tests "*.SchedulingPolicyHashTest"
```

Expected: 모든 대상 테스트가 통과한다.

- [ ] **Step 6: Task 1 커밋**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/profile \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/ProfileReevaluationTargetResolver.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/policy/OperationalSchedulingPolicies.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyCompiler.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyValidator.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyPayloadCodec.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/SchedulingPolicyHasher.kt \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/profile \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyCompilerTest.kt \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyValidatorTest.kt \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/policy/SchedulingPolicyHashTest.kt
git commit -m "Keep profile reevaluation eligibility explicit

Constraint: Confirmed appointments must never enter automatic reevaluation.
Rejected: Add a ninth mandatory scheduling policy kind | Existing tenant policies must remain valid.
Confidence: high
Scope-risk: narrow
Tested: profile reevaluation model and target resolver tests
Not-tested: persistence and worker integration"
```

## Task 2: latest revision head·작업·결과 저장소 구현

**Files:**

- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/ProfileReevaluationRecords.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProfileReevaluationHeads.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProfileReevaluationJobs.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProfileReevaluationOutcomes.kt`
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ProfileReevaluationRepository.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/ProfileReevaluationRepositoryTest.kt`

- [ ] **Step 1: 최신 revision 병합과 중복 제거 실패 테스트 작성**

```kotlin
@Test
fun `같은 환자의 더 최신 revision만 실행 대상으로 남긴다`() = withTransaction {
    repository.upsertEvent(scope, revision = 7, eventId = "evt-7", occurredAt = now)
    repository.upsertEvent(scope, revision = 6, eventId = "evt-6", occurredAt = now.minusSeconds(1))
    repository.upsertEvent(scope, revision = 8, eventId = "evt-8", occurredAt = now.plusSeconds(1))

    repository.findHead(scope)!!.latestRevision shouldBe 8
    repository.findRunnableJobs(scope).single().targetRevision shouldBe 8
}
```

같은 `eventId` 재수신, 같은 revision 재수신, 이전 revision 지연 도착을 포함한다.

- [ ] **Step 2: 저장소 테스트가 신규 schema 부재로 실패하는지 확인**

Run:

```bash
./gradlew :appointment-core:test \
  --tests "*.ProfileReevaluationRepositoryTest"
```

Expected: 신규 table 또는 repository를 찾지 못해 실패한다.

- [ ] **Step 3: Exposed table과 record 구현**

복합 유일성은 다음과 같이 고정한다.

```kotlin
uniqueIndex(
    tenantGroupId,
    clinicId,
    patientReferenceFingerprint,
)
```

작업에는 `targetRevision`, `assessmentRef`, `assessmentHash`, `status`, `dueAt`,
`targetDuration`, `targetPolicyRef`, `targetPolicyGeneration`, `nextAttemptAt`,
`leaseOwner`, `leaseExpiresAt`, `attemptCount`, `firstAttemptAt`, `redriveCount`,
`rootJobId`, `redriveOfJobId`, `redriveGeneration`, `priorityClass`,
`heldCursorAppointmentId`, `proposedCursorAppointmentId`와 처리 결과별 bounded count를
저장한다.

`priorityClass`는 `UNCLASSIFIED`, `HELD_PRESENT`, `PROPOSED_ONLY`의 닫힌 값이다.
신규 job은 우선 `HELD` 목표 시간으로 dueAt을 계산한다. 첫 claim에서 indexed
existence query로 `HELD` 존재 여부를 한 번 확인해 class를 고정하고, `PROPOSED_ONLY`면
dueAt을 해당 목표로 다시 계산하되 이미 더 이른 dueAt을 늦추지는 않는다.

- [ ] **Step 4: 원자적 upsert·lease·완료·실패 메서드 구현**

모든 Exposed 호출은 `transaction {}` 내부에서만 실행한다. 저장소 메서드는
현재 transaction을 요구하고 자체적으로 중첩 transaction을 만들지 않는다.

필수 메서드:

```kotlin
fun upsertEvent(command: UpsertProfileChange): ProfileReevaluationHeadRecord
fun claimFairJobs(command: ClaimProfileReevaluationJobs): List<ProfileReevaluationJobRecord>
fun renewLease(jobId: Long, revision: Long, leaseOwner: String): Boolean
fun advanceCursor(jobId: Long, revision: Long, leaseOwner: String, cursor: ProfileReevaluationCursor): Boolean
fun scheduleRetry(jobId: Long, revision: Long, leaseOwner: String, failureCode: String): Boolean
fun complete(jobId: Long, revision: Long, leaseOwner: String): Boolean
fun markStale(jobId: Long, observedRevision: Long, leaseOwner: String): Boolean
```

claim, renew, checkpoint, retry, stale, terminal 전이는 모두 DB
`current_timestamp`를 사용하는 repository DB-clock 경계에서 실행한다. 모든 변경은
`lease_owner` 일치와 `lease_expires_at > current_timestamp`를 같은 SQL predicate로
검증한다.

- [ ] **Step 5: CAS와 lease 만료 테스트 추가**

revision 7 작업을 실행하는 동안 head가 8로 바뀌면 revision 7의 cursor·완료 갱신이
실패해야 한다. 만료되지 않은 다른 worker의 lease는 탈취할 수 없고, 만료된 lease만
회수할 수 있어야 한다. lease를 잃은 이전 owner는 새 owner가 claim한 뒤 cursor,
retry, complete, stale 중 어느 상태도 변경할 수 없어야 한다. application clock을
앞뒤로 왜곡해도 PostgreSQL/MySQL DB time 기준 결과가 같아야 한다.

redrive는 원본 `FAILED` row를 되살리지 않는다. `(root_job_id, target_revision,
redrive_generation)` 유일 제약과 원본 상태·cooldown CAS로 새 job을 정확히 한 번
만든다. 동시 redrive와 생성 직후 worker crash를 검증한다.

- [ ] **Step 6: 저장소 테스트 통과 확인**

Run:

```bash
./gradlew :appointment-core:test \
  --tests "*.ProfileReevaluationRepositoryTest"
```

Expected: 중복·역순·CAS·lease 테스트가 모두 통과한다.

- [ ] **Step 7: Task 2 커밋**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/ProfileReevaluationRecords.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProfileReevaluationHeads.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProfileReevaluationJobs.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/ProfileReevaluationOutcomes.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ProfileReevaluationRepository.kt \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/ProfileReevaluationRepositoryTest.kt
git commit -m "Coalesce profile changes before scheduling work

Constraint: Duplicate and out-of-order events must not multiply reservation scans.
Rejected: One durable job per event | It creates avoidable backlog and stale writes.
Confidence: high
Scope-risk: moderate
Tested: repository deduplication CAS lease and retry tests
Not-tested: production database dialects"
```

## Task 3: 세 DB dialect에 V13 schema와 조회 index 추가

**Files:**

- Create: `appointment-api/src/main/resources/db/migration/h2/V13__add_profile_reevaluation.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V13__add_profile_reevaluation.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V13__add_profile_reevaluation.sql`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/ProfileReevaluationMigrationTestSupport.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ProfileReevaluationDialectIntegrationTest.kt`

- [ ] **Step 1: 세 dialect migration 실패 테스트 작성**

각 DB에서 Flyway를 적용한 뒤 신규 테이블·복합 유일 index와 예약/commitment 조회,
due claim, lease recovery index를 metadata로 확인한다. PostgreSQL과 MySQL은
bluetape4k singleton launcher를 사용하고 `@Testcontainers`는 사용하지 않는다.

- [ ] **Step 2: 테스트를 실행해 V13 부재로 실패하는지 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationDialectIntegrationTest"
```

Expected: `scheduling_profile_reevaluation_heads` 테이블 부재로 실패한다.

- [ ] **Step 3: H2 migration 작성**

head의 복합 key와 `latest_revision`, job의 due 조회
`(status, next_attempt_at, clinic_id, id)`, lease 복구
`(status, lease_expires_at, clinic_id, id)`, redrive lineage 유일 제약, outcome의
`(job_id, appointment_id)` 유일성을 명시한다. 예약에는
`(clinic_id, patient_reference_fingerprint, id)`, commitment에는
`(commitment_status, appointment_id)` index를 추가한다. 민감 원본을 담을 `JSON`,
`TEXT`, `payload`, `reason` 열은 만들지 않는다.

- [ ] **Step 4: PostgreSQL·MySQL 문법으로 동일 계약 작성**

타입과 index 문법만 dialect에 맞게 조정한다. 세 파일의 table·column·constraint
의미는 동일해야 한다.

- [ ] **Step 5: dev/test schema wiring과 smoke contract 갱신**

`DatabaseConfig`의 명시 table 목록에 신규 table을 등록해
`spring.flyway.enabled=false` 개발 환경에서도 schema가 만들어지게 한다.
`TableSchemaTest`와 전용 migration support는 Exposed/Flyway 양쪽의 3개 table,
FK, 상태 제약, unique/due/lease/redrive/예약 조회 index가 같은지 검증한다.

- [ ] **Step 6: migration과 repository 통합 테스트 통과 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationDialectIntegrationTest"
./gradlew :appointment-core:test \
  --tests "*.ProfileReevaluationRepositoryTest"
```

Expected: H2, PostgreSQL, MySQL schema 검증과 core repository 테스트가 통과한다.

- [ ] **Step 7: Task 3 커밋**

```bash
git add appointment-api/src/main/resources/db/migration/*/V13__add_profile_reevaluation.sql \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/ProfileReevaluationMigrationTestSupport.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ProfileReevaluationDialectIntegrationTest.kt
git commit -m "Keep reevaluation persistence equivalent across supported databases

Constraint: H2 PostgreSQL and MySQL remain first-class supported dialects.
Rejected: PostgreSQL-only queue primitives | They would split runtime behavior by database.
Confidence: high
Scope-risk: moderate
Tested: three-dialect Flyway integration test
Not-tested: production data volume query plans"
```

## Task 4: 신뢰 검증된 최소 CRM 이벤트를 최신 작업으로 병합

**Files:**

- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/profile/PatientSchedulingAssessmentChanged.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/profile/ProfileReferenceFingerprintValidator.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/profile/ProfileReevaluationEventService.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventTrustVerifier.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventRepository.kt`
- Create: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/profile/ProfileReevaluationEventServiceTest.kt`

- [ ] **Step 1: 이벤트 최소 필드와 거부 조건 실패 테스트 작성**

허용 필드:

```kotlin
data class PatientSchedulingAssessmentChanged(
    val eventId: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientReferenceFingerprint: String,
    val profileRevision: Long,
    val materialChange: Boolean,
    val assessmentRef: String,
    val assessmentHash: String,
    val occurredAt: Instant,
)
```

테스트는 raw profile, 특징, score, explanation, correction 필드가 contract에 없음을
reflection으로 확인한다. emitter, schema version, signature는 신뢰된 transport
envelope에서 검증한다. 잘못된 emitter, signature, schema version, clinic scope는
DB 변경 없이 거부해야 한다. `materialChange=false`는 inbox 멱등 기록만 남기고
재평가 job을 만들지 않아야 한다.

- [ ] **Step 2: 이벤트 서비스 테스트가 구현 부재로 실패하는지 확인**

Run:

```bash
./gradlew :appointment-event:test \
  --tests "*.ProfileReevaluationEventServiceTest"
```

Expected: 신규 event/service 부재로 실패한다.

- [ ] **Step 3: 검증·fingerprint·병합 순서를 구현**

처리 순서는 다음을 지킨다.

1. schema version과 emitter 허용 목록 검증
2. signature와 tenant/clinic scope 검증
3. profile event 전용 validator로 fingerprint 형식과 길이를 검증
4. `materialChange=false`이면 inbox 처리 완료만 기록
5. transaction 안에서 `assessmentRef/hash`를 포함한 head/job upsert

이 경로는 raw patient reference를 받거나 `PatientReferenceProtector`로 새 fingerprint를
계산하지 않는다. upstream이 tenant/clinic 도메인으로 만든 pseudonymous reference를
서명된 event로 전달하고, worker는 assessment 응답의 tenant/clinic/fingerprint
일치까지 확인한다.

신뢰 검증을 통과하지 못한 event는 transport 재전달을 무한 반복하지 않는다. 기존
`QuarantineEnvelopeProtector`와 `SchedulingQuarantineRepository`를 재사용해
암호화된 envelope과 bounded reason code를 멱등 격리하고 소비를 ack한다. 같은
event의 반복 격리 1건, 예약·job mutation 0건, 개인정보가 없는 경보와 runbook
연결을 테스트한다.

- [ ] **Step 4: 중복·역순·불신 이벤트 테스트 통과 확인**

Run:

```bash
./gradlew :appointment-event:test \
  --tests "*.ProfileReevaluationEventServiceTest"
```

Expected: 신뢰 검증 실패는 저장 0건, 정상 중복은 head 1건·runnable job 1건이다.

- [ ] **Step 5: Task 4 커밋**

```bash
git add appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/profile \
  appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventTrustVerifier.kt \
  appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventRepository.kt \
  appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/profile
git commit -m "Accept only trusted minimal profile change signals

Constraint: CRM remains the owner of raw profile data and explanations.
Rejected: Persist the inbound event body | It expands sensitive-data custody without scheduling value.
Confidence: high
Scope-risk: moderate
Directive: Keep patient references ephemeral until fingerprinting.
Tested: event schema trust scope deduplication and ordering tests
Not-tested: live CRM emitter integration"
```

## Task 5: 처리 시점 CRM assessment 조회 경계 구현

**Files:**

- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileAssessmentClient.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/RestClientProfileAssessmentClient.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileAssessmentContract.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/RestClientProfileAssessmentClientTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileAssessmentResilienceTest.kt`

- [ ] **Step 1: 허용 필드와 실패 분류 테스트 작성**

```kotlin
data class ProfileSchedulingAssessment(
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientReferenceFingerprint: String,
    val profileRevision: Long,
    val assessmentReference: String,
    val assessmentHash: String,
    val eligibleServiceCodes: Set<String>,
    val requiredResourceTags: Set<String>,
    val allowedTimeWindows: List<AllowedTimeWindow>,
)
```

응답 DTO에 이름, 생년월일, 진단, 특징, score, explanation, correction 같은 필드가
없음을 검증한다. tenant, clinic, fingerprint, revision, assessment reference/hash
불일치는 terminal security failure로 분류한다. timeout, 5xx와 일시적 인증 인프라
장애는 재시도 가능한 기술 실패로 분류하고, schema 불일치와 허용되지 않은 field는
자동 재시도하지 않는다.

- [ ] **Step 2: client 테스트를 실행해 구현 부재로 실패하는지 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.RestClientProfileAssessmentClientTest"
```

Expected: 신규 client와 contract 부재로 실패한다.

- [ ] **Step 3: Spring `RestClient` 기반 strict decoder 구현**

기존 Spring HTTP DSL을 사용해 connect/read timeout을 적용한다. 응답 JSON의
알 수 없는 필드는 거부하고, 허용된 집합도 길이와 원소 수를 제한한다. request와
로그에는 fingerprint, revision, correlation id만 사용한다.

`assessment.base-url`은 HTTPS 고정 host allowlist와 non-loopback·non-link-local
검증을 통과해야 한다. `assessmentRef`는 absolute URI로 해석하지 않고 opaque path
segment로 percent-encoding한다. redirect를 따르지 않고, egress allowlist와 전체
응답 byte 상한을 적용한다. absolute URL, `..`, encoded slash, redirect,
loopback/link-local/private IP, DNS rebinding과 oversized body 거부 테스트를
포함한다.

- [ ] **Step 4: 응답 body 비보존과 로그 마스킹 테스트 추가**

새 test dependency를 추가하지 않고 JDK local HTTP fixture와 Spring `RestClient`
관례를 사용한다. 성공·실패 후 job/outcome 저장소와 captured log 어디에도 응답
body와 `assessmentReference` 원문 이외의 CRM 필드가 남지 않는지 확인한다.
감사에는 event의 opaque assessment reference와 hash만 저장한다.

- [ ] **Step 5: CRM 장애 backpressure 테스트와 구현 추가**

새 dependency를 추가하지 않고 JDK `Semaphore`로 assessment 호출 동시성을
`assessment.max-concurrency` 이하로 제한한다. 연속 실패에서도 outbound in-flight
호출이 상한을 넘지 않고, 초과 작업은 외부 호출 없이 job retry backoff로
넘어가야 한다. 사용 중인 permit, 포화 거부와 timeout metric은 저카디널리티로
기록한다.

- [ ] **Step 6: client 테스트 통과 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.RestClientProfileAssessmentClientTest" \
  --tests "*.ProfileAssessmentResilienceTest"
```

Expected: strict schema, timeout, 상태 코드, revision, 비보존 테스트가 통과한다.

- [ ] **Step 7: Task 5 커밋**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileAssessmentClient.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/RestClientProfileAssessmentClient.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileAssessmentContract.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/RestClientProfileAssessmentClientTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileAssessmentResilienceTest.kt
git commit -m "Keep CRM assessment data outside reservation storage

Constraint: The worker needs scheduling constraints without taking custody of CRM profile data.
Rejected: Copy objective features into appointment storage | CRM owns their lifecycle and correction.
Confidence: high
Scope-risk: moderate
Directive: Reject unrecognized response fields instead of silently retaining them.
Tested: client schema timeout error classification revision and privacy tests
Not-tested: production CRM endpoint"
```

## Task 6: 환자별 대상 예약을 제한된 페이지로 조회

**Files:**

- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/ProfileReevaluationAppointmentQueryTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ProfileReevaluationQueryPlanTest.kt`

- [ ] **Step 1: 상태·scope·cursor 조회 실패 테스트 작성**

```kotlin
val page = repository.findProfileReevaluationCandidates(
    tenantGroupId = tenant,
    clinicId = clinic,
    patientReferenceFingerprint = fingerprint,
    afterAppointmentId = 100L,
    limit = 100,
)
```

테스트 데이터에 `PROPOSED`, `HELD`, `CONFIRMED`, 다른 tenant, 다른 clinic,
다른 fingerprint, cursor 이전 id를 섞고 `PROPOSED`와 `HELD`만 id 오름차순으로
최대 100개 반환하는지 확인한다.

- [ ] **Step 2: repository 테스트가 신규 query 부재로 실패하는지 확인**

Run:

```bash
./gradlew :appointment-core:test \
  --tests "*.ProfileReevaluationAppointmentQueryTest"
```

Expected: `findProfileReevaluationCandidates` 부재로 실패한다.

- [ ] **Step 3: keyset pagination query 구현**

offset pagination을 사용하지 않는다. `Appointments`를 `Clinics`와
`AppointmentCommitments`에 join해 `Clinics.tenantGroupId`, `Appointments.clinicId`,
`Appointments.patientReferenceFingerprint`, `AppointmentCommitments.status`와
`Appointments.id > cursor` 조건을 모두 SQL에 내린다. 조회 후 메모리 필터링이나
tenant id denormalization을 하지 않는다.

- [ ] **Step 4: query plan 기준 테스트 작성**

PostgreSQL과 MySQL의 `EXPLAIN` 결과가 `Appointments → Clinics` tenant 검증과
`AppointmentCommitments` 상태 join에서 위 복합 index를 사용하고 full table
scan으로 퇴행하지 않는지 검증한다. `HELD` 존재 확인, 예약 page, due claim,
lease recovery를 각각 독립적으로 검사하고 planner 표현 차이는 정규화한 뒤 확인한다.

- [ ] **Step 5: 조회와 plan 테스트 통과 확인**

Run:

```bash
./gradlew :appointment-core:test \
  --tests "*.ProfileReevaluationAppointmentQueryTest"
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationQueryPlanTest"
```

Expected: scope·상태·cursor·limit와 두 운영 DB의 index 사용 검증이 통과한다.

- [ ] **Step 6: Task 6 커밋**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt \
  appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/ProfileReevaluationAppointmentQueryTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ProfileReevaluationQueryPlanTest.kt
git commit -m "Bound each profile reevaluation scan by indexed keyset pages

Constraint: Large clinics may have many active appointments for one patient population.
Rejected: Offset scans or in-memory state filtering | Cost grows with backlog and risks cross-scope reads.
Confidence: high
Scope-risk: moderate
Tested: candidate query isolation pagination and database query plans
Not-tested: production statistics distribution"
```

## Task 7: `PROPOSED`·`HELD` 예약을 보호하는 원자적 재평가 구현

**Files:**

- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentCommitmentRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ResourceAllocationRepository.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentProposalService.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationDecisionService.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationDecisionServiceTest.kt`

- [ ] **Step 1: `CONFIRMED`와 기술 실패 불변 테스트 작성**

```kotlin
@Test
fun `확정 예약은 assessment가 달라져도 변경하지 않는다`() {
    service.reevaluate(confirmedAppointment, assessment)
    commitmentRepository.findById(confirmedAppointment.id) shouldBe beforeCommitment
    allocationRepository.findActive(confirmedAppointment.id) shouldContainExactly beforeAllocations
}
```

assessment 조회 실패, 후보 계산 예외, DB 충돌에서도 commitment와 allocation이
변하지 않는지 snapshot 비교로 검증한다.

- [ ] **Step 2: `HELD` 4가지 결과의 실패 테스트 작성**

다음 결과를 각각 검증한다.

1. pinned policy에서 기존 선점이 유효하면 allocation id와 expiry를 그대로 유지
2. 기존 선점이 무효이고 새 후보가 있으면 새 allocation 생성과 기존 release가 한 transaction
3. 평가 성공 후 후보가 정말 없으면 기존 release와 `PROPOSED` 복귀가 한 transaction
4. 평가·계산 기술 실패면 기존 `HELD`와 allocation을 유지

- [ ] **Step 3: 대상 테스트를 실행해 원자적 API 부재로 실패하는지 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationDecisionServiceTest"
```

Expected: 신규 decision service와 CAS 메서드 부재로 실패한다.

- [ ] **Step 4: 기존 선점 검증과 새 후보 정책을 분리**

기존 allocation 검증에는 commitment에 고정된 policy snapshot을 사용한다. 새 후보
탐색에만 현재 effective policy와 최신 assessment를 사용한다. 프로필 변경 경로가
`FUTURE_ONLY`를 우회하지 않도록 snapshot 선택 테스트를 추가한다.

- [ ] **Step 5: `PROPOSED` 갱신 CAS 구현**

현재 commitment 상태와 version, 처리 revision을 조건으로 기존 proposal을
supersede하고 새 proposal을 append한다. revision 또는 상태가 달라지면
`SKIPPED_INELIGIBLE`이나 `SKIPPED_UNCHANGED`로 끝내며 덮어쓰지 않는다.

- [ ] **Step 6: `HELD` 교체·복귀 transaction 구현**

예약과 active allocation을 같은 transaction에서 잠근다. 새 resource allocation을
먼저 유효성 검사하되, commitment CAS 성공 전에는 기존 allocation을 release하지
않는다. CAS 실패 시 전체 transaction을 rollback한다.

- [ ] **Step 7: outbox와 최소 outcome을 같은 transaction에 기록**

outcome에는 job id, appointment id, revision, outcome type, policy snapshot id,
assessment reference, assessment hash, emitter, event id, completed timestamp만
저장한다. 설명과 원본 assessment는 저장하지 않는다.

- [ ] **Step 8: decision service 테스트 통과 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationDecisionServiceTest"
./gradlew :appointment-core:test \
  --tests "*.AppointmentCommitmentRepositoryTest" \
  --tests "*.ResourceAllocationRepositoryTest"
```

Expected: 불변·유지·교체·복귀·rollback·snapshot·CAS 테스트가 통과한다.

- [ ] **Step 9: Task 7 커밋**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentCommitmentRepository.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ResourceAllocationRepository.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentProposalService.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationDecisionService.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationDecisionServiceTest.kt
git commit -m "Protect confirmed bookings and valid holds during reevaluation

Constraint: Profile changes may improve proposals but must not silently rewrite confirmed care.
Rejected: Release the old hold before finding a replacement | A transient failure would lose valid capacity.
Confidence: high
Scope-risk: broad
Directive: Validate existing holds with their pinned policy snapshot.
Tested: confirmed immutability hold keep replace fallback rollback and policy snapshot tests
Not-tested: multi-node database contention"
```

## Task 8: clinic 공정 디스패처·lease·retry·catch-up 구현

**Files:**

- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationDispatcher.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationWorker.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationRetryPolicy.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationDispatcherTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationWorkerTest.kt`

- [ ] **Step 1: clinic 공정성과 우선순위 실패 테스트 작성**

32개 이상의 clinic에 backlog를 만들고 한 cycle에서 clinic별 최대 동시 실행 수가
2를 넘지 않는지 확인한다. `UNCLASSIFIED`는 먼저 indexed `HELD` 존재 확인으로
`HELD_PRESENT` 또는 `PROPOSED_ONLY`가 되고, `HELD_PRESENT`를 우선하되
`PROPOSED_ONLY`의 대기 시간이 처리 목표를 넘기기 전에 반드시 선택되는 aging
테스트를 포함한다.

- [ ] **Step 2: stale revision·bounded tick 실패 테스트 작성**

worker가 한 tick에서 `max-appointments-per-tick`을 넘지 않는지, head revision이
바뀌면 현재 작업을 `STALE`로 끝내고 최신 작업만 이어가는지 검증한다.

- [ ] **Step 3: dispatcher 테스트를 실행해 구현 부재로 실패하는지 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationDispatcherTest" \
  --tests "*.ProfileReevaluationWorkerTest"
```

Expected: 신규 dispatcher/worker 부재로 실패한다.

- [ ] **Step 4: two-level concurrency와 fair claim 구현**

전역 semaphore와 clinic별 semaphore를 함께 사용한다. claim query는 같은 clinic의
작업을 연속으로 독점하지 않도록 clinic round-robin key와 aging score를 사용한다.
예약 처리 transaction 바깥에서 assessment와 후보 계산을 수행하고, 최종 반영 때만
짧은 transaction을 연다.

dispatcher는 claim 시점과 각 appointment commit 직전에 effective runtime gate를
다시 읽는다. `DISABLED`, clinic allowlist 제외, `DRY_RUN` downgrade,
`APPLY_PROPOSED_AND_HELD → APPLY_PROPOSED` 변경을 즉시 반영한다. 중단한 작업은
cursor를 안전하게 checkpoint하고 lease를 반납하며 bounded reason metric을 남긴다.
설정 변경 뒤 추가 mutation이 0건인지 각각 테스트한다.

- [ ] **Step 5: bounded exponential backoff와 jitter 구현**

```kotlin
val delay = min(maxBackoff, initialBackoff * 2.0.pow(attempt - 1))
val jittered = delay * random.nextDouble(1.0 - jitter, 1.0 + jitter)
```

`max-attempts` 또는 `max-elapsed-time` 중 먼저 도달한 제한에서 `FAILED`로 전이한다.
민감정보가 아닌 고정 `failureCode`만 저장한다.

- [ ] **Step 6: lease 갱신·중단·자동 redrive 구현**

`lease-renew-interval < lease-duration`을 설정 검증에서 보장한다. lease 갱신 실패
즉시 추가 예약 처리를 중단한다. 자동 redrive는 최대 2회, 30분 cooldown 후에만
허용하고 이후에는 운영자 수동 절차로 남긴다. redrive 생성은 Task 2의 lineage
유일 제약과 원본 `FAILED` 상태·cooldown CAS를 사용한다.

worker loop와 retry delay는 coroutine cancellation을 삼키지 않는다.
`CancellationException`을 즉시 다시 던지고 page/appointment 경계에서
`ensureActive()`를 호출한다. assessment 전, page transaction 사이, retry delay
중 취소와 lease 갱신 실패 뒤 mutation 0건을 검증한다.

- [ ] **Step 7: bounded catch-up 구현**

feature enable 또는 clinic allowlist 확대 시 active reservation 전체를 한 번에
enqueue하지 않는다. clinic별 keyset cursor와 동일한 page/tick 제한으로 synthetic
revision 작업을 만든다.

- [ ] **Step 8: dispatcher·worker 테스트 통과 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationDispatcherTest" \
  --tests "*.ProfileReevaluationWorkerTest"
```

Expected: 공정성·우선순위·starvation 방지·bounded tick·stale·lease·retry·redrive가 통과한다.

- [ ] **Step 9: Task 8 커밋**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationDispatcher.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationWorker.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationRetryPolicy.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationDispatcherTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationWorkerTest.kt
git commit -m "Bound reevaluation work while preserving clinic fairness

Constraint: One large clinic must not consume global worker capacity.
Rejected: FIFO across all jobs | Backlog volume would cause tenant and clinic starvation.
Confidence: high
Scope-risk: broad
Directive: Keep appointment mutations in short transactions after external assessment.
Tested: fairness priority starvation bounds stale lease retry redrive and catch-up tests
Not-tested: production scheduler clock skew"
```

## Task 9: Spring 설정·dry run·활성화·메트릭·health 연결

**Files:**

- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationProperties.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationConfiguration.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationMetrics.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationHealthIndicator.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationAdminService.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationEndpoint.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingPolicyRequests.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingPolicyResponses.kt`
- Modify: `appointment-api/src/main/resources/application.yml`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationPropertiesTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationWiringTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationMetricsTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationAdminServiceTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ProfileReevaluationEndpointSecurityTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyRequestContractTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyOpenApiTest.kt`

- [ ] **Step 1: 기본 비활성·범위·상호 제약 실패 테스트 작성**

기본값은 `enabled=false`, `mutationMode=DRY_RUN`이어야 한다. target 범위,
`leaseRenewInterval < leaseDuration`, 양수 concurrency/page/retry, jitter 0~1을
검증한다. `enabled=false`에서는 빈 assessment base URL을 허용한다. event 소비나
worker가 실행 가능한 상태에서는 HTTPS 고정 host allowlist와 egress 검증을 통과한
base URL을 필수로 한다.

- [ ] **Step 2: 환경·tenant·clinic precedence와 due-time 실패 테스트 작성**

환경설정이 플랫폼 기본값으로 binding되는지 확인한다. tenant/clinic override를
2분으로 줄이면 기존 5분 작업이 앞당겨지고, 10분으로 늘려도 기존 작업이 늦춰지지
않는지 repository spy로 검증한다.

기존 scheduling policy API의 `NOTIFICATION_AND_SLA` tenant/clinic payload에 두
optional target field를 추가한다. 필드가 없는 기존 요청과 저장 payload는 계속
유효해야 하고, response/OpenAPI는 effective 값과 `PLATFORM|TENANT|CLINIC` 출처를
보여야 한다.

- [ ] **Step 3: wiring 테스트를 실행해 bean 부재로 실패하는지 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationPropertiesTest" \
  --tests "*.ProfileReevaluationWiringTest"
```

Expected: properties/configuration bean 부재로 실패한다.

- [ ] **Step 4: feature gate와 mutation mode 구현**

`DISABLED`, `DRY_RUN`, `APPLY_PROPOSED`, `APPLY_PROPOSED_AND_HELD` 순서만 허용한다.
`DRY_RUN`은 assessment·후보 계산·결과 메트릭까지 실행하지만 commitment,
allocation, outbox를 변경하지 않는다. clinic allowlist가 비어 있으면 적용 대상이
없도록 한다.

- [ ] **Step 5: 저카디널리티 메트릭과 health 구현**

필수 메트릭:

- event accepted/rejected/stale 수
- job 상태·clinic 공정 대기 시간·처리 지연
- `PROPOSED`/`HELD` 결과별 수와 p95 처리 시간
- CRM assessment 조회 latency·오류율
- lease loss·retry·failed·redrive 수
- dry-run 예상 결과와 실제 적용 결과 차이

tenant, clinic, patient, appointment, event id는 metric tag로 사용하지 않는다.
health는 backlog age, lease 갱신 실패, assessment 연속 실패, failed job 수를
요약하며 원본 식별자를 노출하지 않는다.

- [ ] **Step 6: 감사 가능한 drain·redrive 운영 진입점 구현**

Spring Boot internal actuator `profileReevaluation` endpoint는 현재 backlog·lease와
drain 상태 조회, tenant/clinic/revision 범위의 redrive preview·execute만 제공한다.
모든 명령은 admin 권한, actor, bounded reason, idempotency key를 요구한다.
`ProfileReevaluationAdminService`가 원본 job을 직접 수정하지 않고 Task 2의
lineage/CAS 계약으로 새 attempt를 만든다. preview는 mutation하지 않는다.

disable·allowlist 축소·mode downgrade 시 drain 상태와 보존된 backlog를 확인할 수
있어야 한다. endpoint가 일반 API 보안 체계에서 접근 불가하고 actuator admin
권한에서만 허용되는지 테스트한다.

- [ ] **Step 7: 설정·wiring·metric·운영 진입점 테스트 통과 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationPropertiesTest" \
  --tests "*.ProfileReevaluationWiringTest" \
  --tests "*.ProfileReevaluationMetricsTest" \
  --tests "*.ProfileReevaluationAdminServiceTest" \
  --tests "*.ProfileReevaluationEndpointSecurityTest"
```

Expected: feature gate, override, due-time, metric tag, health redaction 테스트가 통과한다.

- [ ] **Step 8: Task 9 커밋**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationProperties.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationConfiguration.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationMetrics.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationHealthIndicator.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationAdminService.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationEndpoint.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingPolicyRequests.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/SchedulingPolicyResponses.kt \
  appointment-api/src/main/resources/application.yml \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationPropertiesTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationWiringTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationMetricsTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationAdminServiceTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ProfileReevaluationEndpointSecurityTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyRequestContractTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyOpenApiTest.kt
git commit -m "Make reevaluation rollout bounded and observable

Constraint: Hospitals need local targets without delaying already-promised processing.
Rejected: Enable held mutations with one boolean | Dry run and proposed-only rollout are required safety stages.
Confidence: high
Scope-risk: moderate
Directive: Keep metric labels free of tenant clinic patient and appointment identifiers.
Tested: configuration validation override due-time feature gate metrics and health tests
Not-tested: production alert routing"
```

## Task 10: 실제 DB 동시성·장애·개인정보 회귀 검증

**Files:**

- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ProfileReevaluationConcurrencyIntegrationTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ProfileReevaluationFailureIntegrationTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ProfileReevaluationPrivacyIntegrationTest.kt`

- [ ] **Step 1: PostgreSQL·MySQL 경쟁 조건 실패 테스트 작성**

동일 예약과 revision에 두 worker를 동시에 진입시킨다. 결과는 정확히 한 번만
적용되고, active allocation은 최대 한 세트, outcome과 outbox도 한 건이어야 한다.
다음 경쟁을 각각 검증한다.

- 재평가 중 사용자가 `HELD`를 `CONFIRMED`로 확정
- 재평가 중 선점 만료 worker가 allocation release
- revision 7 처리 중 revision 8 이벤트 도착
- lease 만료 직전 다른 worker가 같은 job claim
- 실행 중 feature disable, clinic allowlist 축소, mutation mode downgrade
- 동시 redrive와 redrive row 생성 직후 worker crash

- [ ] **Step 2: PostgreSQL·MySQL에서 테스트를 실행해 동시성 보호 누락을 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationConcurrencyIntegrationTest"
```

Expected: 최소 한 경쟁 시나리오가 중복 outcome, stale write 또는 allocation 불변 위반으로 실패한다.

- [ ] **Step 3: 필요한 lock/CAS 순서를 최소 범위로 보강**

고정 순서는 DB time 기준 job lease owner/fencing 확인 → job revision 확인 →
commitment lock/version 확인 → active allocation lock → 새 후보 최종 검증 →
commitment/allocation/outbox/outcome 반영이다.
deadlock 회피를 위해 여러 resource는 stable id 오름차순으로 잠근다.

- [ ] **Step 4: 장애 주입 테스트 작성**

assessment timeout, 후보 계산 예외, 새 allocation insert 실패, commitment CAS 실패,
outbox insert 실패, transaction commit 실패를 주입한다. 기술 실패에서는 기존
commitment/allocation이 그대로이고 retry job만 남아야 한다.

- [ ] **Step 5: 개인정보 금지 경계 테스트 작성**

event, inbox, DLQ 또는 실패 event 기록, head, job, outcome, outbox, captured
exception/log, outbound request path, metric tag, actuator health를 검사해 raw
patient reference와 다음 key가 없음을 확인한다.

```text
name, birthDate, diagnosis, feature, score, explanation, correction, rawProfile
```

- [ ] **Step 6: 동시성·장애·개인정보 테스트 통과 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "*.ProfileReevaluationConcurrencyIntegrationTest" \
  --tests "*.ProfileReevaluationFailureIntegrationTest" \
  --tests "*.ProfileReevaluationPrivacyIntegrationTest"
```

Expected: 두 운영 DB에서 중복 반영 0건, stale mutation 0건, 개인정보 노출 0건이다.

- [ ] **Step 7: Task 10 커밋**

```bash
git add appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ProfileReevaluationConcurrencyIntegrationTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/ProfileReevaluationFailureIntegrationTest.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ProfileReevaluationPrivacyIntegrationTest.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentCommitmentRepository.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ResourceAllocationRepository.kt \
  appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ProfileReevaluationRepository.kt
git commit -m "Prove reevaluation safety under real database contention

Constraint: Reservation confirmation allocation expiry and profile changes can race across nodes.
Rejected: Treat H2 concurrency tests as sufficient | Locking and isolation differ on production dialects.
Confidence: high
Scope-risk: broad
Directive: Preserve the documented lock order when extending reservation mutations.
Tested: PostgreSQL MySQL concurrency failure rollback and privacy integration tests
Not-tested: cross-region database latency"
```

## Task 11: 10,000건·100 clinic 성능과 공정성 검증

**Files:**

- Create: `appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/ProfileReevaluationScaleSimulation.kt`
- Create: `appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationGatlingFixture.kt`
- Create: `docs/benchmarks/profile-reevaluation/README.md`

- [ ] **Step 1: 재현 가능한 고정 데이터셋 fixture 작성**

두 target fixture를 만든다. `multi-clinic-target`은 100 clinic에 active
reservation 10,000건을 분산하되 한 대형 clinic에 40%를 배치한다.
`single-clinic-target`은 한 clinic에 `PROPOSED`/`HELD` active reservation
10,000건의 동시 변경 burst를 만든다. `PROPOSED` 70%, `HELD` 25%,
`CONFIRMED` 5%로 구성하고 revision 중복, 역순 이벤트, CRM 지연·오류를 고정
seed로 섞는다.

- [ ] **Step 2: safety assertion과 SLO 실패 조건 작성**

다음을 simulation 종료 조건으로 둔다.

- `CONFIRMED` mutation 0건
- active allocation 중복 0건
- cross-tenant/clinic mutation 0건
- stale revision mutation 0건
- 개인정보 persistence 0건
- `HELD` 처리 지연 p95 5분 이내
- `PROPOSED` 처리 지연 p95 30분 이내
- 모든 clinic에서 처리 진전이 있어 starvation 0건
- queue growth, worker memory와 lease expiry rate가 설정 상한 안

처리 시간은 각 작업의 절대 deadline이 아니라 전체 부하에서의 p95 목표로 평가한다.

- [ ] **Step 3: 작은 smoke profile을 실행해 fixture와 assertion 연결 확인**

Run:

```bash
./gradlew :appointment-api:gatlingRun \
  -Dgatling.simulationClass=io.bluetape4k.clinic.appointment.api.ProfileReevaluationScaleSimulation \
  -DprofileReevaluation.scale=smoke
```

Expected: 10 clinic·1,000건 smoke에서 모든 safety assertion이 통과한다.

- [ ] **Step 4: 목표 규모 profile 실행**

Run:

```bash
./gradlew :appointment-api:gatlingRun \
  -Dgatling.simulationClass=io.bluetape4k.clinic.appointment.api.ProfileReevaluationScaleSimulation \
  -DprofileReevaluation.scale=multi-clinic-target
./gradlew :appointment-api:gatlingRun \
  -Dgatling.simulationClass=io.bluetape4k.clinic.appointment.api.ProfileReevaluationScaleSimulation \
  -DprofileReevaluation.scale=single-clinic-target
```

Expected: 100 clinic 분산과 단일 clinic burst 각각 10,000건에서 안전성 위반 0건,
HELD p95 ≤ 5분, PROPOSED p95 ≤ 30분이다. 분산 fixture는 starvation 0건,
단일 clinic fixture는 queue·memory bound와 lease expiry rate 상한을 충족한다.

- [ ] **Step 5: 원시 보고서와 실행 환경 기록**

`docs/benchmarks/profile-reevaluation/README.md`에 commit SHA, JVM, CPU, memory,
DB image/version, worker 설정, seed, 결과 파일 경로, p50/p95/p99, 오류·retry 수를
기록한다. 각 profile은 DB image와 통계를 초기화한 뒤 1회 warmup, 3회 측정하고
세 측정의 median과 worst-success를 함께 판정한다. baseline 대비 허용 오차와
실패 시 재실행 조건을 고정한다. 성공 수치만 옮기지 말고 모든 원시 Gatling report
위치를 남긴다.

- [ ] **Step 6: Task 11 커밋**

```bash
git add appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/ProfileReevaluationScaleSimulation.kt \
  appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationGatlingFixture.kt \
  docs/benchmarks/profile-reevaluation/README.md
git commit -m "Measure reevaluation fairness at the expected hospital scale

Constraint: Large clinics can dominate volume while smaller clinics still need bounded progress.
Rejected: Throughput-only benchmark | It can hide starvation and unsafe mutations.
Confidence: medium
Scope-risk: moderate
Directive: Preserve raw report references and the fixed dataset seed.
Tested: 100-clinic 10000-reservation Gatling profile
Not-tested: production network and CRM latency distribution"
```

## Task 12: 운영 문서와 한/영·light/dark 업무 흐름 시각화 완성

**Files:**

- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `appointment-api/README.md`
- Modify: `appointment-api/README.ko.md`
- Modify: `appointment-event/README.md`
- Modify: `appointment-event/README.ko.md`
- Modify: `docs/api/scheduling-policy.md`
- Create: `docs/runbooks/profile-reevaluation.md`
- Create: `docs/runbooks/profile-reevaluation.ko.md`
- Create: `docs/runbooks/profile-reevaluation-alerts.yml`
- Create: `docs/superpowers/specs/2026-07-30-profile-change-reservation-reevaluation.html`
- Create: `docs/superpowers/specs/2026-07-30-profile-change-reservation-reevaluation.ko.html`
- Create: `docs/superpowers/specs/2026-07-30-profile-change-reservation-reevaluation.en.light.png`
- Create: `docs/superpowers/specs/2026-07-30-profile-change-reservation-reevaluation.en.dark.png`
- Create: `docs/superpowers/specs/2026-07-30-profile-change-reservation-reevaluation.ko.light.png`
- Create: `docs/superpowers/specs/2026-07-30-profile-change-reservation-reevaluation.ko.dark.png`
- Modify: `docs/visual-companions/manifest.json`
- Create: `scripts/capture-profile-reevaluation-visual.mjs`
- Create: `tests/visual-companions/profile-reevaluation-visual.test.mjs`

- [ ] **Step 1: runbook 필수 항목 검증 테스트 작성**

한국어·영어 runbook에 다음 항목이 모두 있는지 테스트한다.

- disabled → dry run → `PROPOSED` → `HELD` 단계
- clinic allowlist 확대·축소
- p95 목표와 backlog·lease·assessment alert
- failed job 조회와 제한된 redrive
- 개인정보 사고 시 중단·보존·조사 경계
- rollback 시 `CONFIRMED`와 기존 `HELD` 불변 확인
- 지원하지 않는 동작: `CONFIRMED` 자동 변경, CRM 원본·특징·점수·설명 보관,
  모든 개별 작업의 5분/30분 완료 보장, 제한 소진 뒤 무인 자동 redrive

두 runbook과 root/API/event README의 heading id, config key, metric name,
SQL·curl 예시, 정상·중단 기준, 지원하지 않는 동작 목록이 언어별로 같은지
source-equivalence contract로 검증한다. `appointment-event` README에는 emitter,
schema, 필드, fingerprint-only, trust·quarantine 계약을 설명한다.

- [ ] **Step 2: 시각 자료 계약 실패 테스트 작성**

두 HTML의 business flow 구조·노드 id가 같고 문구만 언어별인지 확인한다.
각 HTML은 `prefers-color-scheme`과 명시적 `data-theme=light|dark`를 지원해야 한다.
네 PNG의 크기·불투명 pixel·light/dark 평균 명도 차이와 manifest 등록을 검증한다.

- [ ] **Step 3: 문서 테스트를 실행해 산출물 부재로 실패하는지 확인**

Run:

```bash
node --test tests/visual-companions/profile-reevaluation-visual.test.mjs
node scripts/validate-visual-companions.mjs
```

Expected: HTML·PNG·manifest entry 부재로 실패한다.

- [ ] **Step 4: 운영자가 실제로 실행할 수 있는 runbook 작성**

`curl`, SQL, metric query는 현재 구현의 endpoint·table·metric 이름과 일치시킨다.
“확인한다”로 끝내지 말고 정상 기준, 중단 기준, rollback 판단, 담당 시스템을
구체적으로 적는다. 한국어는 직역투를 피하고 국내 S/W 개발자가 자연스럽게
이해할 수 있는 용어를 쓴다.

`profile-reevaluation-alerts.yml`에는 SLO burn, oldest job age, failed 증가,
lease expiry 급증, assessment 포화, quarantine 반복의 query, threshold,
for-duration, severity와 runbook anchor를 고정한다. 테스트는 YAML을 파싱해 모든
필수 alert와 유효한 runbook link를 검사한다.

- [ ] **Step 5: 기준 Markdown을 바탕으로 한국어·영어 HTML 작성**

업무 흐름은 다음 6단계와 예외 분기를 한 화면에 표현한다.

1. CRM 최소 변경 이벤트
2. 신뢰 검증·fingerprint·latest revision 병합
3. clinic 공정 dispatch
4. 처리 시점 assessment 조회
5. `PROPOSED` 갱신 / `HELD` 유지·교체·복귀 / `CONFIRMED` 건너뜀
6. outbox·최소 감사·운영 지표

색상만으로 상태를 구분하지 않고 텍스트와 형태를 함께 사용한다. 1440×900에서도
본문이 잘리지 않아야 하며, 한국어 HTML과 영어 HTML의 구조는 동일해야 한다.

- [ ] **Step 6: Playwright capture script로 네 PNG 생성**

Run:

```bash
node scripts/capture-profile-reevaluation-visual.mjs
```

Expected: en/ko × light/dark 네 PNG가 정해진 이름과 크기로 생성된다.

- [ ] **Step 7: README `<picture>`와 companion manifest 연결**

한국어 README는 한국어 PNG, 영어 README는 영어 PNG를 기본으로 사용한다.
각 `<picture>`는 dark media source와 light fallback을 제공하고 원본 HTML과
기준 설계 Markdown 링크를 함께 둔다. 언어에 맞는 구체적 alt text와 동일 언어
HTML fallback link가 있는지 validator로 확인한다.

- [ ] **Step 8: 시각·문서 계약 통과 확인**

Run:

```bash
node --test tests/visual-companions/*.test.mjs
node scripts/validate-visual-companions.mjs
git diff --check
```

Expected: 모든 visual companion 테스트, manifest validator, whitespace 검사가 통과한다.

- [ ] **Step 9: Task 12 커밋**

```bash
git add README.md README.ko.md appointment-api/README.md appointment-api/README.ko.md \
  appointment-event/README.md appointment-event/README.ko.md \
  docs/api/scheduling-policy.md \
  docs/runbooks/profile-reevaluation.md docs/runbooks/profile-reevaluation.ko.md \
  docs/runbooks/profile-reevaluation-alerts.yml \
  docs/superpowers/specs/2026-07-30-profile-change-reservation-reevaluation.html \
  docs/superpowers/specs/2026-07-30-profile-change-reservation-reevaluation.ko.html \
  docs/superpowers/specs/2026-07-30-profile-change-reservation-reevaluation.*.png \
  docs/visual-companions/manifest.json \
  scripts/capture-profile-reevaluation-visual.mjs \
  tests/visual-companions/profile-reevaluation-visual.test.mjs
git commit -m "Explain profile reevaluation in each supported reader mode

Constraint: Business workflows need Korean and English light and dark artifacts.
Rejected: Use HTML for sequence class and ERD diagrams | Their existing SVG pipeline is more precise.
Confidence: high
Scope-risk: moderate
Directive: Keep Markdown as the reference and regenerate PNGs from the paired HTML files.
Tested: visual companion contracts captures manifest validation and diff check
Not-tested: browser engines other than Chromium"
```

## Task 13: 전체 회귀·정적 검증·문서 일치성 확인

**Files:**

- Modify only if verification exposes a defect in files changed by Tasks 1–12.

- [ ] **Step 1: module별 단위·통합 테스트 실행**

Run:

```bash
./gradlew :appointment-core:test
./gradlew :appointment-event:test
./gradlew :appointment-api:test
```

Expected: 세 module의 모든 테스트가 통과한다.

- [ ] **Step 2: build와 정적 분석 실행**

Run:

```bash
./gradlew :appointment-core:build :appointment-event:build :appointment-api:build
./gradlew detekt
```

Expected: compile, test, Kover/quality gate, Detekt가 오류 없이 끝난다.

- [ ] **Step 3: 시각 자료와 문서 검증 재실행**

Run:

```bash
node --test tests/visual-companions/*.test.mjs
node scripts/validate-visual-companions.mjs
git diff --check
```

Expected: visual companion 계약과 문서 형식 검증이 통과한다.

- [ ] **Step 4: 금지 경계 정적 검색**

Run:

```bash
rg -n "rawProfile|birthDate|diagnosis|explanation|correction" \
  appointment-core/src/main appointment-event/src/main appointment-api/src/main \
  -g '*ProfileReevaluation*' -g '*profile*'
```

Expected: 허용된 strict decoder의 명시적 거부 목록을 제외하고 저장 모델, 로그,
metric, outbox에 일치 항목이 없다.

- [ ] **Step 5: 요구사항 추적표를 구현 증거와 대조**

Task 1–12의 테스트 이름과 commit SHA를 추적표 각 행에 연결한다.
`CONFIRMED` mutation 0건, 중복 allocation 0건, cross-scope 0건, stale mutation 0건,
개인정보 persistence 0건을 최종 보고서에 명시한다.

- [ ] **Step 6: 최종 커밋**

```bash
git add -A
git commit -m "Close the profile reevaluation delivery gates

Constraint: Completion requires safety privacy scale operations and documentation evidence.
Rejected: Declare completion from unit tests alone | Database contention and rollout behavior are core risks.
Confidence: high
Scope-risk: broad
Directive: Do not enable held mutations before dry-run and proposed-only evidence is accepted.
Tested: core event API builds full tests Detekt visual contracts and target-scale Gatling
Not-tested: production rollout"
```

## 5. Step 3-P 위험 검토

| 우선순위 | 위험 | 계획에 반영한 대응 | 증거 |
|---|---|---|---|
| P0 | `CONFIRMED`가 재평가 경로에서 변경됨 | eligibility 이중 확인과 실제 DB 경쟁 테스트 | Task 1, 7, 10 |
| P0 | 기존 `HELD`를 먼저 해제해 환자 선점 상실 | 새 후보 검증·CAS 후 기존 선점 release, 단일 transaction | Task 7, 10 |
| P0 | 원본 프로필·설명 정보가 예약 DB나 로그에 남음 | 최소 event, 처리 시점 strict assessment, 금지 경계 테스트 | Task 4, 5, 10 |
| P1 | 대형 clinic이 worker를 독점 | 전역+clinic별 제한, round-robin, aging | Task 8, 11 |
| P1 | 이전 revision이 최신 결과를 덮어씀 | head revision CAS, stale 상태, 예약 반영 직전 재확인 | Task 2, 8, 10 |
| P1 | 세 DB의 lock/index 동작 차이 | 동일 V13 계약, PostgreSQL·MySQL 동시성·EXPLAIN | Task 3, 6, 10 |
| P1 | CRM 장애가 예약 상태 변경으로 오인됨 | 기술 실패와 no-candidate 분리, 기술 실패 시 무변경 | Task 5, 7 |
| P1 | 짧아진 처리 목표가 기존 backlog에 반영되지 않음 | due-time advance 전용 갱신과 회귀 테스트 | Task 9 |
| P2 | retry가 무한 반복되어 backlog 확대 | 횟수·경과시간 제한, bounded redrive·cooldown | Task 8 |
| P2 | 고카디널리티 metric으로 운영 비용 증가 | 식별자 tag 금지와 metric contract 테스트 | Task 9 |
| P2 | catch-up이 신규 이벤트 처리를 압도 | 동일 page/tick 제한과 clinic cursor | Task 8 |

## 6. Step 3-R 독립 검토 결과

| 관점 | 최초 결과 | 계획에 반영한 핵심 수정 | 재검토 |
|---|---:|---|---:|
| 성능 | P0 0, P1 3, P2 2 | 실제 schema 기반 index, due/lease index, priority class, 단일 clinic 10,000건, 반복 측정 | P0 0, P1 0, PASS |
| 안정성 | P0 0, P1 4, P2 2 | lease owner fencing, DB time, runtime gate, redrive lineage, cancellation, assessment backpressure | P0 0, P1 0, PASS |
| 보안 | P0 0, P1 2, P2 1 | fingerprint-only event, ref/hash/scope 결합, SSRF·redirect·응답 크기 방어 | P0 0, P1 0, PASS |
| 운영 | P0 0, P1 2, P2 2 | 감사 가능한 actuator redrive/drain, 조건부 URL 검증, alert 계약, quarantine 절차 | P0 0, P1 0, PASS |
| 개발자 API | P1 2, P2 2 | 기존 policy payload optional override, schema wiring, event README, 무의존 HTTP fixture | P0 0, P1 0, PASS |
| 사용자 문서 | P1 2, P2 2, P3 1 | 타입 가시성, event 문서, 미지원 동작, locale 동등성, alt/fallback | P0 0, P1 0, PASS |

최초 검토의 모든 P1은 해당 Task의 파일·실패 테스트·구현 단계·검증 명령으로
반영했다. P2와 P3도 구현 비용보다 안전성·운영성·문서 정확성의 가치가 큰 항목은
모두 계획에 포함했다. 수정 뒤 여섯 검토자가 자기 관점의 P0/P1이 0임을 다시
확인했다.

## 7. 문서·공개 계약 영향

- 신규 공개 REST endpoint와 module dependency는 추가하지 않는다. 기존 scheduling
  policy API의 `NOTIFICATION_AND_SLA` payload에는 하위 호환 optional target field가
  추가된다.
- 내부 event schema가 추가되므로 `appointment-event` README에 emitter·필드·신뢰
  검증 규칙을 설명한다.
- 운영 설정이 추가되므로 `appointment-api` README에 기본값, override 범위,
  mutation mode를 설명한다.
- 최상위 README에는 업무 흐름 시각 자료와 기준 설계·runbook 링크를 추가한다.
- `CHANGELOG`, `settings.gradle.kts`, CI workflow, Kover 설정은 동작 변경이 필요할
  때만 수정하며, 이 계획 자체는 해당 변경을 요구하지 않는다.

## 8. 완료 조건

- 모든 Task의 RED → GREEN 증거와 Lore 형식 commit이 존재한다.
- `PROPOSED`, `HELD`만 재평가되고 `CONFIRMED` mutation은 모든 검증에서 0건이다.
- 유효한 `HELD`의 allocation id와 expiry가 유지된다.
- `HELD` 교체·복귀는 commitment, allocation, outbox, outcome과 원자적이다.
- 중복·역순 event와 다중 worker 경쟁에서도 latest revision만 한 번 적용된다.
- CRM 원본·특징·score·설명·정정 정보가 event body 이후 영속 계층, 로그, metric,
  outbox, health에 남지 않는다.
- 100 clinic·10,000 active reservation에서 starvation 없이 `HELD` p95 5분,
  `PROPOSED` p95 30분 목표를 충족한다.
- H2, PostgreSQL, MySQL migration·query plan·동시성 테스트가 통과한다.
- dry run → `PROPOSED` → `HELD` 점진 활성화와 rollback 절차가 runbook에 있다.
- 한국어·영어 HTML과 네 PNG가 light·dark theme 계약 및 README 연결을 충족한다.
- `:appointment-core`, `:appointment-event`, `:appointment-api` build, 전체 테스트,
  Detekt, visual companion validator, `git diff --check`가 통과한다.
