# 회원 식별자 기반 알림 아웃박스 구현 계획

> **에이전트 작업자 필수 지침:** 이 계획을 구현할 때는 `$subagent-driven-development`를 우선 사용하고, 한 세션에서 순차 실행할 때는 `$executing-plans`를 사용한다. 각 단계는 `- [ ]` 체크박스로 추적한다.

**목표:** 예약 변경과 알림 요청을 같은 트랜잭션에 기록하고, 회원 DB에서 최신 수신 정보를 조회해 상한이 있는 재시도·억제·비식별화·운영 관측을 제공한다.

**아키텍처:** `appointment-core`는 기존 `patient_external_id` 물리 컬럼을 `MemberId` 도메인 값으로 해석한다. `appointment-event`는 알림 전용 아웃박스 계약과 caller-transaction repository를 소유하고, `appointment-api`는 legacy와 v2 command 트랜잭션 안에서 알림 행을 기록한다. `appointment-notification`은 공정한 claim, lease fencing, 회원 조회, typed template 렌더링, provider 호출, durable retry, 종료 행 비식별화와 운영 지표를 담당한다.

**기술 스택:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed ORM, Flyway, H2/PostgreSQL/MySQL, Micrometer, Resilience4j, JUnit 5, MockK, bluetape4k assertions와 singleton Testcontainers launcher

---

## 1. 실행 전제와 작업 분해

- 기준 설계: `docs/superpowers/specs/2026-07-31-issue-172-notification-outbox-design.md`
- 기준 이슈: `https://github.com/bluetape4k/clinic-appointment/issues/172`
- 기준 브랜치: `feat/issue-172-notification-reliability`
- 기준 base: `origin/develop`의 `f10e2c2fcf7766dccde3cef8f03434c78ae5f915`
- 새 외부 dependency와 새 Gradle module은 추가하지 않는다.
- Flyway schema 이름 `scheduling_*`는 기존 계약이므로 변경하지 않는다.
- Exposed repository 호출은 caller가 연 `transaction {}` 또는 명시적인 짧은 store transaction 안에서만 수행한다.
- Testcontainers 검증은 병렬로 실행하지 않고 H2, PostgreSQL, MySQL 순서로 실행한다.
- Kotlin 구현·검토는 `$bluetape-kotlin-patterns`, 테스트는 `references/testing.md`, Spring wiring은 `references/spring-boot.md`, Exposed 변경은 `$ecc-kotlin-exposed`를 적용한다.
- README·OpenAPI·runbook은 `$bluetape-writer`, 다이어그램 변경은 `$bluetape-diagram`을 적용한다.

### 파일 책임 지도

| 영역 | 파일 | 책임 |
|---|---|---|
| 회원 식별자 | `appointment-core/.../model/identity/MemberId.kt` | opaque 회원 ID 값과 길이·공백 검증 |
| 예약 영속 경계 | `AppointmentRecord.kt`, `AppointmentCommitmentRecords.kt`, `Appointments.kt`, `AppointmentRepository.kt`, `RecordMappers.kt` | 기존 `patient_external_id` 컬럼을 `MemberId`로 읽고 쓰기 |
| 알림 계약 | `appointment-event/.../event/notification/NotificationOutboxContracts.kt` | 닫힌 channel/event/slot/status/error/suppression 계약 |
| template parameter | `appointment-event/.../event/notification/NotificationTemplateParameters.kt` | typed parameter와 schema discriminator |
| 키 계약 | `appointment-event/.../event/notification/NotificationOutboxKeys.kt` | key ring port, HMAC idempotency·감사 fingerprint |
| 아웃박스 영속 | `NotificationOutboxEvents.kt`, `NotificationDeliveryAttempts.kt`, `NotificationOutboxRepository.kt` | enqueue, claim, fence, retry, 종료, redaction, retention |
| API 회원 해석 | `appointment-api/.../notification/AppointmentMemberResolver.kt` | legacy 입력과 v2 actor/Plan을 검증된 `MemberId`로 변환 |
| API 기록기 | `appointment-api/.../notification/AppointmentNotificationWriter.kt` | command 결과를 알림 envelope와 reminder 행으로 변환 |
| worker 설정 | `NotificationProperties.kt`, `NotificationResilienceProperties.kt` | batch·동시성·lease·retry·rollout·crypto 설정과 상한 |
| worker 실행 | `NotificationOutboxDispatcher.kt`, `NotificationOutboxWorker.kt`, `NotificationOutboxWorkStore.kt` | 공정 claim, 외부 I/O, fenced outcome, lease 복구 |
| 회원·template | `MemberNotificationProfileResolver.kt`, `NotificationTemplateCatalog.kt`, `NotificationTemplateRenderer.kt` | 최신 profile 조회와 안전한 parameterized rendering |
| provider | `NotificationChannel.kt`, `ResilientNotificationChannel.kt`, `DummyNotificationChannel.kt` | 구조화 요청 전달과 실패 반환 |
| 운영 | `NotificationOutboxMetrics.kt`, `NotificationOutboxHealthIndicator.kt`, `NotificationStatusQueryService.kt`, `NotificationReNotifyService.kt` | metric, health, 안전한 상태 조회, 수동 재알림 |
| migration | `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V14__add_notification_outbox.sql` | 테이블, constraint, unique/claim/recovery/retention index |
| 문서 | README 쌍, `docs/requirements/notification.md`, 운영 runbook, OpenAPI 테스트 | caller·운영 계약과 한/영 문서 동등성 |

### 실행 상태

| 작업 | 상태 | 근거 또는 처분 |
|---|---|---|
| Task 1~12 | 완료 | 회원 경계, outbox lifecycle, worker, 운영 API와 기존 검증 커밋 |
| Task 13A | 완료 | 병원별 rollout mode, 상호 배타 route, 동일 outbox 행 조건부 선점 테스트 |
| Task 13B | 후속 운영 이슈 | 실제 24시간·1,000건 canary와 `ACTIVE` 전환 뒤 listener 제거 |
| Task 14 | 완료 | 3개 dialect·실행 계획 테스트, 20,000개 backlog 합성 부하와 Gatling 보고서 |
| Task 15 | 완료 | OpenAPI 계약, README 한·영, 운영 runbook, 한·영 light/dark 다이어그램 |
| Task 16 | 완료 | affected-module 검증, 최종 독립 재검토와 검토·lesson 문서 완료 |

## 2. 요구사항 추적표

| 설계 수용 기준 | 구현 작업 | 핵심 증거 |
|---:|---|---|
| 1. 예약과 아웃박스 원자성 | Task 6, 7 | rollback 통합 테스트 |
| 2. durable attempt와 오류 코드 | Task 3, 9 | attempt repository·worker 테스트 |
| 3. 재시도 상한 | Task 8, 9 | properties 경계·exhaustion 테스트 |
| 4. 성공 key 재발송 금지 | Task 3, 9 | unique key·SENT claim 제외 테스트 |
| 5. 다중 worker와 lease 복구 | Task 8, 9 | 반복 concurrency·stale fence 테스트 |
| 6. 아웃박스 수신자는 `memberId`만 사용 | Task 1, 4, 10 | codec·DB privacy 검사 |
| 7. typed parameterized template | Task 2, 10 | schema/parameter/escaping 테스트 |
| 8. raw 개인정보·본문·예외 미저장 | Task 3, 9, 12 | DB/log/metric allowlist 테스트 |
| 9. 신규 예약 `memberId` 필수 | Task 1, 5~7 | legacy/v2 endpoint 테스트 |
| 10. 기존 누락 예약 억제 | Task 3, 5, 6, 11 | `LEGACY_SUPPRESSION` 원자 기록 테스트 |
| 11. 회원 조회 retry/suppression | Task 9, 10 | 결과별 분류 테스트 |
| 12. metric/dashboard/alert | Task 12 | metric tag·status API·health 테스트 |
| 13. 3개 DB migration과 claim | Task 3, 8, 14 | H2/PostgreSQL/MySQL 순차 검증 |
| 14. 전환기 route와 worker 상호 배타, 최종 제거 추적 | Task 13A, 13B | 같은 outbox 행 조건부 claim·route gate 테스트, 후속 운영 이슈 |
| 15. 대규모 backlog 상한 | Task 8, 14 | EXPLAIN·합성 부하 테스트 |
| 16. 제한된 retention/redaction | Task 9, 14 | page·backpressure·redaction 테스트 |
| 17. endpoint 오류와 OpenAPI | Task 5, 12, 15 | 오류 매핑·OpenAPI snapshot |
| 18. re-notify/alert/health/key/migration runbook | Task 12, 15 | 운영 문서와 설정명 검사 |

## 3. 위험 예측

| 위험 | 조기 신호 | 완화 | rollback·재검증 지점 |
|---|---|---|---|
| command transaction 밖에서 enqueue | 예약 commit 뒤 outbox 0건 | writer를 repository와 같은 `transaction {}`에 배치 | Task 5·6 원자성 테스트 실패 시 해당 command hook만 되돌리고 RED부터 재실행 |
| durable retry와 Resilience4j retry 곱 증가 | 한 lease에서 provider 호출 2회 초과 | lease별 호출 기본 1회, 설정 곱 최대 12 검증 | Task 8 properties 테스트와 Task 9 attempt 수 재검증 |
| lease를 잃은 worker가 최신 상태 덮어쓰기 | 같은 outbox에 상충하는 종료 상태 | `leaseOwner + leaseToken + attemptNumber + leaseUntil` fenced update | Task 8·9 stale-worker 반복 테스트 |
| 대형 병원 backlog가 다른 병원 고갈 | 작은 병원 oldest age 증가 | tenant/clinic cursor와 clinic별 semaphore | Task 8 공정성 테스트와 Task 14 합성 부하 재실행 |
| 개인정보가 history/log/metric에 재유입 | raw phone/name/body 문자열 탐지 | typed allowlist, 종료 트랜잭션 즉시 redaction, 낮은 cardinality tag | Task 10·12 privacy 검사 실패 시 provider 호출 차단 |
| 3개 DB의 constraint/index 차이 | migration 또는 EXPLAIN 불일치 | dialect별 V14와 migration support matrix | Task 3 이후 H2, Task 14에서 PostgreSQL/MySQL 순차 재실행 |
| 기존 caller가 `memberId` 없이 호출 | OBSERVE missing-member 증가 | 만료가 있는 clinic override와 readiness gate | Task 5 OBSERVE 유지, ENFORCE 전환 중단 |
| key ring 장애로 중복 enqueue | idempotency digest 계산 실패 | enqueue readiness 503 fail-closed | Task 2 key 테스트, Task 12 health 테스트 |
| 전환기 listener와 worker 동시 발송 | 같은 논리 key의 provider 호출 2회 | route gate와 provider 호출 전 동일 outbox 행 조건부 claim | Task 13A에서 default SHADOW·상호 배타 테스트, Task 13B에서 실제 canary 검증 |

## Task 1: `MemberId` 도메인 경계와 예약 매핑

**복잡도:** 높음
**의존성:** 없음
**write scope:** `appointment-core`, 관련 `appointment-api` identity test

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/identity/MemberId.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/identity/MemberIdTest.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/AppointmentRecord.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/AppointmentCommitmentRecords.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/AppointmentItemRecords.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentItemRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/RecordMappers.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleService.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentCommandService.kt`
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepositoryTest.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentItemRepositoryTest.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/service/ClosureRescheduleServiceTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/VisitCommitmentCommandTestSupport.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/DefaultAppointmentCommitmentApplicationServiceTest.kt`

- [ ] **Step 1: 값 객체 RED 테스트 작성**

```kotlin
class MemberIdTest {
    @Test
    fun `공백 회원 ID는 거절한다`() {
        assertFailsWith<IllegalArgumentException> { MemberId(" ") }
    }

    @Test
    fun `opaque 회원 ID를 원문 변경 없이 보존한다`() {
        MemberId("member_01JZ8A").value shouldBeEqualTo "member_01JZ8A"
    }
}
```

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-core:test --tests "*.MemberIdTest"`

Expected: `MemberId` 미정의로 compilation failure.

- [ ] **Step 3: `MemberId` 최소 구현**

```kotlin
@JvmInline
value class MemberId(val value: String) : Serializable {
    init {
        value.requireNotBlank("memberId")
        require(value.length <= 255) { "memberId must not exceed 255 characters" }
    }
}
```

`AppointmentRecord`, `AppointmentVisitIdentityDraft`, `AppointmentItemMutationScope`의 외부 모델 이름을 `memberId: MemberId?` 또는 `memberStableRef: MemberId?`로 통일하고, `Appointments.patientExternalId` 물리 컬럼은 그대로 둔다. repository mapper는 `row[Appointments.patientExternalId]?.let(::MemberId)`를 사용한다. `AppointmentRepository`, `AppointmentItemRepository`, `ClosureRescheduleService`, `AppointmentCommitmentCommandService`와 해당 fixture가 기존 이름을 계속 참조하지 않는지 `rg`로 확인한다.

- [ ] **Step 4: repository round-trip과 물리 컬럼 호환 테스트**

`AppointmentRepositoryTest`에 `MemberId("member-1")` 저장·조회와 nullable legacy row 조회를 각각 추가한다. 테스트 setup은 `SchemaUtils.createMissingTablesAndColumns(Appointments)`와 `Appointments.deleteAll()`을 사용한다.

- [ ] **Step 5: Task 1 검증**

Run: `./gradlew :appointment-core:test --tests "*.MemberIdTest" --tests "*.AppointmentRepositoryTest"`

Run: `./gradlew :appointment-core:test --tests "*.AppointmentItemRepositoryTest" --tests "*.ClosureRescheduleServiceTest"`

Run: `./gradlew :appointment-api:test --tests "*.DefaultAppointmentCommitmentApplicationServiceTest"`

Run: `rg -n "patientExternalId|patientExternalStableRef" appointment-core/src/main appointment-api/src/main`

Expected: 모든 test PASS; 신규 record는 `MemberId`, legacy row는 `null`, SQL column 이름은 `patient_external_id`; 마지막 `rg`에는 `Appointments.patientExternalId` 물리 컬럼과 mapper의 호환 참조만 남는다.

- [ ] **Step 6: Lore commit**

```text
Make member identity explicit without renaming the compatibility column

Constraint: Existing databases retain patient_external_id during rollout
Rejected: Persisting a second member_id column | duplicates identity during migration
Confidence: high
Scope-risk: moderate
Tested: MemberId and AppointmentRepository targeted tests
Not-tested: API and notification integration until later tasks
```

## Task 2: 알림 envelope, typed parameter와 key ring 계약

**복잡도:** 높음
**의존성:** Task 1
**write scope:** `appointment-event`

**Files:**
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxContracts.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationTemplateParameters.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxCodec.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxKeys.kt`
- Modify: `appointment-event/build.gradle.kts`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxCodecTest.kt`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxHasherTest.kt`

`appointment-event/build.gradle.kts` 변경은 strict typed JSON codec을 위해 repository-standard 기존 Jackson 3 Kotlin module을 연결하는 범위로 제한한다. 새 외부 dependency를 추가하지 않는다.

- [ ] **Step 1: 닫힌 계약과 codec RED 테스트**

```kotlin
@Test
fun `알 수 없는 parameterType은 거절한다`() {
    val json = """{"schemaVersion":1,"parameterType":"UNKNOWN","parameters":{}}"""
    assertFailsWith<NotificationContractException> { codec.decode(json) }
}

@Test
fun `회원 이름과 전화번호 field는 envelope에 없다`() {
    val json = codec.encode(confirmedEnvelope())
    json shouldNotContain "patientName"
    json shouldNotContain "patientPhone"
    json shouldNotContain "memberName"
}
```

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-event:test --tests "*.NotificationOutboxCodecTest"`

Expected: codec와 계약 미정의로 compilation failure.

- [ ] **Step 3: durable 계약 구현**

다음 타입을 실제 enum/value class/sealed interface로 정의한다.

```kotlin
enum class NotificationOutboxRowKind { SENDABLE, LEGACY_SUPPRESSION }
enum class NotificationOutboxStatus { PENDING, PROCESSING, RETRY_WAIT, SENT, SUPPRESSED, EXHAUSTED }
enum class NotificationChannelType { DUMMY, SMS, EMAIL, PUSH }
enum class NotificationEventType { CREATED, CONFIRMED, CANCELLED, RESCHEDULED, REMINDER }
enum class NotificationSlot { CREATED, CONFIRMED, CANCELLED, RESCHEDULED, REMINDER_24H, REMINDER_SAME_DAY }

sealed interface NotificationTemplateParameters : Serializable

data class AppointmentConfirmedParameters(
    val clinicDisplayName: String,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
) : NotificationTemplateParameters {
    companion object { private const val serialVersionUID = 1L }
}
```

`NotificationFailureCode`와 `NotificationSuppressionReasonCode`는 설계 문서의 목록만 허용한다. `NotificationOutboxEnvelope`는 `schemaVersion`, `parameterType`, `templateVersion`, `MemberId`를 필수로 가진다.

- [ ] **Step 4: key ring과 HMAC RED 테스트**

active와 previous key로 같은 논리 알림을 조회할 digest 후보가 만들어지는지, idempotency와 audit domain이 다른지, key가 없으면 `HMAC_KEY_UNAVAILABLE`로 실패하는지 검증한다.

- [ ] **Step 5: key ring port와 HMAC 구현**

```kotlin
interface NotificationOutboxKeyRing {
    fun active(): NotificationHmacKey
    fun previous(): NotificationHmacKey?
}

interface NotificationOutboxHasher {
    fun idempotencyCandidates(input: NotificationIdempotencyInput): List<NotificationIdempotencyDigest>
    fun auditFingerprint(input: NotificationAuditInput): NotificationAuditFingerprint
}
```

`Mac.getInstance("HmacSHA256")`를 호출마다 만들고 key byte array를 방어 복사한다. domain prefix는 `clinic-notification:idempotency:v1`과 `clinic-notification:audit:v1`로 분리한다.

- [ ] **Step 6: Task 2 검증**

Run: `./gradlew :appointment-event:test --tests "*.NotificationOutboxCodecTest" --tests "*.NotificationOutboxHasherTest"`

Expected: unknown schema/type 거절, 개인정보 field 부재, active/previous lookup, domain separation PASS.

- [ ] **Step 7: Lore commit**

```text
Define a closed privacy-safe notification delivery contract

Constraint: Durable payloads cannot contain recipient profile data or rendered messages
Rejected: Map<String, Any?> parameters | bypasses schema and allow-list validation
Confidence: high
Scope-risk: moderate
Tested: Codec and HMAC contract tests
Not-tested: Database persistence until Task 3
```

## Task 3: 아웃박스·attempt 테이블과 caller-transaction repository

**복잡도:** 높음
**의존성:** Task 2
**write scope:** `appointment-event`

**Files:**
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxEvents.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationDeliveryAttempts.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.kt`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepositoryTest.kt`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxConcurrencyTest.kt`

- [ ] **Step 1: enqueue와 개인정보 RED 테스트**

```kotlin
@Test
fun `같은 idempotency digest는 한 행으로 수렴한다`() = transaction(database) {
    repository.enqueue(sendableDraft())
    repository.enqueue(sendableDraft())
    repository.countActive() shouldBeEqualTo 1L
}

@Test
fun `legacy suppression은 raw appointment와 member를 저장하지 않는다`() = transaction(database) {
    val row = repository.suppressLegacy(legacySuppressionDraft())
    row.appointmentId shouldBeNull()
    row.memberId shouldBeNull()
    row.parametersJson shouldBeNull()
}
```

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-event:test --tests "*.NotificationOutboxRepositoryTest"`

Expected: table/repository 미정의로 compilation failure.

- [ ] **Step 3: 테이블과 repository 최소 구현**

`clinic_notification_outbox`와 `clinic_notification_delivery_attempts`를 정의한다. repository public 메서드는 자체 `transaction {}`을 열지 않고 caller transaction을 사용한다.

```kotlin
class NotificationOutboxRepository {
    fun enqueue(draft: SendableNotificationDraft): NotificationOutboxRecord
    fun suppressLegacy(draft: LegacySuppressionDraft): NotificationOutboxRecord
    fun findReadyClinicKeys(cursor: NotificationFairCursor?, limit: Int): List<NotificationClinicKey>
    fun findReadyCandidates(key: NotificationClinicKey, cursorId: Long?, limit: Int): List<NotificationCandidate>
    fun claim(candidateId: Long, owner: String, token: String): ClaimedNotification?
    fun recoverExpired(candidateId: Long, owner: String, token: String): ClaimedNotification?
    fun complete(command: CompleteNotificationCommand): Boolean
    fun scheduleRetry(command: RetryNotificationCommand): Boolean
}
```

claim과 complete 조건은 `rowKind`, status, owner, token, attempt, DB 시각을 모두 확인한다. attempt unique key는 `(outbox_id, attempt_number)`다.

- [ ] **Step 4: 공정 claim 질의와 index 계약 고정**

V14 작성 전에 실제 repository의 `WHERE`/`ORDER BY`를 먼저 고정한다. clinic keyset 탐색, clinic 내부 ready candidate, 만료 lease recovery, 종료 retention, pending/oldest metric 질의를 각각 이름 있는 query contract로 만들고 다음 index 이름과 column order를 migration support가 검사하게 한다.

```text
uk_notification_outbox_idempotency
idx_notification_outbox_ready_clinic_cursor
idx_notification_outbox_ready_within_clinic
idx_notification_outbox_lease_recovery
idx_notification_outbox_terminal_retention
idx_notification_outbox_pending_oldest
```

column order는 각 질의의 동등 조건, 범위 조건, 정렬 순서와 일치시킨다. `NotificationOutboxRepositoryTest`는 생성 SQL 이름만 보지 않고 각 query contract의 filter/order와 기대 index 정의가 함께 바뀌도록 잠근다.

- [ ] **Step 5: claim·fence concurrency 테스트**

`MultithreadingTester`로 같은 candidate를 20회 경쟁시키고 claim 성공 수가 1인지 확인한다. stale token의 `complete`는 `false`, 최신 token은 `true`여야 한다.

- [ ] **Step 6: Task 3 검증**

Run: `./gradlew :appointment-event:test --tests "*.NotificationOutboxRepositoryTest" --tests "*.NotificationOutboxConcurrencyTest"`

Expected: enqueue/suppression/claim/fence/attempt unique PASS.

- [ ] **Step 7: Lore commit**

```text
Persist notification delivery with transaction-owned fencing

Constraint: Provider IO must never hold a database transaction
Rejected: Reusing SchedulingOutboxEvents | publication and delivery lifecycles differ
Confidence: high
Scope-risk: broad
Tested: H2 repository and concurrency tests
Not-tested: PostgreSQL and MySQL migrations until Task 14
```

## Task 4: V14 Flyway migration과 schema 초기화

**복잡도:** 높음
**의존성:** Task 3
**write scope:** `appointment-api/src/main/resources`, migration tests, schema config

**Files:**
- Create: `appointment-api/src/main/resources/db/migration/h2/V14__add_notification_outbox.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V14__add_notification_outbox.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V14__add_notification_outbox.sql`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/NotificationOutboxMigrationTestSupport.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`

- [ ] **Step 1: migration RED 테스트**

clean install과 V13→V14 upgrade에서 두 테이블, row-kind `CHECK`, idempotency unique index, claim/recovery/retention index를 검사한다. `MigrationUtils.statementsRequiredForDatabaseMigration(...)`는 additive drift가 없어야 한다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-api:test --tests "*.FlywayMigrationTest"`

Expected: `FlywayMigrationTest`에 추가한 notification schema assertion이 V14와 테이블 부재로 FAIL.

- [ ] **Step 3: 3개 dialect V14 구현**

각 SQL은 다음 제약을 동일하게 강제한다.

```text
SENDABLE and status in (PENDING, PROCESSING, RETRY_WAIT) =>
appointment_id, member_id, channel, event_type,
notification_slot, template_key, template_version, parameter_type,
parameters_json are NOT NULL

SENDABLE and status in (SENT, SUPPRESSED, EXHAUSTED) =>
appointment_id, member_id, parameters_json are NULL

LEGACY_SUPPRESSION => status = SUPPRESSED and raw identity/template fields are NULL
```

PostgreSQL은 online index 전략을 runbook에 기록하고, MySQL은 지원되는 `ALGORITHM`/`LOCK` 범위를 확인한다. H2는 테스트 가능한 동등 constraint를 둔다.

- [ ] **Step 4: schema initializer 단일화**

`DatabaseConfig`의 명시적 table 목록에 새 테이블을 추가한다. `NotificationAutoConfiguration.notificationSchemaInitializer`는 동일 테이블을 중복 생성하지 않도록 제거하거나 API schema initializer bean 부재 조건에서만 동작하게 분리한다.

- [ ] **Step 5: H2 migration 검증**

Run: `./gradlew :appointment-api:test --tests "*.FlywayMigrationTest"`

Expected: clean/upgrade/constraint/index/drift PASS.

- [ ] **Step 6: Lore commit**

```text
Add portable notification outbox schema before runtime wiring

Constraint: H2 PostgreSQL and MySQL must enforce the same row-kind lifecycle
Rejected: Exposed-only schema generation | cannot express all operational constraints
Confidence: medium
Scope-risk: broad
Tested: H2 clean and upgrade migration tests
Not-tested: Container dialects until Task 14
```

## Task 5: 회원 해석과 legacy `OBSERVE`/`ENFORCE` API 계약

**복잡도:** 높음
**의존성:** Task 1, 2, 4
**write scope:** `appointment-api`

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/AppointmentMemberResolver.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/NotificationMemberIdProperties.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/NotificationMemberApiException.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/CreateAppointmentRequest.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentCommitmentPlanningResolvers.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/notification/AppointmentMemberResolverTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentControllerTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentRequestV2Test.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentV2Test.kt`

- [ ] **Step 1: 오류 계약 RED 테스트**

legacy body 누락 `422/MEMBER_ID_REQUIRED`, 미존재 `404/MEMBER_NOT_FOUND`, scope 불일치 `403/MEMBER_SCOPE_MISMATCH`, 모호성 `409/MEMBER_REFERENCE_AMBIGUOUS`, directory 장애 `503/MEMBER_DIRECTORY_UNAVAILABLE`와 `Retry-After`를 검증한다. v2 customer와 admin은 각각 actor/Plan에서 파생한 참조에 대해 `MEMBER_NOT_FOUND`, `MEMBER_SCOPE_MISMATCH`, `MEMBER_REFERENCE_AMBIGUOUS`, `MEMBER_DIRECTORY_UNAVAILABLE`을 같은 `SchedulingApiErrorResponse`로 반환한다. `MEMBER_ID_REQUIRED`는 legacy request body 전용이다. 모든 오류 응답은 raw member ID나 Plan에서 파생한 회원 참조를 반사하지 않는다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-api:test --tests "*.AppointmentControllerTest" --tests "*.AppointmentMemberResolverTest"`

Expected: `memberId`와 오류 매핑 부재로 FAIL.

- [ ] **Step 3: resolver와 rollout properties 구현**

```kotlin
enum class MemberIdEnforcementMode { OBSERVE, ENFORCE }

interface AppointmentMemberResolver {
    fun resolveLegacy(tenantGroupId: Long, clinicId: Long, requested: MemberId?): MemberResolution
    fun resolvePlan(actor: ActorContext, access: ResolvedAppointmentPlanAccess): MemberId
}

sealed interface MemberResolution {
    data class Resolved(val memberId: MemberId) : MemberResolution
    data object LegacyMissing : MemberResolution
}
```

platform default는 `ENFORCE`다. clinic `OBSERVE` override는 만료 시각과 owner가 없으면 기동 시 거절한다. v2 DTO에는 `memberId`를 추가하지 않는다.
legacy DTO의 `memberId`는 opaque 값이며 `ENFORCE`에서 필수다. KDoc과 schema는 이름·전화번호가 회원 식별자를 대신할 수 없고 오류 응답에도 원문 ID를 반환하지 않는다고 명시한다.

- [ ] **Step 4: actor/Plan 경계 구현**

customer는 인증된 `patientSubjectId`를 resolver에 넘기되 같은 namespace라고 가정하지 않는다. admin은 `ResolvedAppointmentPlanAccess.plan`의 보호된 참조를 resolver가 해석한다. 기본 adapter는 fail-closed다.

- [ ] **Step 5: Task 5 검증**

Run: `./gradlew :appointment-api:test --tests "*.AppointmentMemberResolverTest" --tests "*.AppointmentControllerTest" --tests "*.AppointmentRequestV2Test" --tests "*.AdminAppointmentV2Test"`

Expected: legacy 오류 5종, v2 body에 memberId 부재, customer/admin의 Plan-to-member 오류 4종, `503 Retry-After`, actor/Plan scope와 raw 식별자 비반사 검증 PASS.

- [ ] **Step 6: Lore commit**

```text
Require verified member identity at every appointment entry point

Constraint: Customer and admin v2 requests derive identity from actor and Plan
Rejected: Accepting memberId in v2 request bodies | weakens server-side ownership checks
Confidence: high
Scope-risk: broad
Tested: Resolver and endpoint contract tests
Not-tested: Command-to-outbox atomicity until Tasks 6 and 7
```

## Task 6: Legacy command 트랜잭션의 원자적 알림 기록

**복잡도:** 높음
**의존성:** Task 3, 5
**write scope:** legacy appointment API service와 tests

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/AppointmentNotificationWriter.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentController.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentNotificationAtomicityTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentControllerTest.kt`

- [ ] **Step 1: create commit/rollback RED 테스트**

같은 transaction에서 appointment와 `CREATED` outbox가 함께 생기는지, outbox insert를 강제로 실패시키면 appointment와 idempotency row도 rollback되는지 검증한다. `OBSERVE` missing-member는 appointment와 `LEGACY_SUPPRESSION`이 함께 commit되어야 한다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-api:test --tests "*.AppointmentNotificationAtomicityTest"`

Expected: outbox 0건 또는 appointment만 commit되어 FAIL.

- [ ] **Step 3: writer 구현**

```kotlin
class AppointmentNotificationWriter(
    private val repository: NotificationOutboxRepository,
    private val hasher: NotificationOutboxHasher,
    private val clock: Clock,
) {
    fun appointmentCreated(record: AppointmentRecord, version: Long, resolution: MemberResolution)
    fun statusChanged(record: AppointmentRecord, version: Long, from: AppointmentState, to: AppointmentState)
    fun cancelled(record: AppointmentRecord, version: Long, reasonCode: CancellationReasonCode?)
    fun rescheduled(original: AppointmentRecord, replacement: AppointmentRecord, version: Long)
}
```

writer는 transaction을 열지 않는다. 자유 텍스트 취소 사유는 outbox parameter에 넣지 않고 등록된 reason code만 사용한다.

- [ ] **Step 4: legacy command transaction 재배치**

`AppointmentService.create`, `createIdempotently`, `updateStatus`, `cancel`, `reschedule`의 저장·상태 이력·outbox 기록을 각각 하나의 transaction으로 묶는다. Spring event publish는 commit 뒤 logging observer용으로만 유지한다.

- [ ] **Step 5: confirm과 reminder 선기록**

`CONFIRMED` 전이 시 `CONFIRMED`, `REMINDER_24H`, `REMINDER_SAME_DAY` 행을 같은 transaction에서 만든다. cancel/reschedule은 이전 reminder를 `APPOINTMENT_CHANGED`로 종료하고 새 version reminder를 만든다.

- [ ] **Step 6: Task 6 검증**

Run: `./gradlew :appointment-api:test --tests "*.AppointmentNotificationAtomicityTest" --tests "*.AppointmentControllerTest"`

Expected: create/status/cancel/reschedule commit/rollback, duplicate command, reminder materialization PASS.

- [ ] **Step 7: Lore commit**

```text
Close the legacy appointment-to-notification commit gap

Constraint: Notification enqueue must share the command transaction
Rejected: Adapting NotificationEventListener | still loses events after commit
Confidence: high
Scope-risk: broad
Tested: Legacy command atomicity and reminder materialization tests
Not-tested: V2 command paths until Task 7
```

## Task 7: Commitment v2 command의 알림 writer 연결

**복잡도:** 높음
**의존성:** Task 1, 5, 6
**write scope:** v2 commitment command/application service와 tests

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentCommandService.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/DefaultAppointmentCommitmentApplicationService.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentCommitmentPlanningResolvers.kt`
- Read/consume: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/AppointmentCommitmentRecords.kt`의 Task 1 `MemberId` 계약
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentCommandServiceTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/DefaultAppointmentCommitmentApplicationServiceTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/VisitCommitmentConcurrencyTest.kt`

- [ ] **Step 1: v2 hook RED 테스트**

customer request, admin direct create, approve/confirm, cancel, change proposal 각각에 대해 성공 command와 outbox 행이 함께 commit되는지 검증한다. stale ETag, replay와 정책 거절에는 새 outbox가 없어야 한다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-api:test --tests "*.AppointmentCommitmentCommandServiceTest" --tests "*.DefaultAppointmentCommitmentApplicationServiceTest"`

Expected: v2 notification outbox 0건으로 FAIL.

- [ ] **Step 3: v2 identity를 `MemberId`로 전달**

Task 1에서 `AppointmentVisitIdentityDraft.memberId: MemberId?`를 durable identity 필드로 만들고 기존 patient reference fingerprint는 별도 opaque fingerprint로 유지한다. `AppointmentCommitmentPlanningResolver.resolveIdentity`는 검증된 `MemberId`를 이 필드와 command context로 전달한다. 물리 `Appointments.patientExternalId` 이름은 mapper 밖으로 노출하지 않고, fingerprint를 역조회하거나 member ID로 대체하지 않는다. `VisitCommitmentCommandTestSupport`는 resolve → command → appointment persistence → notification writer까지 같은 `MemberId`가 전달되는 fixture를 제공한다.

- [ ] **Step 4: `executeCommand` transaction에 writer 연결**

`writeDecision`과 appointment projection이 성공한 뒤, `persistCommandResult` 전에 `AppointmentNotificationWriter`를 호출한다. 기존 `SchedulingOutboxEvents` 기록은 유지하되 notification table과 상태를 공유하지 않는다.

- [ ] **Step 5: Task 7 검증**

Run: `./gradlew :appointment-api:test --tests "*.AppointmentCommitmentCommandServiceTest" --tests "*.DefaultAppointmentCommitmentApplicationServiceTest" --tests "*.VisitCommitmentConcurrencyTest"`

Expected: v2 경로별 enqueue, replay dedupe, stale ETag rollback, 동시 command 수렴 PASS.

- [ ] **Step 6: Lore commit**

```text
Record v2 appointment notifications inside commitment decisions

Constraint: Generic scheduling publication remains separate from notification delivery
Rejected: Publishing a second Spring event | does not provide caller-transaction durability
Confidence: high
Scope-risk: broad
Tested: V2 command and concurrency tests
Not-tested: Worker delivery until later tasks
```

## Task 8: worker 설정, work store, 공정 claim과 lease 복구

**복잡도:** 높음
**의존성:** Task 3, 4
**write scope:** `appointment-notification`

**Files:**
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorkStore.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxDispatcher.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorker.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadiness.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationCryptoProperties.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationProperties.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationPropertiesTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationCryptoPropertiesTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadinessTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxDispatcherTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorkerLeaseTest.kt`

- [ ] **Step 1: 설정 경계 RED 테스트**

durable attempt `1..10`, 경과 시간 `15m..72h`, lease별 provider attempt `1..2`, 곱 `<=12`, 양수 batch/concurrency, lease가 provider timeout보다 긴지 검증한다. `memberResolverMaxConcurrency`, member rate limit/circuit breaker와 channel별 `providerMaxConcurrency`/bulkhead가 모두 양수이고, worker 전체 동시성이 DB claim·회원 서비스·선택 channel provider 상한의 최솟값을 넘지 않는지도 검사한다.
crypto 설정은 inline key material을 거절하고 외부 secret reference만 허용한다. active/previous key ID 중복, previous overlap 35일 초과, 만료된 active key를 fail-closed하며 `toString`과 validation 오류에 key material이 나타나지 않는지 검증한다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :appointment-notification:test --tests "*.NotificationPropertiesTest"`

Expected: worker 설정 부재로 FAIL.

- [ ] **Step 3: immutable properties와 validator 구현**

기본값은 attempt 6, 경과 24h, provider attempt 1, catch-up 30m다. member resolver와 channel/provider마다 독립된 timeout, rate limit, circuit breaker와 bulkhead 설정을 둔다. 범위를 벗어난 값이나 전체 worker concurrency가 외부 의존성 상한을 넘는 조합은 기동 시 `IllegalStateException`으로 거절한다.

- [ ] **Step 4: schema readiness를 worker보다 먼저 연결**

`NotificationSchemaReadiness`는 Flyway version, outbox/attempt table, claim/recovery index와 active key ring을 확인한다. 새 binary가 V14 이전 schema에 연결되거나 claim/recovery 계약이 빠졌으면 readiness를 DOWN으로 만들고 worker poll, lease recovery, reminder scanner와 retention runner bean을 시작하지 않는다. `NotificationAutoConfigurationTest`는 old-schema/new-binary, key 없음, 정상 schema를 각각 검증한다.

- [ ] **Step 5: 짧은 transaction work store 구현**

```kotlin
interface NotificationOutboxWorkStore {
    suspend fun findFairCandidates(limit: Int, cursor: NotificationFairCursor?): NotificationCandidatePage
    suspend fun claim(id: Long, owner: String): ClaimedNotification?
    suspend fun recoverExpired(limit: Int, owner: String): List<ClaimedNotification>
    suspend fun complete(command: CompleteNotificationCommand): Boolean
    suspend fun retry(command: RetryNotificationCommand): Boolean
}
```

각 메서드는 `withContext(Dispatchers.IO) { transaction(database) { ... } }`로 짧게 실행한다. provider I/O를 받는 callback은 만들지 않는다.

- [ ] **Step 6: 공정 dispatcher 구현과 테스트**

`ProfileReevaluationDispatcher`의 cursor·global/per-clinic semaphore 패턴을 재사용한다. 한 clinic에 10,000건, 다른 clinic에 10건을 넣어 작은 clinic이 첫 두 page 안에 claim되는지 검증한다.
별도 semaphore/bulkhead 테스트에서는 동시 member lookup과 channel별 provider 호출이 각각 설정한 상한을 한 번도 넘지 않는지 반복 검증한다.

- [ ] **Step 7: 만료 lease 복구와 app clock skew 테스트**

DB `CURRENT_TIMESTAMP` 기반으로 만료 `PROCESSING`을 복구한다. 서로 다른 app clock을 주입해도 같은 DB 결과가 나와야 한다.
만료 row에 open attempt가 있으면 같은 짧은 recovery transaction에서 이전 attempt를 `LEASE_LOST`로 종료하고 recovery metric을 올린 뒤, 새 owner가 증가한 attempt number와 새 token을 얻는지 검증한다. open attempt가 남아서는 안 된다.

- [ ] **Step 8: Task 8 검증**

Run: `./gradlew :appointment-notification:test --tests "*.NotificationPropertiesTest" --tests "*.NotificationCryptoPropertiesTest" --tests "*.NotificationSchemaReadinessTest" --tests "*.NotificationOutboxDispatcherTest" --tests "*.NotificationOutboxWorkerLeaseTest"`

Expected: 설정·crypto 상한, schema fail-closed, DB/member/provider 최소 동시성, 공정성, 단일 claim, open attempt를 닫는 만료 복구와 clock skew PASS.

- [ ] **Step 9: Lore commit**

```text
Bound notification polling and recover leases with database time

Constraint: Large clinics must not starve smaller clinics
Rejected: Global FIFO polling | permits tenant backlog starvation
Confidence: medium
Scope-risk: broad
Tested: Properties dispatcher and lease tests
Not-tested: Real provider delivery until Task 10
```

## Task 9: durable retry, attempt fencing, 종료 비식별화와 retention

**복잡도:** 높음
**의존성:** Task 8
**write scope:** `appointment-event`, `appointment-notification`

**Files:**
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationRetryPolicy.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationRetentionRunner.kt`
- Modify after Task 8 creates: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorker.kt`
- Modify after Task 3 creates: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationRetryPolicyTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxLifecycleTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationRetentionRunnerTest.kt`

- [ ] **Step 1: retry/exhaustion RED 테스트**

attempt 1~5는 deterministic exponential backoff의 `RETRY_WAIT`, attempt 6 또는 24h 경과는 `EXHAUSTED`인지 검증한다. `DELIVERY_RESULT_UNKNOWN`도 같은 상한을 적용한다.

- [ ] **Step 2: stale attempt RED 테스트**

old lease token의 결과 기록은 outbox를 바꾸지 않고 자신이 만든 attempt만 `LEASE_LOST`로 종료해야 한다. 새 owner의 attempt와 종료 상태는 유지되어야 한다.

- [ ] **Step 3: retry policy와 fenced lifecycle 구현**

worker는 claim transaction에서 attempt를 만들고, 외부 I/O 뒤 fenced update를 수행한다. cancellation은 `CancellationException`을 먼저 rethrow하고 필요한 짧은 정리만 `NonCancellable`에서 수행한다.

취소 테스트는 claim 전, claim 뒤 I/O 전, member/template/provider I/O 중, provider 성공 뒤 fenced update 전으로 나눈다. 각 단계에서 transaction과 coroutine이 남지 않고, lease/attempt가 단계에 맞게 종료 또는 복구 가능하며 cancellation이 일반 실패로 삼켜지지 않는지 확인한다.

- [ ] **Step 4: 종료 트랜잭션 즉시 비식별화**

`SENT`, `SUPPRESSED`, `EXHAUSTED` 전환과 같은 update에서 `member_id`, `appointment_id`, `parameters_json`을 `NULL`로 만든다. audit key 장애여도 raw field 제거를 우선하고 `HMAC_KEY_UNAVAILABLE`을 남긴다.

- [ ] **Step 5: 처리량이 제한된 retention runner 구현**

`SENT/SUPPRESSED` 7일, `EXHAUSTED` 30일 기본값을 사용한다. indexed 종료 시각 순서로 page를 삭제하고 page 사이에 backpressure를 둔다.

- [ ] **Step 6: Task 9 검증**

Run: `./gradlew :appointment-notification:test --tests "*.NotificationRetryPolicyTest" --tests "*.NotificationOutboxLifecycleTest" --tests "*.NotificationRetentionRunnerTest"`

Expected: retry/exhaustion/fencing/redaction/retention과 단계별 cancellation의 transaction·coroutine 정리 PASS.

- [ ] **Step 7: Lore commit**

```text
Make every delivery attempt bounded fenced and privacy-minimized

Constraint: Terminal rows cannot retain member appointment or template data
Rejected: Redriving completed rows | redaction intentionally removes replay inputs
Confidence: high
Scope-risk: broad
Tested: Lifecycle retry retention and cancellation tests
Not-tested: Member and provider adapters until Task 10
```

## Task 10: 회원 profile, template renderer와 provider 결과 계약

**복잡도:** 높음
**의존성:** Task 2, 8, 9
**write scope:** `appointment-notification`

**Files:**
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/MemberNotificationProfileResolver.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/MemberNotificationProfile.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationTemplateCatalog.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationTemplateRenderer.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationProviderContracts.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationChannel.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/ResilientNotificationChannel.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/DummyNotificationChannel.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationMessageProvider.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/MemberNotificationProfileResolverTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationTemplateRendererTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/ResilientNotificationChannelTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationProviderContractTest.kt`

- [ ] **Step 1: 회원 결과 분류 RED 테스트**

timeout/429/5xx는 retryable, 탈퇴/없음은 `MEMBER_NOT_AVAILABLE`, 연락처 없음은 `DESTINATION_UNAVAILABLE`, 동의 거부는 `CONSENT_DENIED`, scope 불일치는 `MEMBER_SCOPE_MISMATCH`로 검증한다.
scope 불일치는 raw ID 없는 security audit/alert event도 남겨야 한다.

- [ ] **Step 2: template 안전성 RED 테스트**

알 수 없는 version, 허용되지 않은 field, control character, template-expression delimiter와 길이 초과를 거절한다. SMS plain text, email HTML/text와 push title/body 문맥별 escaping을 각각 검증하고 HTML/script injection을 실패 처리한다. deep-link는 URI scheme allowlist를 통과한 값만 허용한다. 이름·destination·locale·consent는 outbox parameter가 아니라 profile에서만 채워져야 한다.

- [ ] **Step 3: profile·catalog·renderer 구현**

```kotlin
data class MemberNotificationProfile(
    val displayName: String,
    val destination: String,
    val locale: Locale,
    val consent: NotificationConsent,
)

interface NotificationTemplateCatalog {
    fun find(key: NotificationTemplateKey, version: Int, channel: NotificationChannelType): NotificationTemplate?
}
```

profile은 worker 메모리에서 provider 호출 기간에만 유지한다. 동일 batch의 single-flight는 허용하되 영속 cache는 두지 않는다.
`MemberNotificationProfile`, `NotificationConsent`, `NotificationProviderRequest`, `NotificationProviderResult`와 template 객체는 `appointment-notification`의 runtime-only 계약이며 outbox 직렬화 대상이 아니다. persisted status/failure/suppression/channel/event/slot과 typed parameter는 Task 2의 `appointment-event` 계약만 사용하고 KDoc, `Serializable`, `serialVersionUID`를 갖는다. mapping test는 provider 결과가 Task 2의 닫힌 failure/suppression code 밖으로 새 문자열을 만들지 못하게 한다.

- [ ] **Step 4: provider 결과 계약으로 channel 변경**

```kotlin
interface NotificationChannel {
    val channelType: NotificationChannelType
    fun send(request: NotificationProviderRequest): NotificationProviderResult
}
```

`ResilientNotificationChannel`은 최종 예외를 삼키지 않고 닫힌 결과 또는 typed exception을 worker에 반환한다. provider reference는 opaque 문자셋·길이 검증을 통과한 경우만 저장한다.
provider idempotency key는 domain-separated HMAC digest만 전달하며 raw appointment/member/tenant/event 값을 포함하지 않는다. channel log, exception과 attempt row에는 raw key, credential 또는 provider payload를 남기지 않는다.

- [ ] **Step 5: Dummy privacy 정리**

Dummy channel은 이름·전화번호·본문을 로그나 history에 저장하지 않고 channel/event/outcome만 기록한다. 기존 `NotificationMessageProvider`는 새 renderer로 대체하고 사용처가 없으면 삭제한다.

- [ ] **Step 6: Task 10 검증**

Run: `./gradlew :appointment-notification:test --tests "*.MemberNotificationProfileResolverTest" --tests "*.NotificationTemplateRendererTest" --tests "*.NotificationProviderContractTest" --tests "*.ResilientNotificationChannelTest"`

Expected: profile 분류, channel별 escaping, version 고정, provider 실패 전달, opaque idempotency key, scope security alert와 raw 개인정보 비로그 PASS.

- [ ] **Step 7: Lore commit**

```text
Resolve recipients at send time and render only versioned templates

Constraint: Contact locale and consent belong to the member service
Rejected: Copying name and phone into the outbox | creates stale sensitive snapshots
Confidence: high
Scope-risk: broad
Tested: Profile template and provider contract tests
Not-tested: Full worker integration until Task 11
```

## Task 11: reminder 보정과 end-to-end worker 통합

**복잡도:** 높음
**의존성:** Task 6~10
**write scope:** `appointment-notification`, 관련 API integration tests

**Files:**
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationReminderRecoveryScanner.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/AppointmentReminderScheduler.kt`
- Modify after Task 8 creates: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorker.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationReminderRecoveryScannerTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxEndToEndTest.kt`

- [ ] **Step 1: downtime 경계 RED 테스트**

due 전에는 선기록 row 유지, 30분 catch-up window 안에서는 같은 idempotency key로 복구, window 뒤에는 `REMINDER_WINDOW_MISSED` suppression인지 확인한다.

- [ ] **Step 2: scheduler 역할 축소**

`AppointmentReminderScheduler`는 provider를 호출하지 않는다. `LeaderGroupElector`는 scanner trigger 중복을 줄이는 용도로만 사용하고 정확성은 unique key와 repository CAS가 보장한다.

- [ ] **Step 3: end-to-end worker 테스트**

appointment/outbox 준비 → claim → profile 조회 → template rendering → provider 성공 → attempt `SUCCESS` → outbox `SENT`와 raw field 제거를 한 테스트에서 검증한다. provider 실패 경로는 `RETRY_WAIT`와 `EXHAUSTED`를 검증한다.
provider 성공 직후 fenced completion store가 실패하거나 프로세스가 종료되는 crash window를 주입한다. lease 만료 뒤 같은 provider idempotency key로 복구하고, provider가 결과를 확정하지 못하면 `DELIVERY_RESULT_UNKNOWN` signal과 retry budget을 사용하며 exactly-once를 주장하지 않는지 검증한다.

- [ ] **Step 4: Task 11 검증**

Run: `./gradlew :appointment-notification:test --tests "*.NotificationReminderRecoveryScannerTest" --tests "*.NotificationOutboxEndToEndTest"`

Expected: reminder downtime 3경계와 worker 성공/retry/exhaustion PASS.

- [ ] **Step 5: Lore commit**

```text
Recover reminder materialization without restoring direct delivery

Constraint: Redis leadership is an optimization not a correctness boundary
Rejected: Sending missed reminders after the catch-up window | can surprise patients
Confidence: high
Scope-risk: moderate
Tested: Reminder recovery and worker end-to-end tests
Not-tested: Operational APIs until Task 12
```

## Task 12: metric, health, 안전한 조회와 수동 `re-notify`

**복잡도:** 높음
**의존성:** Task 9~11
**write scope:** `appointment-notification`, `appointment-api`

**Files:**
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxMetrics.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxHealthIndicator.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxAlertPolicy.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationStatusQueryService.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/NotificationOperationsController.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/NotificationReNotifyService.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/NotificationOperationsDtos.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxMetricsTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxHealthIndicatorTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxAlertPolicyTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/NotificationOperationsControllerTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/notification/NotificationReNotifyServiceTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/NotificationOperationsSecurityIntegrationTest.kt`

- [ ] **Step 1: low-cardinality metric RED 테스트**

metric tag에 member, appointment, outbox, tenant, clinic ID가 없고 channel/event/outcome/reason code만 있는지 검사한다. pending/oldest gauge가 full-table exact scan을 호출하지 않는지 store mock으로 검증한다.

- [ ] **Step 2: health RED 테스트**

schema/claim/key ring 실패는 readiness DOWN, provider/member circuit open·oldest 증가·retention 실패는 liveness 유지와 degraded detail인지 검증한다.

- [ ] **Step 3: metric과 health 구현**

설계의 8개 metric 이름을 그대로 사용한다. health detail은 안정적인 code와 count만 반환하고 raw ID를 포함하지 않는다. alert policy test는 oldest 5m/30m, `EXHAUSTED` 1/10건, provider failure 20%/50%, unknown 1/5건, lease recovery 5%, pending 10,000 증가의 warning/critical·해제 조건을 설계 표와 일치시키며 낮은 cardinality label만 허용한다. emergency key revoke와 key lookup 실패는 enqueue readiness 503과 Security·notification on-call 공동 alert를 검증한다.

- [ ] **Step 4: 안전한 상태 조회 API 구현**

응답은 `status`, `reasonCode`, `nextAttemptAt`, `exhaustedAt`, `recommendedAction`, `patientVisible`만 허용한다. `notification:read` capability와 exact clinic membership을 매 요청 검증하고 환자 화면에는 상세 suppression/provider 실패를 숨긴다. appointment ID 입력은 path의 tenant/clinic과 appointment row의 tenantGroupId/clinicId를 먼저 대조하며 outbox/attempt 질의에도 같은 scope predicate를 넣는다. 불일치는 403 또는 404로 fail-closed하고 raw ID를 응답·로그에 남기지 않는다.
운영 dashboard query는 RBAC 뒤 tenant, clinic, status, channel, event type, reason code와 시간 범위만 filter로 허용하며 raw member/appointment/outbox ID를 projection이나 filter로 제공하지 않는다.

- [ ] **Step 5: `re-notify` RED 테스트와 구현**

최대 100 appointment, dry-run, 이중 승인, 현재 member/consent/template 재검증, `SENT`와 `DELIVERY_RESULT_UNKNOWN` 기본 제외, 새 generation/key, 중단 후 같은 generation 재개를 검증한다.
transport authorization은 일반 tenant write matcher와 분리한 `notification:renotify` capability를 요구한다. 플랫폼 전용 service account/system identity와 exact clinic membership을 가진 human clinic approver를 분리 검증하며 patient, 일반 staff/admin, worker 외 system account는 거절한다.
clinic별·provider별 rate limit, 실행자·승인자·scope·generation·시작/중단/완료 시각·결과 수 audit, 현재 consent 재평가, suppression 우회 금지, 부분 실패 뒤 같은 generation 재개와 `SENT`/`DELIVERY_RESULT_UNKNOWN` 기본 제외도 검증한다.

```kotlin
data class ReNotifyCommand(
    val tenantGroupId: Long,
    val clinicId: Long,
    val appointmentIds: Set<Long>,
    val generation: String,
    val platformApproval: ApprovalReference,
    val clinicApproval: ApprovalReference,
    val dryRun: Boolean,
)
```

- [ ] **Step 6: Task 12 검증**

Run: `./gradlew :appointment-notification:test --tests "*.NotificationOutboxMetricsTest" --tests "*.NotificationOutboxHealthIndicatorTest" --tests "*.NotificationOutboxAlertPolicyTest"`

Run: `./gradlew :appointment-api:test --tests "*.NotificationOperationsControllerTest" --tests "*.NotificationReNotifyServiceTest" --tests "*.NotificationOperationsSecurityIntegrationTest"`

Expected: metric cardinality, health 분리, 전용 capability·service-account·clinic object 권한·redaction과 re-notify 제한 PASS.

- [ ] **Step 7: Lore commit**

```text
Expose notification health and recovery without exposing recipient data

Constraint: Operations need actionable states but not raw delivery inputs
Rejected: Completed-row redrive | completed inputs are intentionally redacted
Confidence: high
Scope-risk: broad
Tested: Metrics health status authorization and re-notify tests
Not-tested: Rollout cutover until Task 13
```

## Task 13A: rollout route gate와 privacy-safe 전환기 direct path

**복잡도:** 중간
**의존성:** Task 11, 12
**write scope:** `appointment-event`, `appointment-notification`, configuration

**Files:**
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationDeliveryRouteGate.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationDirectOutboxDelivery.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationEventListener.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxDispatcher.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorkStore.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationProperties.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfigurationTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationDirectOutboxDeliveryTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationEventListenerTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationDeliveryRouteGateTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationRolloutModeTest.kt`

- [ ] **Step 1: 상호 배타 route와 동일 행 claim RED 테스트**

기본 `SHADOW`에서는 background dispatcher의 provider 호출이 0회이고 전환기 event
route만 같은 outbox 행을 claim한다. `CANARY`는 allowlist 병원에서 worker만,
non-canary 병원에서는 event route만 claim한다. `ACTIVE`는 worker만, `PAUSED`는 두
route 모두 claim하지 않는다. 경쟁 테스트는 서로 다른 route가 같은 행을 동시에
claim해도 provider 호출이 최대 1회임을 반복 검증한다.

- [ ] **Step 2: rollout mode 구현**

`SHADOW`, `CANARY`, `ACTIVE`, `PAUSED`를 닫힌 enum으로 정의하고 기본값을
`SHADOW`로 둔다. `CANARY`는 양수 병원 ID allowlist가 반드시 있어야 하며 다른
모드의 stale allowlist는 시작 단계에서 거절한다. `PAUSED`도 enqueue와 retention은
유지한다.

- [ ] **Step 3: privacy-safe 전환기 event route 구현**

event listener는 예약 DTO, 이름, 전화번호나 완성 본문을 받지 않는다. 이벤트의 병원,
예약과 event type으로 command transaction이 만든 sendable outbox 행을 찾고 짧은 DB
transaction에서 조건부 claim한 뒤 기존 `NotificationOutboxWorker`에 전달한다. raw
`NotificationHistoryTable` 코드와 message provider는 복원하지 않는다.

- [x] **Step 4: Task 13 검증**

Run: `./gradlew :appointment-notification:test --tests "*.NotificationAutoConfigurationTest" --tests "*.NotificationDeliveryRouteGateTest" --tests "*.NotificationRolloutModeTest"`

Expected: default SHADOW, background provider 0회, direct/worker 상호 배타, 동일 행
provider 최대 1회, PAUSED backlog 보존, raw history bean 부재 PASS.

Run: `rg -n "patientName|patientPhone|recipient|payloadJson|errorMessage|NotificationMessageProvider|NotificationHistoryRepository" appointment-notification/src/main`

Expected: 허용 목록 밖 raw 개인정보·legacy history/message provider 참조 0건.

- [ ] **Step 5: Lore commit**

```text
Make rollout routes share one durable notification claim

Constraint: Production canary evidence is unavailable in the code-only PR
Rejected: Restoring the raw direct channel | duplicates provider logic and revives privacy risk
Confidence: high
Scope-risk: broad
Tested: Auto-configuration and rollout mode tests
Not-tested: 24-hour and 1,000-notification production canary
```

## Task 13B: 운영 canary와 전환기 listener 최종 제거

**상태:** 후속 운영 이슈 #204에서 수행
**의존성:** Task 13A 배포

- [ ] 병원 1곳에서 최소 24시간과 1,000개 논리 알림을 관찰한다.
- [ ] unknown/duplicate 0건, critical alert 0건, oldest 활성 행 age 5분 미만,
  suppression reason 설명 가능을 확인한다.
- [ ] 병원별 승인 후 allowlist를 확대하고 전체 병원을 `ACTIVE`로 전환한다.
- [ ] 전환기 event listener를 제거하고 raw legacy 물리 테이블의 retention 만료를
  확인한 뒤 별도 migration을 계획한다.

Task 13B는 운영 환경 변경과 실제 관측이 필요하므로 PR #203의 구현·검증 완료 조건에
포함하지 않는다. 후속 GitHub 이슈 #204가 병원별 승인, 24시간·1,000건 관측,
`ACTIVE` 전환과 listener 제거 gate를 추적한다.

## Task 14: 3개 DB, 실행 계획, 동시성·부하·retention 검증

**복잡도:** 높음
**의존성:** Task 4, 8, 9, 13A
**write scope:** integration/Gatling tests와 migration support

**Files:**
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/NotificationOutboxDialectIntegrationTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/NotificationOutboxQueryPlanTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/NotificationOutboxLoadIntegrationTest.kt`
- Create: `appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/NotificationOutboxScaleSimulation.kt`
- Modify: migration test entry points from Task 4

- [x] **Step 1: H2 claim lifecycle 통합 테스트**

10,000 active row와 10,000 종료 row에서 제한된 page, single claim, lease recovery, retention page를 검증한다.

- [x] **Step 2: PostgreSQL migration·claim·EXPLAIN**

singleton PostgreSQL launcher를 사용해 H2와 같은 10,000 active/10,000 종료 backlog를 준비하고 `ANALYZE`를 실행한다. clinic keyset, clinic 내부 ready candidate, recovery, retention, pending/oldest metric 질의의 `EXPLAIN`을 저장한다. 기대 index 이름을 검사하고 `Seq Scan`과 대규모 sort plan이면 실패한다.

- [x] **Step 3: MySQL migration·claim·EXPLAIN**

singleton MySQL launcher를 사용하고 PostgreSQL Gradle invocation이 끝난 뒤 실행한다. 같은 10,000 active/10,000 종료 backlog와 통계 갱신 뒤 동일한 5개 질의의 `EXPLAIN`을 저장한다. CAS 영향 row 수와 unique key를 검증하고 access type `ALL`, 대규모 filesort 또는 temporary sort면 실패한다.

- [x] **Step 4: 합성 공정성·backpressure 부하**

대형 clinic과 소형 clinic을 섞고 member resolver/provider를 포화시켜 다음 실패 기준을 executable assertion으로 둔다.

- 기준 장비 smoke profile에서 claim p95 `<=250ms`, p99 `<=500ms`
- retention 동시 실행 시 p95 증가 `<=50%`이며 절대 증가 `<=100ms`
- poll 1회 반환 row `<=batchSize`, 메모리 working set `<=batchSize + clinicCursorPageSize`
- 작은 clinic starvation `0`, clinic별·member resolver·channel/provider 관측 동시성은 설정 상한 이하

profile별 threshold는 `NotificationOutboxScaleSimulation` fixture와 runbook에 같은 값으로 기록하며, 나쁜 측정값을 보고서만 남기고 통과시키지 않는다.

- [x] **Step 5: Task 14 순차 검증**

Run: `./gradlew :appointment-api:test --tests "*.NotificationOutboxDialectIntegrationTest" --tests "*.NotificationOutboxQueryPlanTest"`

Run: `./gradlew :appointment-api:test --tests "*.NotificationOutboxLoadIntegrationTest"`

Run: `./gradlew :appointment-api:gatlingRun --simulation io.bluetape4k.clinic.appointment.api.NotificationOutboxScaleSimulation`

Expected: 3개 dialect lifecycle, 실행 계획, fair scheduling, 제한된 in-flight/retention PASS.

- [x] **Step 6: Lore commit**

```text
Prove notification claim and cleanup under real dialect load

Constraint: Claim correctness cannot depend on SKIP LOCKED support
Rejected: H2-only confidence | misses dialect index and timestamp behavior
Confidence: medium
Scope-risk: moderate
Tested: H2 PostgreSQL MySQL query-plan and load evidence
Not-tested: Production provider throughput
```

## Task 15: OpenAPI, README, 다이어그램과 운영 runbook

**복잡도:** 중간
**의존성:** Task 5, 12~14
**write scope:** 문서, OpenAPI contract tests

**Files:**
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `appointment-notification/README.md`
- Modify: `appointment-notification/README.ko.md`
- Modify: `appointment-api/README.md`
- Modify: `appointment-api/README.ko.md`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/CustomerAppointmentV2Controller.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentV2Controller.kt`
- Create or Modify: `docs/requirements/notification.md`
- Create: `docs/runbooks/notification-outbox-operations.md`
- Modify: `docs/requirements/assets/data-flow-05-notification-events-ko.svg`
- Modify: `docs/requirements/assets/data-flow-05-notification-events-en.svg`
- Regenerate: 대응 PNG 파일
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/NotificationOpenApiTest.kt`

- [x] **Step 1: OpenAPI contract RED 테스트**

self-service 성공·member mapping 거절, staff 대리 예약, legacy `403/404/422/503`, v2 member mapping `403/404/409/503`, retry/`EXHAUSTED` 상태 조회와 `re-notify` dry-run 예시를 실제 DTO와 error registry에서 생성해 검증한다. directory `503`에는 `Retry-After`가 있고, 모든 예시는 `SchedulingApiErrorResponse`를 사용하며 raw member 참조를 포함하지 않아야 한다. 현재 저장소의 self-service 생성 경로는 `POST /api/v2/appointment-requests`이므로 승인 설계의 축약 표현을 이 실제 경로로 맞춘다.
Task 15는 Task 5·12에서 이미 구현한 DTO, 오류 registry와 endpoint 동작을 변경하지 않는다. controller 수정은 OpenAPI annotation·example에 한정하고 runtime API behavior는 새로 추가하지 않는다.

- [x] **Step 2: README 한/영 동등성 갱신**

`README.md`와 `README.ko.md` 쌍에 durable outbox, `memberId`, at-least-once, 실패 관측과 운영 제한을 같은 의미로 추가한다. 한국어 문서에서는 기준이 되는 문서·데이터·경로를 문맥에 맞게 자연스럽게 표현한다.

- [x] **Step 3: 운영 runbook 작성**

다음 항목을 실제 설정명과 owner로 기록한다.

- alert 기준과 해제 조건
- 8개 metric에서 alert·조치로 이어지는 mapping과 RBAC dashboard filter
- 24시간·1,000건 canary gate
- provider 중단 시 `PAUSED`와 backlog 보존
- `re-notify` dry-run·이중 승인·재개
- key rotation 90일과 35일 overlap
- 정확한 key setting 이름, external secret reference, emergency revoke와 enqueue fail-closed 절차
- key lookup·rotation alert의 Security·notification on-call 공동 소유
- H2/PostgreSQL/MySQL migration, lock, 호환성 matrix
- old binary/new schema, new binary/old schema, new binary/new schema별 hold point와 rollback 조건
- staging 대표 backlog에서 측정한 DDL lock timeout·실제 lock/elapsed time
- `REMINDER_WINDOW_MISSED` 직원 후속 조치
- legacy caller의 변경 전·후 요청 예시와 idempotency key 재사용 규칙
- `OBSERVE` suppression 의미, override owner·expiry와 `ENFORCE` 전환 조건
- `MEMBER_ID_REQUIRED` 발생 시 caller 조치

- [x] **Step 4: 한/영 다이어그램 갱신**

`$bluetape-diagram`을 읽고 sequence/class/ERD 성격인 기존 자산은 SVG+PNG로 유지한다. 예약 command → outbox → worker → member DB/template/provider → redacted attempt 흐름을 한/영에서 같은 의미로 만든다.

- [x] **Step 5: Task 15 검증**

Run: `./gradlew :appointment-api:test --tests "*.NotificationOpenApiTest"`

Run: `git diff --check`

Run: `$bluetape-diagram`의 현재 audit/render 명령

Expected: OpenAPI example PASS, README 한/영 계약 일치, runbook 설정명 일치, 다이어그램 audit PASS.

- [x] **Step 6: Lore commit**

```text
Document the durable notification contract for callers and operators

Constraint: Workshop readers need equivalent Korean and English README and diagrams
Rejected: Documenting hypothetical DTOs | examples must come from implemented contracts
Confidence: high
Scope-risk: narrow
Tested: OpenAPI contract diff check and diagram audits
Not-tested: Human canary observation until deployment
```

## Task 16: 전체 검증, 검토 수렴과 lesson

**복잡도:** 높음
**의존성:** Task 1~15
**write scope:** 검증·review·lesson 문서

**Files:**
- Create: `docs/review/2026-07-31-issue-172-notification-outbox-review.md`
- Create: `docs/lessons/2026-07-31-issue-172-notification-outbox.md`
- Modify: `docs/superpowers/plans/2026-07-31-issue-172-notification-outbox-plan.md`의 실행 체크와 검토 기록

- [x] **Step 1: module-scoped 전체 테스트**

Run: `./gradlew :appointment-core:test :appointment-event:test :appointment-notification:test :appointment-api:test`

Expected: affected module tests PASS.

- [x] **Step 2: build와 정적 검사**

Run: `./gradlew :appointment-core:build :appointment-event:build :appointment-notification:build :appointment-api:build`

Run: `git diff --check`

Expected: BUILD SUCCESSFUL, whitespace 오류 0건.

- [x] **Step 3: 설계·계획 추적 검증**

설계 수용 기준 18개를 실제 test와 문서 경로에 다시 연결한다. 누락 기준이 있으면 해당 Task의 RED 단계로 돌아가고 이후 검증을 새로 실행한다.

- [x] **Step 4: 6개 관점 최종 코드 검토**

성능, 안정성, 보안, 운영, 개발자/API, 사용자/caller 관점을 독립 실행하고 주 세션이 중복 제거·심각도 정규화·문서/증거 검사를 수행한다. `P0=0`, `P1=0`이 될 때까지 해당 관점과 영향받은 테스트를 다시 실행한다.

- [x] **Step 5: lesson 작성**

caller transaction, lease fencing, 종료 비식별화, retry 계층 곱 방지, 다중 DB index 차이에서 얻은 실제 근거를 기록한다. 예상과 달랐던 점과 다음 작업자가 재사용할 guard를 포함한다.

- [x] **Step 6: Lore commit**

```text
Converge notification reliability against the approved design

Constraint: Type A delivery requires fresh multi-perspective evidence
Rejected: Treating module green as design completion | acceptance spans privacy operations and migration
Confidence: high
Scope-risk: broad
Tested: Affected module builds tests migration load docs and P0/P1 review
Not-tested: Production canary remains an operational gate
```

## 4. 구현 시작 전 게이트

1. 이 계획의 6개 관점 검토가 `P0=0`, `P1=0`이어야 한다.
2. 사용자가 현재 계획을 승인해야 한다.
3. 승인된 spec과 plan을 feature branch에 Lore commit으로 함께 기록한다.
4. Step 4 구현 전에 `$test-driven-development`와 실행 task에 해당하는 Kotlin 조건부 지침을 다시 읽는다.
5. 구현은 Task 1부터 순서대로 진행한다. 독립적인 read-only 검토만 병렬화하고, H2/PostgreSQL/MySQL·부하 검증은 순차 실행한다.

## 5. 계획 검토 기록

| 관점 | 최초 결과 | 최종 결과 | 반영 내용 |
|---|---|---|---|
| 성능 | P0=0, P1=2, P2=2, P3=0 | P0=0, P1=0 | 공정 claim 질의·index 계약, DB별 대표 backlog EXPLAIN, member/provider 상한과 실행 가능한 부하 임계값 반영 |
| 안정성 | P0=0, P1=2, P2=3, P3=1 | P0=0, P1=0 | schema 기동 차단, open attempt 복구, crash window, 단계별 cancellation과 상호 배타 canary 반영 |
| 보안·개인정보 | P0=0, P1=2, P2=3, P3=2 | P0=0, P1=0 | 운영 API 전용 capability·객체 권한, crypto 설정, provider key, channel별 escaping과 source scan 반영 |
| 운영 | P0=0, P1=2, P2=4, P3=2 | P0=0, P1=0 | alert/dashboard 계약, `re-notify` 통제, key revoke, canary/rollback과 migration hold point 반영 |
| 개발자·API | P0=0, P1=1, P2=2, P3=0 | P0=0, P1=0 | `MemberId` durable 소유권, provider runtime 계약과 Task 15 OpenAPI-only 경계 반영 |
| 사용자·caller | P0=0, P1=2, P2=2, P3=1 | P0=0, P1=0 | v2 오류 matrix, 실제 endpoint, legacy 전환 checklist와 OpenAPI 범위 반영 |
