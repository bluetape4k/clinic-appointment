# Issue #425 notification persistence capability 경계 설계

> 상태: 사용자 승인 계획을 기준으로 작성한 구현 전 명세
>
> 대상 이슈: [#425](https://github.com/bluetape4k/clinic-appointment/issues/425)
>
> 기준 ref: `origin/develop` / `5399ff63649f1cc78ae73f00d121c37195817fb8`
>
> 작업 branch: `refactor/issue-425-persistence-capability`

## 1. 문제와 목표

Issue #409에서 event write contract와 notification persistence 소유권을 분리했지만,
notification의 일부 public 생성자가 여전히 `JdbcNotificationOutboxRepository`를 직접
받는다. `NotificationApiConsumerFixture`도 이 concrete repository를 생성자 예제로
요구한다. 따라서 notification module 안에서 구현을 교체하거나 fixture를 가벼운 fake로
검증하기 어렵고, public API가 JDBC persistence 세부사항을 caller에게 전파한다.

이번 변경의 목표는 다음 두 경계를 capability port로 고정하는 것이다.

```text
notification worker/store  ──(work capability)──> notification persistence
notification metrics       ──(observation capability)──> notification persistence
waitlist adapter            ──(sink capability)──> waitlist persistence
```

`JdbcNotificationOutboxRepository`는 기존 SQL·transaction·lease·retry 구현을 그대로
유지하면서 두 capability를 구현한다. `JdbcNotificationOutboxWorkStore`와
`JdbcNotificationOutboxObservationStore`는 concrete repository 대신 capability를
주입받는다. `WaitlistNotificationOutboxAdapter`의 concrete repository 전용 overload는
제거하고 이미 존재하는 `WaitlistNotificationOutboxSink`만 유지한다. API consumer fixture는
capability 타입으로 생성자 경계를 컴파일한다. capability 교체는 public 생성자에 직접
fake 구현을 주입하는 범위로 한정하며, 이번 변경에서는 사용자 정의 fake bean을 Spring
자동 구성에 등록하거나 `@Primary`·`@Qualifier`로 대체하는 계약을 만들지 않는다.

목표에 포함하지 않는 것은 새 module/dependency/schema, table·column·migration 변경,
worker 알고리즘 변경, event contract 재설계, frontend 및 publish/release다.

## 2. 현재 근거와 재사용 기준

| 근거 | 확인된 사실 | 경계 문제 |
|---|---|---|
| `appointment-notification/.../NotificationOutboxWorkStore.kt` | `JdbcNotificationOutboxWorkStore`가 `Database`와 `JdbcNotificationOutboxRepository`를 받고, 짧은 `transaction(database)` 안에서 repository query/claim/lifecycle을 호출한다. | worker store public constructor가 JDBC class descriptor를 노출한다. |
| 같은 파일 | `JdbcNotificationOutboxObservationStore`가 `Database`와 concrete repository를 받고 `observeReady`를 호출한다. | metric observation wrapper도 concrete descriptor를 노출한다. |
| `.../persistence/JdbcNotificationOutboxRepository.kt` | ready clinic/candidate, observation, expired lease, claim, complete, retry, retention 메서드가 한 repository에 있고 자체 transaction을 열지 않는다. | 이 메서드 묶음이 추상화되지 않아 wrapper가 concrete 타입에 결합된다. |
| `.../persistence/WaitlistNotificationOutboxPersistence.kt` | `WaitlistNotificationOutboxSink`와 lambda primary constructor가 이미 존재하고, concrete repository 전용 overload가 중복된다. | source/ABI 표면에 불필요한 concrete overload가 남아 있다. |
| `src/consumerFixture/notification/.../NotificationApiConsumerFixture.kt` | work/observation helper의 repository 인자가 concrete 타입이며 expected inventory에도 repository가 있다. | fixture가 capability consumer가 아니라 implementation consumer가 된다. |
| 기존 테스트 | `io.bluetape4k.assertions`, `Base58.randomString(8)`, `SchemaUtils.createMissingTablesAndColumns`, singleton launcher, caller-owned Exposed transaction을 이미 사용한다. | 새 테스트도 같은 ecosystem 규칙을 따라야 한다. |
| #409 review/lesson | 남은 concrete constructor 노출을 #425 후속 범위로 명시했다. | 이번 명세의 source/ABI migration 근거다. |

GNO의 `bluetape4k-github`, `bluetape4k-docs`, `bluetape4k-wiki`에서 #425와 동일한
capability port 결정을 찾지 못했다. 따라서 live Issue #425, 현재 source, #409 review/lesson,
Gradle fixture 구현을 결정의 기준으로 삼는다.

## 3. 선택지와 결정

### 선택지 A — persistence capability port 두 개를 추가한다 (추천)

기존 `notification.persistence` package에 다음 public interface를 추가한다.

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

`JdbcNotificationOutboxRepository`가 두 interface를 구현하고, 두 JDBC wrapper의
constructor parameter와 내부 delegate property는 interface로 선언한다. 이 방식은
transaction을 이미 소유한 repository를 그대로 재사용하고, 생성자에 fake capability를
직접 주입해 worker와 metric wrapper를 검증할 수 있다. `Database`와
`NotificationLeaseTokenGenerator`는
wrapper의 책임이므로 유지한다.

### 선택지 B — `NotificationOutboxWorkStore`가 repository SQL을 직접 소유한다

worker-facing interface에 persistence query 메서드를 모두 넣고 JDBC store에서 SQL을
실행한다. wrapper 수는 줄지만 worker capability와 JDBC query/transaction 구현이 한 타입에
섞이고 observation/retention/direct-delivery가 같은 경계를 공유하지 못한다. 기존 repository
테스트와 SQL 소유권을 재배치해야 하므로 이번 좁은 refactor에는 과하다.

### 선택지 C — concrete overload를 유지하고 deprecated facade만 추가한다

기존 ABI를 보존할 수 있지만 public constructor가 concrete repository를 계속 노출한다.
source guard와 consumer fixture가 요구하는 capability 경계를 만족하지 못하므로 채택하지
않는다.

**결정:** 선택지 A를 적용한다. 선택지 B/C는 각각 범위 확대와 경계 실패 때문에 배제한다.

## 4. 책임과 API 경계

### 4.1 Work capability

`NotificationOutboxWorkPersistence`는 worker가 caller transaction 안에서 필요한 SQL
capability만 표현한다. DTO는 기존 `notification.persistence` 타입을 재사용하고, interface는
transaction을 열거나 coroutine dispatcher를 선택하지 않는다. `JdbcNotificationOutboxWorkStore`
는 `withContext(Dispatchers.IO) { transaction(database) { ... } }` 경계를 유지하며
`persistence` delegate를 통해 기존 메서드를 호출한다.

다음 semantics는 불변이다.

- fair cursor와 `eligibleScopes` 필터는 기존 SQL predicate와 같은 순서로 동작한다.
- claim/lease token 생성은 기존 `NotificationLeaseTokenGenerator`를 사용한다.
- expired lease recovery, fence 검증, complete/retry attempt audit는 같은 caller transaction에 남는다.
- retention delete는 `NotificationDeliveryAttempts`를 먼저 지우고 outbox를 지우는 순서를 유지한다.

### 4.2 Observation capability

`NotificationOutboxObservationPersistence`는 bounded ready 관찰 결과만 제공한다. wrapper는
`observationLimit` 양수 검증과 `transaction(database)`·`Dispatchers.IO` 경계를 유지하고,
`NotificationOutboxObservationStore`로 관찰 결과를 변환하는 현재 metric contract를 바꾸지
않는다. 전체 table exact scan이나 새로운 cache를 추가하지 않는다.

### 4.3 Waitlist sink capability

`WaitlistNotificationOutboxSink`는 이미 row를 caller transaction에 전달하는 단일 메서드다.
`WaitlistNotificationOutboxAdapter`는 lambda primary constructor와 sink constructor만
공개한다. `WaitlistNotificationOutboxRepository`를 받는 중복 overload는 제거한다.
repository는 계속 sink를 구현하므로 Kotlin source caller는 동일한 `Adapter(repository)`
호출을 sink overload로 컴파일할 수 있지만, concrete JVM constructor descriptor를 직접
호출한 binary consumer는 migration이 필요하다.

### 4.4 Auto-configuration과 consumer fixture

`NotificationAutoConfiguration`의 repository bean은 concrete implementation을 생성하고,
notification module 내부의 writer/work/observation bean 조립에 그대로 사용한다. 이 issue는
사용자가 capability 타입의 fake bean으로 기본 persistence bean을 교체하거나
`@Primary`·`@Qualifier` 우선순위를 조정하는 Spring 확장 계약을 제공하지 않는다. 따라서
Spring 자동 구성의 기본 후보·타입·객체 연결은 기존 `NotificationAutoConfigurationTest`로
회귀 검증하고, fake capability 검증은 public 생성자 직접 주입으로 수행한다. concrete
구현은 조립 경계에만 남기며 API public constructor에는 전파하지 않는다.

fixture는 다음처럼 capability 중심으로 compile한다.

```kotlin
private fun workStoreType(
    database: Database,
    persistence: NotificationOutboxWorkPersistence,
): JdbcNotificationOutboxWorkStore = JdbcNotificationOutboxWorkStore(database, persistence)

private fun observationStoreType(
    database: Database,
    persistence: NotificationOutboxObservationPersistence,
): JdbcNotificationOutboxObservationStore =
    JdbcNotificationOutboxObservationStore(database, persistence)
```

fixture source는 `JdbcNotificationOutboxRepository`를 import하거나 expected inventory에
직접 나열하지 않는다. `JdbcNotificationOutboxWorkStore`,
`JdbcNotificationOutboxObservationStore`, 두 capability와 `NotificationOutboxWriter`는
모듈 API가 의도적으로 제공하는 surface로 남긴다.

## 5. 호환성·migration

| 이전 API | 새 API | 영향과 migration |
|---|---|---|
| `JdbcNotificationOutboxWorkStore(Database, JdbcNotificationOutboxRepository)` | `JdbcNotificationOutboxWorkStore(Database, NotificationOutboxWorkPersistence)` | Kotlin positional source는 repository가 interface를 구현하므로 대체로 재컴파일된다. named-argument 호출은 `repository =`에서 `persistence =`로 바꾸며, JVM descriptor가 바뀌어 binary consumer도 재컴파일해야 한다. |
| `JdbcNotificationOutboxObservationStore(Database, JdbcNotificationOutboxRepository)` | `JdbcNotificationOutboxObservationStore(Database, NotificationOutboxObservationPersistence)` | 위와 같은 intentional ABI migration이며 named-argument 호출은 `persistence =`로 갱신한다. |
| `WaitlistNotificationOutboxAdapter(WaitlistNotificationOutboxRepository, Codec)` | `WaitlistNotificationOutboxAdapter(WaitlistNotificationOutboxSink, Codec)` | source는 sink supertype으로 이동하고, concrete overload의 binary 호출자는 재컴파일한다. |
| fixture helper의 concrete repository parameter | capability parameter | fixture가 implementation 대신 contract를 증명한다. |

ABI shim을 남기지 않는 이유는 shim 자체가 public concrete persistence constructor를
계속 노출하기 때문이다. migration note와 KDoc에 위 변경을 기록한다. table, column, Flyway
checksum, schema bootstrap은 변경하지 않으므로 schema rollback은 필요 없다. 문제가 생기면
capability interface와 wrapper 변경 commit을 revert하고, 기존 repository source를 복원해
module tests를 재실행한다.

## 6. 실패 모드와 방어선

| 실패 모드 | 방어선 | 검증 |
|---|---|---|
| wrapper가 다시 concrete repository를 받음 | constructor reflection/source guard가 parameter type을 검사한다. | notification contract test, fixture compile |
| repository가 capability 구현에서 method를 누락함 | interface 구현 compile과 method inventory test를 사용한다. | `:appointment-notification:compileKotlin` |
| transaction 경계가 wrapper에서 사라짐 | existing repository transaction negative test와 wrapper source scan을 유지한다. | transaction contract test, targeted worker tests |
| fair/lease/retry/readiness semantics가 변함 | 기존 lifecycle/lease/retry/readiness tests를 수정 없이 또는 capability fake와 함께 실행한다. | notification targeted regression |
| waitlist concrete overload가 다시 추가됨 | adapter constructor source/reflection guard와 sink test를 둔다. | waitlist adapter test |
| fixture가 raw implementation을 요구함 | fixture source 금지 anchor와 expected inventory를 갱신한다. | `compileModuleConsumerFixtures`, API variant task |
| 문서가 새 constructor를 잘못 설명함 | KDoc/README/migration note를 source와 read-back한다. | writer SPW-01..05, diff check |

## 7. bluetape4k ecosystem 적용 규칙

- assertion은 기존 `io.bluetape4k.assertions.shouldBeEqualTo`, `shouldBeTrue`,
  `shouldBeFalse`, `assertFailsWith`를 사용한다. JUnit/Kotlin generic assertion을 새
  테스트에 추가하지 않는다.
- H2 database name이나 새 test fixture 식별자가 필요하면
  `io.bluetape4k.codec.Base58.randomString(8)`을 사용한다. security lease token,
  durable checkpoint UUID, `System.nanoTime()` 같은 의미가 다른 식별자는 기계적으로 바꾸지
  않는다.
- Exposed 접근은 caller `transaction {}` 또는 wrapper의 기존 `transaction(database)` 안에
  둔다. `@Testcontainers`를 추가하지 않고 repository singleton launcher 정책을 재사용한다.
- 새 dependency/module/schema를 만들지 않는다. 기존 codec, assertions, Exposed helper,
  Gradle consumer fixture, notification ports를 우선 사용한다.
- public KDoc는 한국어로 작성하고, capability가 transaction ownership을 갖지 않는다는
  제약과 migration 영향을 명시한다.

## 8. 수용 기준과 DoD

1. `JdbcNotificationOutboxWorkStore`와 `JdbcNotificationOutboxObservationStore`의 public
   constructor parameter에 `JdbcNotificationOutboxRepository`가 없다.
2. `WaitlistNotificationOutboxAdapter`의 public constructor parameter에
   `WaitlistNotificationOutboxRepository`가 없다.
3. `JdbcNotificationOutboxRepository`가 두 capability interface를 구현하고 기존 method
   inventory/transaction semantics를 유지한다.
4. notification consumer fixture가 concrete repository를 import하지 않고 capability
   parameter로 compile한다. Gradle expected inventory와 source guard가 일치한다.
5. transaction negative, fair readiness, lease/fence, retry, retention, observation과
   waitlist adapter 회귀 tests가 통과한다.
6. 새/수정 테스트가 bluetape4k assertions와 필요한 `Base58.randomString(8)` 규칙을 따른다.
7. 모듈 compile/test, consumer fixture/API variant, source/jar boundary, 기본 Spring
   auto-configuration wiring, `git diff --check`와 7-Tier review가 통과한다. migration SQL
   SHA는 불변이다.
8. 이 spec/plan/review/lesson, KDoc/README migration note가 실제 source와 일치하고
   `SPW-01..05`가 각각 PASS다.

## 9. 7-Tier review와 승인 경계

spec과 plan은 performance, stability, security, operator/Ops, developer/API, user/caller
여섯 관점과 main-session integration을 각각 검토한다. P0/P1은 구현 전에 고치고, P2/P3는
수정하거나 후속 이슈와 rationale을 남긴다. 현재 Issue #425의 비목표인 schema/module/
dependency 변경을 review가 발견하면 계획을 중지하고 범위를 다시 승인받는다.

이 명세에서 구현으로 넘어가는 조건은 사용자 승인, `WF-04A` topology/lane receipt,
spec/plan writer gate, latest integrated review `P0=0/P1=0`이다. PR 생성은 승인 계획의
repo/base/head authority를 사용하고, merge는 exact live head에 대한 별도 fresh `승인` 뒤에
수행한다.

## 10. 문서 작성 점검

- [x] SPW-01: Issue #425, current source, #409 review/lesson, fixture와 exact identifiers를
  source ledger로 고정했다.
- [x] SPW-02: 문제·대안·결정·경계·호환성·실패 모드·수용 기준·DoD를 포함했다.
- [x] SPW-03: 한국어 technical register를 적용하고 code/command/API token을 보존했다.
- [x] SPW-04: public constructor와 repository method를 현재 source와 대조했다.
- [x] SPW-05: Markdown heading/table/code fence와 미확정 구현 항목을 read-back했다.
