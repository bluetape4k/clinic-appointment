# Issue #425 persistence capability 경계 구현 계획

> **For agentic workers:** 이 계획은 `bluetape-full-feature` Type-A 순서를 따른다. 각 단계는
> fresh evidence를 기록하고 선행 단계가 PASS인 경우에만 다음 단계로 이동한다.

**목표:** notification worker/observation/waitlist public 생성자와 consumer fixture에서
concrete persistence를 제거하고 capability port, transaction semantics, ABI/source
migration을 검증한다.

**아키텍처:** 기존 `notification.persistence` package에 work/observation capability를
추가하고 `JdbcNotificationOutboxRepository`가 구현한다. JDBC wrapper는 capability와
`Database`를 받아 기존 `transaction(database)` 경계를 유지한다. capability 교체는
public 생성자에 fake 구현을 직접 주입하는 범위로 한정하고, waitlist adapter는 이미 존재하는
sink port만 공개한다. API auto-configuration은 사용자 정의 fake bean 대체 계약 없이 concrete
implementation을 내부에서 조립한다.

**기술 스택:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed 1 JDBC, Gradle Kotlin DSL,
JUnit 5, `io.bluetape4k.assertions`, `io.bluetape4k.codec.Base58`, 기존 singleton
launcher/test helpers, GitHub consumer fixture.

---

## 승인·기준·실행 순서

- 명세: [2026-08-26-issue-425-persistence-capability-design.ko.md](../specs/2026-08-26-issue-425-persistence-capability-design.ko.md)
- 이슈: [#425](https://github.com/bluetape4k/clinic-appointment/issues/425)
- 저장소: `bluetape4k/clinic-appointment`
- base: `develop`
- head: `refactor/issue-425-persistence-capability`
- 기준 SHA: `5399ff63649f1cc78ae73f00d121c37195817fb8`
- 새 module/dependency/schema/migration과 frontend/publish/release는 범위 밖이다.
- root `develop`의 사용자 dirty path는 worktree 밖에서 보존한다.

실행 순서는 `baseline → RED contract test → capability implementation → wrapper/waitlist
boundary → fixture/build guard → behavior regression → docs → 7-Tier/performance → verifier
→ lesson/PR`이다. PR 생성 authority는 이 계획에 명시되어 있고, merge는 exact live head에
대한 별도 fresh `승인` 뒤에만 수행한다.

## 파일 책임 지도

| 책임 | 파일 | 변경 |
|---|---|---|
| capability contract | `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationOutboxPersistenceCapabilities.kt` | work/observation interface와 KDoc 생성 |
| JDBC implementation | `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/JdbcNotificationOutboxRepository.kt` | 두 interface 구현 및 `override` 추가 |
| worker/metric wrapper | `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorkStore.kt` | constructor/delegate capability 변경, transaction 보존 |
| Spring wiring/API caller | `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`, `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfigurationTest.kt`, `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NotificationOutboxCanarySimulationIntegrationTest.kt` | 내부 concrete 조립과 `persistence` named-argument migration 검증 |
| waitlist adapter | `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/WaitlistNotificationOutboxPersistence.kt` | concrete overload 제거 |
| consumer fixture/Gradle | `src/consumerFixture/notification/.../NotificationApiConsumerFixture.kt`, `build.gradle.kts` | repository 유입 제거, capability inventory/guard 추가 |
| boundary regression | `appointment-notification/src/test/.../NotificationPersistenceCapabilityContractTest.kt` | reflection/source/API guard 생성 |
| public docs | `appointment-notification/README.md`, `README.ko.md` | capability와 ABI migration section 추가 |
| durable artifacts | `docs/superpowers/{specs,plans,reviews,checklists}/...`, `docs/lessons/...` | Korean spec/plan/review/lesson 및 gate evidence |

## Task 0: 기준선과 workflow receipt

**Files:** 코드 변경 없음. checklist/receipt와 command evidence만 갱신한다.

- [ ] **Step 0.1: run/topology 확인**

```bash
python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
  --state-root /Users/debop/work/bluetape4k/clinic-appointment/.bluetape \
  verify --run-id 20260826T095644Z-e0df092c
```

Expected: run state `running`, topology 등록 이후 sequence.

- [ ] **Step 0.2: worktree/base/dirty 경계 확인**

```bash
git status --short --branch
git rev-parse HEAD origin/develop
git diff --name-only origin/develop...HEAD
```

Expected: feature worktree는 계획 artifact 외 clean, 기준 SHA와 root dirty 분리.

- [ ] **Step 0.3: module baseline 실행**

```bash
./gradlew :appointment-notification:test --no-daemon --console=plain --max-workers=1
./gradlew :appointment-api:compileTestKotlin --no-daemon --console=plain --max-workers=1
```

Expected: 두 command `BUILD SUCCESSFUL`; 실패하면 raw output을 기록하고 구현을 중지한다.

- [ ] **Step 0.4: migration fingerprint**

```bash
sha256sum appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V14__add_notification_outbox.sql \
  appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V19__add_waitlist_delivery.sql \
  appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V21__add_tenant_query_isolation.sql \
  appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V22__add_appointment_messaging_outbox_lease.sql
git diff --check
```

Expected: 12개 checksum과 diff-check 결과를 ledger에 고정한다.

## Task 1: RED capability contract와 fixture guard

**Files:**

- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationPersistenceCapabilityContractTest.kt`
- Modify: `src/consumerFixture/notification/kotlin/io/bluetape4k/clinic/appointment/consumer/NotificationApiConsumerFixture.kt`
- Modify: `build.gradle.kts`

- [ ] **Step 1.1: constructor/source RED test 작성**

```kotlin
@Test
fun `JDBC wrapper public constructor는 concrete repository를 노출하지 않는다`() {
    JdbcNotificationOutboxWorkStore::class.constructors
        .flatMap { it.parameters }
        .none { it.type.classifier == JdbcNotificationOutboxRepository::class }
        .shouldBeTrue()
    JdbcNotificationOutboxObservationStore::class.constructors
        .flatMap { it.parameters }
        .none { it.type.classifier == JdbcNotificationOutboxRepository::class }
        .shouldBeTrue()
}

@Test
fun `repository는 두 capability를 구현한다`() {
    NotificationOutboxWorkPersistence::class.java
        .isAssignableFrom(JdbcNotificationOutboxRepository::class.java).shouldBeTrue()
    NotificationOutboxObservationPersistence::class.java
        .isAssignableFrom(JdbcNotificationOutboxRepository::class.java).shouldBeTrue()
}

@Test
fun `waitlist adapter public constructor는 concrete repository를 노출하지 않는다`() {
    WaitlistNotificationOutboxAdapter::class.constructors
        .flatMap { it.parameters }
        .none { it.type.classifier == WaitlistNotificationOutboxRepository::class }
        .shouldBeTrue()
}
```

파일 source guard는 `src/consumerFixture/notification`의 Kotlin source를 읽어
`JdbcNotificationOutboxRepository`가 없음을 `shouldBeFalse()`로 증명한다. 새 테스트에는
`io.bluetape4k.assertions`만 사용한다.

- [ ] **Step 1.2: RED 실행**

```bash
./gradlew :appointment-notification:test --tests \
  'io.bluetape4k.clinic.appointment.notification.persistence.NotificationPersistenceCapabilityContractTest' \
  --no-daemon --no-build-cache --rerun-tasks --max-workers=1 --console=plain
```

Expected: interface 부재 또는 concrete constructor assertion failure. failure가 이번
경계 부재를 가리키는지 확인한 뒤 Task 2로 진행한다.

- [ ] **Step 1.3: fixture helper를 capability parameter로 준비**

```kotlin
private fun workStoreType(database: Database, persistence: NotificationOutboxWorkPersistence) =
    JdbcNotificationOutboxWorkStore(database, persistence)
private fun observationStoreType(database: Database, persistence: NotificationOutboxObservationPersistence) =
    JdbcNotificationOutboxObservationStore(database, persistence)
```

fixture import/expected inventory에서 concrete repository를 제거하고 두 capability 이름을
추가한다. wrapper class 자체는 public inventory로 유지한다.

## Task 2: capability interface와 repository 구현

**Files:**

- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/NotificationOutboxPersistenceCapabilities.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/JdbcNotificationOutboxRepository.kt`

- [ ] **Step 2.1: 최소 method inventory interface 추가**

```kotlin
interface NotificationOutboxWorkPersistence {
    fun currentDatabaseTime(): Instant
    fun findReadyClinicKeys(cursor: NotificationFairCursor?, limit: Int,
                            eligibleScopes: Set<TenantClinicScope>? = null): List<NotificationClinicKey>
    fun findReadyCandidates(key: NotificationClinicKey, cursorId: Long?, limit: Int): List<NotificationCandidate>
    fun findExpiredProcessingIds(limit: Int, eligibleScopes: Set<TenantClinicScope>? = null): List<Long>
    fun claim(candidateId: Long, owner: String, token: String): ClaimedNotification?
    fun claimReadyForDirect(scope: TenantClinicScope, appointmentId: AppointmentId,
                            eventType: NotificationEventType, owner: String, token: String): ClaimedNotification?
    fun recoverExpired(candidateId: Long, owner: String, token: String): ClaimedNotification?
    fun complete(command: CompleteNotificationCommand): Boolean
    fun scheduleRetry(command: RetryNotificationCommand): Boolean
    fun deleteTerminalBatch(status: NotificationOutboxStatus, retention: Duration, limit: Int): Int
}

fun interface NotificationOutboxObservationPersistence {
    fun observeReady(limit: Int): NotificationOutboxObservation
}
```

interface는 transaction/dispatcher/codec/token 생성 책임을 갖지 않고 기존 persistence DTO를
재사용한다.

- [ ] **Step 2.2: repository 구현 선언과 override만 추가**

```kotlin
class JdbcNotificationOutboxRepository(
    private val codec: NotificationOutboxCodec,
    private val leaseDuration: Duration,
) : NotificationOutboxWriter,
    NotificationOutboxWorkPersistence,
    NotificationOutboxObservationPersistence {
```

기존 SQL method body와 validation은 바꾸지 않고 capability method에 `override`를 추가한다.

- [ ] **Step 2.3: GREEN compile/test**

```bash
./gradlew :appointment-notification:compileKotlin \
  :appointment-notification:test --tests \
  'io.bluetape4k.clinic.appointment.notification.persistence.NotificationPersistenceCapabilityContractTest' \
  --no-daemon --no-build-cache --rerun-tasks --max-workers=1 --console=plain
```

Expected: contract test PASS, `BUILD SUCCESSFUL`.

- [ ] **Step 2.4: Lore checkpoint commit**

```text
알림 JDBC repository를 capability port로 구현한다

Constraint: lease·retry·retention 동작과 schema를 변경하지 않는다
Rejected: worker store에 SQL을 직접 옮기는 방식 | query 소유권과 transaction 경계를 넓힌다
Confidence: high
Scope-risk: moderate
Directive: wrapper는 capability만 의존하고 concrete class는 조립 경계에 남긴다
Tested: notification compile 및 capability contract GREEN
Not-tested: 전체 lifecycle/fixture regression
```

## Task 3: wrapper constructor와 waitlist overload 경계 수렴

**Files:**

- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorkStore.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/WaitlistNotificationOutboxPersistence.kt`
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence/WaitlistNotificationOutboxAdapterTest.kt`

- [ ] **Step 3.1: wrapper parameter/delegate 교체**

```kotlin
class JdbcNotificationOutboxWorkStore(
    private val database: Database,
    private val persistence: NotificationOutboxWorkPersistence,
    private val tokenGenerator: NotificationLeaseTokenGenerator = SecureNotificationLeaseTokenGenerator(),
) : NotificationOutboxWorkStore, NotificationDirectOutboxStore

class JdbcNotificationOutboxObservationStore(
    private val database: Database,
    private val persistence: NotificationOutboxObservationPersistence,
    private val observationLimit: Int = 10_001,
) : NotificationOutboxObservationStore
```

`repository.` 호출을 `persistence.`로 바꾸되 `withContext(Dispatchers.IO)`와
`transaction(database)`는 보존한다. worker-facing ports는 이미 abstraction이므로 불필요한
수정을 하지 않는다. positional Kotlin caller는 구현체가 capability를 구현하는 이점을
그대로 사용하고, named-argument caller는 `repository =`를 `persistence =`로 migration한다.

- [ ] **Step 3.2: waitlist concrete overload 삭제**

`WaitlistNotificationOutboxAdapter(WaitlistNotificationOutboxRepository, Codec)`만 삭제하고
lambda primary와 `WaitlistNotificationOutboxSink` constructor를 유지한다. repository는
계속 sink를 구현한다.

- [ ] **Step 3.3: waitlist test와 behavior regression**

```kotlin
private val repository: WaitlistNotificationOutboxSink = WaitlistNotificationOutboxRepository()
private val adapter = WaitlistNotificationOutboxAdapter(repository)
```

```bash
./gradlew :appointment-notification:test --tests \
  'io.bluetape4k.clinic.appointment.notification.NotificationOutboxWorkerLeaseTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationSchemaReadinessTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.persistence.JdbcNotificationOutboxRepositoryTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.persistence.WaitlistNotificationOutboxAdapterTest' \
  --no-daemon --no-build-cache --rerun-tasks --max-workers=1 --console=plain
```

Expected: lease/fence, readiness, transaction, idempotency, codec, waitlist tests PASS.

- [ ] **Step 3.4: Lore checkpoint commit**

```text
알림 wrapper의 concrete persistence 생성자 노출을 닫는다

Constraint: caller transaction, lease fence, retry, readiness semantics를 보존한다
Rejected: public deprecated concrete overload 유지 | boundary guard가 계속 실패한다
Confidence: high
Scope-risk: moderate
Directive: fixture와 ABI/source migration 문서를 새 생성자 계약으로 맞춘다
Tested: targeted lease/readiness/repository/waitlist tests
Not-tested: consumer fixture variant와 7-Tier review
```

## Task 4: consumer fixture와 Gradle source/API guard

**Files:** `src/consumerFixture/notification/.../NotificationApiConsumerFixture.kt`,
`build.gradle.kts`, 필요 시
`appointment-api/src/test/.../KotlinProductionPatternComplianceTest.kt`.

- [ ] **Step 4.1: fixture source와 inventory 갱신**

`JdbcNotificationOutboxRepository` import/helper/expected symbol을 제거하고
`NotificationOutboxWorkPersistence`, `NotificationOutboxObservationPersistence`를 추가한다.

- [ ] **Step 4.2: fixture forbidden source guard 추가**

`src/consumerFixture/notification`의 `.kt` source만 읽어
`JdbcNotificationOutboxRepository`가 없으면 통과하고, 재유입 시 task를 실패시킨다.
기존 event jar forbidden guard와 API variant/task graph는 재사용한다.

- [ ] **Step 4.3: fixture/API/jar 검증**

```bash
./gradlew :appointment-notification:jar \
  compileAppointmentNotificationConsumerFixture compileModuleConsumerFixtures \
  assertModuleConsumerFixtureApiVariants assertModuleConsumerFixtureTaskGraph \
  --no-daemon --no-build-cache --rerun-tasks --max-workers=1 --console=plain
jar tf appointment-notification/build/libs/*.jar | rg 'NotificationOutbox(Work|Observation)Persistence|JdbcNotificationOutboxWorkStore|JdbcNotificationOutboxObservationStore'
```

Expected: `apiElements`, inventory, source guard, task graph, capability/wrapper jar entries PASS.

- [ ] **Step 4.4: Lore checkpoint commit**

```text
consumer fixture에서 persistence 구현 유입을 차단한다

Constraint: API elements와 기존 fixture task graph를 유지한다
Rejected: fixture에서 concrete repository를 생성하는 방식 | public contract를 증명하지 못한다
Confidence: high
Scope-risk: narrow
Directive: final review에서 fixture source와 jar evidence를 exact head로 재검증한다
Tested: fixture compile, variant, task graph, source/jar guard
Not-tested: README와 lesson
```

## Task 5: assertion/reuse와 module behavior 검증

- [ ] **Step 5.1: ecosystem source scan**

```bash
if rg -n 'org\.junit\.jupiter\.api\.Assertions|kotlin\.test\.assert|assertThrows|!!' appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence; then
  echo '금지된 raw assertion 또는 강제 unwrap 발견' >&2
  exit 1
fi
rg -q 'Base58\.randomString\(8\)' appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence
rg -q 'SchemaUtils\.createMissingTablesAndColumns|transaction\(' appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/persistence
```

Expected: bluetape assertions, Base58 test suffix, migration-safe schema setup, caller transaction.

- [ ] **Step 5.2: notification regression**

```bash
./gradlew :appointment-notification:test \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationOutbox*' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationRetentionRunnerTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationSchemaReadinessTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.persistence.*' \
  --no-daemon --no-build-cache --rerun-tasks --max-workers=1 --console=plain
```

Expected: XML failures/errors `0`, `BUILD SUCCESSFUL`; DB/container checks are sequential.

- [ ] **Step 5.3: API compile/test와 schema 불변**

```bash
./gradlew :appointment-api:compileKotlin :appointment-api:compileTestKotlin \
  :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.config.NotificationOutboxCanarySimulationIntegrationTest' \
  --no-daemon --no-build-cache --rerun-tasks --max-workers=1 --console=plain
git diff --exit-code origin/develop...HEAD -- 'appointment-api/src/main/resources/db/migration/**'
git diff --exit-code --cached -- 'appointment-api/src/main/resources/db/migration/**'
git diff --exit-code -- 'appointment-api/src/main/resources/db/migration/**'
```

Expected: API compile/simulation PASS, migration diff empty and Task 0 checksum unchanged.

## Task 6: Korean 문서와 7-Tier artifact

**Files:** `appointment-notification/README.md`, `README.ko.md`,
`docs/superpowers/reviews/2026-08-26-issue-425-persistence-capability-{spec,plan,implementation}-review.ko.md`,
`docs/lessons/2026-08-26-issue-425-persistence-capability.ko.md`.

- [ ] **Step 6.1: README parity/migration section**

두 README에 같은 Korean section을 추가한다. 내용은 `NotificationOutboxWorkPersistence`,
`NotificationOutboxObservationPersistence`, `WaitlistNotificationOutboxSink`, concrete
JDBC 조립 위치, intentional JVM ABI migration, schema 불변을 설명한다. source path와
constructor 예시는 current source와 일치해야 한다.

- [ ] **Step 6.2: spec/plan/review/lesson 생성과 writer gate**

구현 검증 결과를 반영해 implementation-review와 lesson을 먼저 생성하고, spec/plan/review/
lesson 각 artifact에 SPW-01..05와 Korean naturalness KO-01..07을 기록한다. exact SHA,
type, 명령·결과, P0/P1 disposition을 source와 대조한 뒤에 Step 6.3 audit를 실행한다.

- [ ] **Step 6.3: terminology audit/diff**

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs appointment-notification/README.md appointment-notification/README.ko.md docs/superpowers/specs/2026-08-26-issue-425-persistence-capability-design.ko.md docs/superpowers/plans/2026-08-26-issue-425-persistence-capability-plan.ko.md docs/superpowers/reviews/2026-08-26-issue-425-persistence-capability-spec-review.ko.md docs/superpowers/reviews/2026-08-26-issue-425-persistence-capability-plan-review.ko.md docs/superpowers/reviews/2026-08-26-issue-425-persistence-capability-implementation-review.ko.md docs/lessons/2026-08-26-issue-425-persistence-capability.ko.md
git diff --check
```

Expected: unexplained terminology finding `0`, diff check PASS.

## Task 7: risk, 7-Tier, performance/stability, verifier

- [ ] **Step 7.1: triggered risk table** — public ABI/source, DB transaction/lease/retry,
  fixture variant, Spring auto-configuration, and docs drift에 signal/mitigation/rerun/rollback를 기록한다.
- [ ] **Step 7.2: 6 perspectives + integration** — performance, stability, security,
  operator/Ops, developer/API, user/caller와 main integration을 spec/plan/current diff에
  각각 적용한다. P0/P1은 수정 후 affected test/lens를 rerun하고 P2/P3는 수정 또는 follow-up rationale을 남긴다.
- [ ] **Step 7.3: performance/stability scan** — `Dispatchers.IO`, query/lock/round-trip,
  cancellation/cleanup, observation bound, singleton launcher와 sequential Gradle evidence를 확인한다.
- [ ] **Step 7.3A: Spring 기본 wiring 회귀** — `NotificationAutoConfigurationTest`의
  V21 readiness context에서 `JdbcNotificationOutboxRepository`,
  `NotificationOutboxWorkStore`, `NotificationOutboxObservationStore`의 기본
  후보와 연결을 확인한다. 사용자 정의 capability fake bean, `@Primary`, `@Qualifier`
  대체 계약은 추가하지 않으며, fake 검증은 constructor 직접 주입 test로 한정한다.
- [ ] **Step 7.4: verifier command**

```bash
./gradlew :appointment-notification:check :appointment-api:compileTestKotlin \
  compileModuleConsumerFixtures assertModuleConsumerFixtureApiVariants \
  --no-daemon --no-build-cache --rerun-tasks --max-workers=1 --console=plain
git status --short
git diff --check origin/develop...HEAD
git diff --check
git diff --check --cached
git diff --stat origin/develop...HEAD
```

Expected: checks PASS, spec/plan acceptance mapping complete, P0=0/P1=0.

- [ ] **Step 7.5: final review/Lore commit read-back** — Step 6.2에서 생성한 lesson과
  implementation review가 context/decision/outcome/verification/surprise/future guard와
  SPW-01..05를 포함하는지 확인하고, final review artifact와 함께 Korean Lore commit으로
  수렴한다.

## Task 8: PR delivery와 merge-ready stop

- [ ] **Step 8.1: CG-11/12 refresh/push** — current AGENTS/skills/common-gates/Issue #425 metadata와
  exact local head를 다시 읽고 `git push origin refactor/issue-425-persistence-capability` 후 remote SHA를 확인한다.
- [ ] **Step 8.2: Korean PR create/read-back**

```bash
gh pr create --repo bluetape4k/clinic-appointment --base develop --head refactor/issue-425-persistence-capability --title '리팩터링(notification): persistence capability 경계를 닫는다' --body-file .bluetape/candidates/issue-425-pr-body.md
gh pr view --repo bluetape4k/clinic-appointment --json number,title,body,headRefOid,baseRefName,labels,assignees,milestone,url
```

body 마지막 section은 `## DoD Status`이며 Issue assignee/labels/milestone parity를 확인한다.
- [ ] **Step 8.3: exact-head CI/review와 merge-ready report** — required CI, reviews/threads,
  body, mergeability를 읽고 `Required checks: X/Y; N/A: N; Blocked: 0`, exact PR/head,
  P0/P1=0, known risks, unchecked `CG-16/17/18`을 보고한다.
- [ ] **Step 8.4: fresh merge approval hold** — merge-ready report 후 새 `승인` 전에는
  `gh pr merge`, branch/worktree 삭제를 실행하지 않는다.

## Rollback / rerun

- interface/wrapper compile failure: checkpoint `800aa0f9a6f526aeff759e71848a8cbf3d6967fe`와
  `git status --short`를 먼저 기록하고, 승인된 파일만
  `git diff --binary 5399ff63649f1cc78ae73f00d121c37195817fb8 800aa0f9a6f526aeff759e71848a8cbf3d6967fe -- appointment-notification/src/main appointment-notification/src/test | git apply --check -R`로 충돌을 확인한 뒤 같은 diff를 `git apply -R`로 되돌린다. 이후 root dirty path를 건드리지 않고 baseline test를 rerun하고, `git status --short`와 해당 module test를 다시 확인한다.
- behavior regression: raw failure를 진단하고 해당 RED/GREEN과 targeted regression을 처음부터 rerun한다.
- fixture/API variant failure: source inventory·scope·task graph를 고친 뒤 producer jar→fixture→variant 순서로 rerun한다.
- docs/source drift: current SHA에 맞춰 spec/plan/review/lesson/README를 read-back하고 writer gate를 rerun한다.
- CI failure: exact-head check/log를 진단하고 affected task만 고친 뒤 remote head/body evidence를 갱신한다.

## 계획 자체 점검

- spec의 acceptance 1–3은 Task 2/3, 4는 Task 4, 5–7은 Task 5, 8은 Task 6/7에 매핑된다.
- 미확정 placeholder 항목 없이 실제 파일·타입·명령·예상 결과를 적었다.
- RED가 interface 구현보다 앞서고, fixture/docs는 GREEN 이후이며, PR/merge는 모든 검증과 fresh approval 뒤다.
- migration gate는 committed/staged/worktree 세 상태에서 `git diff --exit-code`로 빈 결과를
  강제한다. 문서 audit는 implementation review와 lesson 생성 후에 실행한다.
- final evidence는 `git status --short`, committed-range/staged/worktree `diff --check`와
  tracked artifact read-back을 함께 기록해 untracked 문서를 PASS로 오인하지 않는다.
