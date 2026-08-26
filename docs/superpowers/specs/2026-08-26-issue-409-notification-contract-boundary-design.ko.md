# Issue #409 알림 event contract와 persistence contract 분리 설계

> 상태: 추천 설계 승인 완료, 구현 전 명세
>
> 기준 branch: `refactor/issue-409-contract-boundary`
>
> 기준 develop SHA: `8d68b1e3bc8c944bc1ba1f9e6e8233417d23cff8`
>
> 대상 이슈: [#409](https://github.com/bluetape4k/clinic-appointment/issues/409)
>
> 선행 이슈: [#393](https://github.com/bluetape4k/clinic-appointment/issues/393)

## 1. 문제와 목표

`appointment-event`는 예약 도메인 event DTO와 codec뿐 아니라 notification outbox의
Exposed table, JDBC repository, claim/lease DTO, retry command까지 함께 제공한다. 그
결과 `appointment-api`의 public writer가 event 모듈의 구체 repository를 생성자 타입으로
받고, `appointment-notification`의 worker가 event 모듈의 persistence package를 직접
import한다. `appointment-event`를 재사용하려는 호출자는 event contract만 필요해도
notification persistence 구현과 같은 artifact 경계를 함께 보게 된다.

이 이슈의 목표는 다음 한 방향을 코드와 Gradle API fixture로 고정하는 것이다.

```text
appointment-api  ──(event write contract)──> appointment-event
appointment-notification.persistence ──(event contract)──> appointment-event
appointment-api  ──(내부 구현 조립)──> appointment-notification
```

`appointment-event`에는 외부 호출자가 event를 만들고 caller transaction에서 기록할 때
필요한 순수 contract만 남긴다. `appointment-notification`에는 notification row의
물리 schema, JDBC write implementation, claim/lease/retry/retention lifecycle을 모은다.
API 애플리케이션은 event 모듈의 write port만 의존하고, concrete JDBC repository는
notification auto-configuration이 조립한다.

이번 변경으로 기존 outbox의 table 이름·column·Flyway migration·transaction 경계·lease
fencing·retry 상태 전이는 바꾸지 않는다. 새 module, 외부 dependency, schema migration도
추가하지 않는다.

## 2. 현재 근거

| 근거 | 현재 사실 | 이번 경계에서의 문제 |
|---|---|---|
| `appointment-event/.../event/notification/NotificationOutboxRepository.kt` | `enqueue`, reminder suppression, ready query, claim, retry, terminal retention과 관련 DTO를 한 파일에서 제공한다. | event artifact가 JDBC persistence implementation과 worker lifecycle을 함께 노출한다. |
| `appointment-event/.../event/notification/NotificationOutboxEvents.kt` | `clinic_notification_outbox` table과 index/query contract를 정의한다. | notification readiness와 worker가 event package table을 직접 import한다. |
| `appointment-event/.../event/notification/NotificationDeliveryAttempts.kt` | delivery attempt audit table과 outcome enum을 정의한다. | claim lifecycle의 저장 모델이 event contract와 같은 package에 있다. |
| `appointment-event/.../event/waitlist/WaitlistNotificationOutboxAdapter.kt` | waitlist envelope/codec와 함께 row, sink, adapter, table, repository를 모두 정의한다. | waitlist event payload와 persistence row의 경계가 같은 파일·artifact에 섞여 있다. |
| `appointment-api/.../ServiceConfig.kt` | API가 `NotificationOutboxRepository`와 codec을 직접 bean으로 생성하고 writer/recovery store에 주입한다. | concrete repository가 API public constructor와 조립 코드에 전파된다. |
| `appointment-notification/.../NotificationAutoConfiguration.kt` | notification worker를 위해 event repository bean을 제공한다. | notification이 자기 persistence implementation을 소유하지 않는다. |
| `src/consumerFixture/notification/.../NotificationApiConsumerFixture.kt` | notification consumer가 event repository와 worker DTO를 public API anchor로 참조한다. | 기대 API에 persistence와 event contract가 섞여 있으며 event artifact 단독 fixture가 없다. |
| `docs/requirements/architecture.md` ADR-15 | #393 당시 notification physical move를 후속 범위로 기록했다. | #409에서 source path와 API migration을 갱신할 기준이 필요하다. |

GNO의 `bluetape4k-github`, `bluetape4k-docs`, `bluetape4k-wiki` 검색 결과에는 #409의
현재 설계를 결정할 최신 근거가 없었다. 따라서 live GitHub Issue #409, current
`develop`, ADR-15, #393 설계·계획, 실제 source/build 파일을 기준으로 한다.

## 3. 설계 원칙과 책임 경계

### 3.1 event contract

`appointment-event`는 다음 타입만 public event contract로 제공한다.

- `NotificationOutboxEnvelope`, `NotificationOutboxCodec`,
  `NotificationOutboxContractRegistry`
- `NotificationOutboxHasher`, key ring과 digest/value object
- `NotificationChannelType`, `NotificationEventType`, `NotificationSlot`,
  `NotificationFailureCode`, `NotificationSuppressionReasonCode`와 event payload
  parameter 타입
- `SendableNotificationDraft`, `LegacySuppressionDraft`
- 새 `NotificationOutboxWriter` port와 `NotificationOutboxWriteReceipt`
- waitlist의 `WaitlistNotificationOutboxEnvelope`,
  `WaitlistNotificationOutboxCodec`, deterministic key/contract exception

`NotificationOutboxWriter`는 caller-owned Exposed transaction에서 outbox write에 필요한
최소 동작만 표현한다.

```kotlin
interface NotificationOutboxWriter {
    fun enqueue(draft: SendableNotificationDraft): NotificationOutboxWriteReceipt

    fun suppressLegacy(draft: LegacySuppressionDraft): NotificationOutboxWriteReceipt

    fun containsIdempotency(digest: NotificationIdempotencyDigest): Boolean

    fun suppressOutstandingReminders(
        tenantGroupId: TenantGroupId,
        clinicId: ClinicId,
        appointmentId: AppointmentId,
        suppressionReason: NotificationSuppressionReasonCode,
    ): Int
}
```

`NotificationOutboxWriteReceipt`는 `id: Long` 하나만 제공하는 opaque write result다. 상태,
row kind, lease token, attempt number, Exposed `ResultRow`를 contract에 넣지 않는다.
concrete repository는 이 receipt의 subtype인 `NotificationOutboxRecord`를 반환할 수
있지만 event 호출자는 receipt 외의 persistence 속성에 의존하지 않는다.

DB current timestamp는 writer port에서 제거한다. API reminder recovery checkpoint는
이미 caller transaction 안에서 동작하므로 API 내부의 Exposed transaction helper로
현재 DB 시각을 읽는다. worker는 notification의 `NotificationOutboxWorkStore`가 제공하는
`currentDatabaseTime()`을 계속 사용한다. 이로써 event write contract가 DB clock
구현을 노출하지 않는다.

### 3.2 notification persistence contract와 implementation

`appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/persistence`
패키지를 새 persistence 경계로 사용한다. 새 module은 만들지 않는다.

`appointment-notification` artifact 자체는 worker의 public constructor와 API fixture가
현재 Exposed `Database`·claim DTO를 사용하므로 persistence API를 일부 노출한다. 이번
경계가 차단하는 대상은 `appointment-event` artifact에 notification persistence가
재유입되는 것과 `appointment-api`의 public writer가 concrete repository를 노출하는
것이다. notification consumer가 persistence API를 직접 사용해야 하는 기존 표면은
fixture와 README에 명시하고, 완전한 worker API 은닉은 별도 후속 설계로 남긴다.

다음 타입을 notification으로 이동한다.

- `JdbcNotificationOutboxRepository` (기존 `NotificationOutboxRepository`의 concrete
  implementation; 이름에 JDBC 소유권을 표시한다)
- `NotificationOutboxEvents`, `NotificationDeliveryAttempts`,
  `NotificationOutboxIndexes`, `NotificationOutboxQueryContracts`
- `NotificationOutboxRowKind`, `NotificationOutboxStatus`,
  `NotificationDeliveryAttemptOutcome`
- `NotificationCandidate`, `NotificationClinicKey`, `NotificationFairCursor`,
  `NotificationOutboxObservation`, `ClaimedNotification`, `NotificationOutboxRecord`,
  `CompleteNotificationCommand`, `RetryNotificationCommand`
- waitlist persistence인 `WaitlistNotificationOutboxStatus`, row/record/sink,
  `WaitlistNotificationOutboxAdapter`, `WaitlistNotificationOutboxEvents`,
  `WaitlistNotificationOutboxRepository`

waitlist envelope와 codec은 event package에 남기고, adapter가 event draft를 persistence
row로 변환한다. 따라서 event consumer는 canonical payload를 재사용할 수 있지만 table과
upsert 구현은 notification artifact가 소유한다.

`JdbcNotificationOutboxRepository`는 `NotificationOutboxWriter`를 구현하고
`NotificationOutboxWorkStore`가 사용하는 query/claim/complete/retry/retention API를
제공한다. 모든 public persistence method는 현재와 같이 자체 `transaction {}`을 열지
않고 caller가 연 transaction에서만 동작한다.

### 3.3 API 조립 경계

`appointment-api`의 `DefaultAppointmentNotificationWriter`와
`JdbcAppointmentReminderRecoveryStore` 생성자 타입을 `NotificationOutboxWriter`로
바꾼다. API `ServiceConfig`에서 codec/repository concrete bean을 직접 만들지 않는다.

`NotificationAutoConfiguration`은 다음을 제공한다.

1. `NotificationOutboxCodec` event contract bean
2. `JdbcNotificationOutboxRepository` concrete bean
3. 동일 bean을 `NotificationOutboxWriter`로 노출해 API가 port만 주입받도록 하는
   conditional wiring

`clinic.notification.enabled=false` 또는 notification writer가 없는 테스트 context에서는
API의 기존 fail-closed `UnavailableAppointmentNotificationWriter` 경로를 유지한다.
recovery store는 writer와 database가 모두 준비된 경우에만 생성되도록 조건을 명시한다.

API의 dev/test `SchemaInitConfig`는 moved table type을 notification persistence package에서
import한다. 이는 애플리케이션 내부 schema bootstrap이며 event public contract로
재전파하지 않는다. 운영 migration SQL V14/V19/V21/V22는 그대로 유지한다.

`appointment-notification/build.gradle.kts`의 Exposed migration scanner가
`notification.persistence` 하위 package를 재귀적으로 포함하는지 확인한다. scanner가
재귀를 보장하지 않으면 tables package 설정만 최소 수정하고, 생성된 SQL과 Flyway
source는 변경하지 않는다. 이 검사는 모듈 등록이나 migration source를 추가하는 작업이
아니다.

## 4. Gradle API와 source 경계 검증

### 4.1 event contract consumer fixture

root `build.gradle.kts`에 `appointment-event` API consumer fixture configuration과
`src/consumerFixture/event/kotlin/.../EventNotificationContractConsumerFixture.kt`를
추가한다. fixture는 `NotificationOutboxWriter`, `SendableNotificationDraft`, envelope,
codec, hasher를 compile-time으로 참조한다. 다음 persistence 타입은 fixture source에서
참조하지 않는다.

- `NotificationOutboxEvents`
- `NotificationDeliveryAttempts`
- `JdbcNotificationOutboxRepository`
- `ClaimedNotification`
- `CompleteNotificationCommand`

같은 Gradle 검증 task가 event jar entry를 검사해 위 persistence class가
`appointment-event` jar에 다시 들어오면 실패한다. source-path guard는 event package에
`org.jetbrains.exposed.v1.jdbc` import가 남은 notification persistence 파일이 없는지
확인한다. event가 소유한 scheduling table의 기존 Exposed 사용은 notification 이름공간
검사에서 제외한다.

### 4.2 notification consumer fixture

기존 `NotificationApiConsumerFixture`는 concrete repository가 아니라
`NotificationOutboxWriter`를 API writer contract anchor로 참조한다. notification
worker의 claim/query DTO는 notification persistence package를 통해서만 참조한다.
expected API scope에는 `project::appointment-event`가 notification event contract를
제공하는 사실을 남기되, event jar가 notification persistence를 재전파한다고 해석하지
않는다.

검증 명령은 다음과 같다.

```bash
./gradlew :appointment-event:compileKotlin :appointment-notification:compileKotlin
./gradlew --no-configuration-cache compileModuleConsumerFixtures
./gradlew --no-configuration-cache assertModuleConsumerFixtureApiVariants
```

fixture는 dependency scope와 compile surface를 증명한다. 실제 Flyway 적용, provider
호출, database 동시성은 module test와 migration test가 담당한다.

## 5. ABI·source 호환성과 migration

이번 변경은 의도적인 public source/ABI 경계 변경이다. 다음 migration을 문서와 KDoc에
명시한다.

| 이전 호출 | 새 호출 | 호환성 위험 |
|---|---|---|
| `io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository(codec, leaseDuration)` 생성 | `NotificationOutboxWriter` 주입 또는 `io.bluetape4k.clinic.appointment.notification.persistence.JdbcNotificationOutboxRepository`를 notification 내부에서 생성 | event artifact만 소비하던 구체 생성자는 compile/ABI가 깨진다. 외부 caller는 port 주입으로 이동한다. |
| `NotificationOutboxRecord`의 status/rowKind/parameters 직접 읽기 | event caller는 `NotificationOutboxWriteReceipt.id`만 사용하고, persistence 관찰은 notification module API로 이동 | persistence projection 의존 caller는 import와 테스트를 옮겨야 한다. |
| `event.waitlist.WaitlistNotificationOutboxAdapter` 직접 생성 | notification persistence package의 adapter를 생성하고 event package의 envelope/codec만 재사용 | waitlist adapter/table consumer의 source import가 깨진다. |
| API `ServiceConfig.notificationOutboxRepository()` bean | notification auto-configuration의 `NotificationOutboxWriter` bean | bean 이름을 직접 조회하는 context test는 type-based lookup으로 이동한다. |

호환성은 binary compatibility shim으로 우회하지 않는다. event가 notification에 역의존할
수 없으므로 event package에 concrete facade를 남기면 순환 의존 또는 가짜 구현이 된다.
대신 README와 migration note에 import·constructor 변경을 명시하고, 내부 API consumer
fixture와 module test를 새 contract 기준으로 고정한다. rollback은 코드 commit을 직전
commit으로 되돌리는 방식이며, schema rollback은 필요하지 않다.

## 6. 실패 모드와 방어선

| 실패 모드 | 방어선 | 검증 |
|---|---|---|
| API가 concrete repository를 public constructor로 다시 노출 | `AppointmentNotificationWriter` 구현 source guard와 event fixture의 persistence type 금지 | API/notification compile fixture, source boundary test |
| event jar에 Exposed notification table/class가 재유입 | moved package의 jar entry 금지 목록과 event consumer fixture | Gradle jar boundary task, `jar tf` read-back |
| notification auto-configuration이 writer를 중복 생성하거나 disabled context에서 API가 기동하지 않음 | `@ConditionalOnMissingBean(NotificationOutboxWriter::class)`, `ObjectProvider`, fail-closed writer 조건 | `NotificationAutoConfigurationTest`, API wiring test |
| caller transaction 밖에서 enqueue/claim이 실행됨 | repository/adapter의 `TransactionManager.current()` guard와 기존 transaction contract 보존 | repository/adapter negative test, 7-Tier stability |
| table 이동 중 migration schema와 local SchemaInitConfig가 어긋남 | table/column/index source는 그대로 두고 API bootstrap import만 이동 | Flyway migration test, SchemaInitConfig test, SQL diff |
| waitlist payload contract와 persistence row가 잘못 섞임 | envelope/codec는 event, row/table/repository는 notification persistence package로 분리 | codec round-trip test, adapter persistence test, package scan |
| short random name 충돌 또는 식별자 남용 | 새 H2/topic/test fixture suffix는 `Base58.randomString(8)`을 사용하고, security lease token/DB checkpoint UUID는 의미가 다르므로 이번 이슈에서는 기존 값을 유지한다 | Kotlin pattern source audit, deterministic fixture assertions |

## 7. 동작·성능·운영 계약

- `clinic_notification_outbox`, `clinic_notification_delivery_attempts`,
  `clinic_waitlist_notification_outbox`의 이름과 모든 column/index 이름은 바꾸지 않는다.
- V14/V19/V21/V22 SQL과 Flyway checksum을 변경하지 않는다.
- enqueue와 예약 상태 변경은 같은 caller `transaction {}`에 남긴다. worker는 claim
  transaction을 닫은 뒤 provider I/O를 수행한다.
- repository 이동은 SQL/query plan을 바꾸지 않는다. 추가 round trip, lock 범위,
  lease duration, retry delay를 도입하지 않는다.
- `NotificationSchemaReadiness`는 moved table/index를 notification persistence import로
  확인하고 기존 fail-closed 진단 code와 bounded probe 수를 유지한다.
- `NotificationOutboxMetrics`, leader election, Resilience4j, Kafka4, Redis 8.8 계약은
  API/type 위치만 조정하고 runtime semantics는 유지한다.
- 새 dependencies나 generated source를 추가하지 않는다. 기존 bluetape4k
  `bluetape4k-assertions`, `bluetape4k-junit5`/singleton launcher, Exposed helpers,
  `Base58`, leader/resilience4j/kafka4 재사용을 우선한다.
- 이번 이슈에서는 보안 lease token과 durable recovery checkpoint의 기존 UUID 생성은
  유지한다. `Base58.randomString(8)`은 새 H2/topic/test fixture suffix처럼 충돌 범위가
  제한된 테스트 식별자에만 적용한다.

## 8. 수용 기준과 DoD

### 8.1 수용 기준

- [ ] event jar에 notification table, concrete JDBC repository, claim/lifecycle DTO가
      없고, event consumer fixture가 순수 writer contract만 compile한다.
- [ ] notification persistence package가 table/repository/claim lifecycle을 단일
      책임으로 소유하고, notification worker/readiness/retention/relay가 이 package를
      사용한다.
- [ ] API public writer/recovery constructor가 event `NotificationOutboxWriter`만
      참조하며, disabled context의 fail-closed 동작이 유지된다.
- [ ] waitlist envelope/codec와 row/table/adapter/repository가 서로 다른 module/package
      경계에 있고 기존 canonical payload가 동일하다.
- [ ] `NotificationOutboxRepositoryTest`와
      `WaitlistNotificationOutboxAdapterTest`의 동작·negative·transaction 검증이
      notification module로 이동해 통과한다.
- [ ] `bluetape4k-assertions` assertion vocabulary와 `Base58.randomString(8)` test
      suffix 규칙을 새/이동 테스트에 적용한다. security lease token과 durable
      checkpoint 식별자는 의미를 확인한 뒤 기계적으로 치환하지 않는다.
- [ ] module compile/test, API consumer fixture, Flyway migration test, diff check가
      통과한다.
- [ ] ADR-15, event/notification README, KDoc와 Korean migration note가 source path와
      API contract를 일치시킨다.

### 8.2 DoD 증적

| 영역 | 증적 |
|---|---|
| source boundary | event contract source, notification persistence source path, import/jar guard 결과 |
| API/ABI | event·notification consumer fixture compile 및 expected scope report |
| behavior | moved repository/adapter lifecycle, codec, transaction negative test 결과 |
| schema | Flyway SQL SHA 불변, SchemaInitConfig와 readiness table/index read-back |
| ecosystem reuse | `bluetape4k.assertions`, `Base58`, Exposed transaction helper, singleton launcher source audit |
| 7-Tier | event·notification·api별 performance, stability, security/data boundary, ops, developer/API, user/caller, integration/tests 검토표; P0=0, P1=0 |
| docs | ADR-15와 README/KDoc/migration note의 source-to-claim traceability 및 SPW-01..05 |
| delivery | Lore commit, Korean PR body의 `## DoD Status`, exact-head CI/review/thread read-back |

## 9. 비목표와 후속 범위

- 새 `appointment-notification-contract` module을 만들지 않는다.
- Flyway SQL, table schema, index/query plan, retry/lease/retention semantics를 재설계하지
  않는다.
- `appointment-messaging`의 #393 변경이나 #392~#402 stacked train의 merge 순서를
  수정하지 않는다.
- `NotificationOutboxRepository` concrete 생성자를 event artifact에서 binary shim으로
  보존하지 않는다.
- notification worker public API 전체를 event dependency 없이 만드는 작업은 별도
  후속 설계로 남긴다. 이번 이슈의 최소 목표는 event contract가 persistence implementation을
  역으로 소유하지 않는 단방향 경계다.

## 10. 검토 게이트 기록

| 항목 | 결과 | 근거 |
|---|---|---|
| 사용자 설계 선택 | PASS | 2026-08-26 사용자 선택 `1`; 새 module 없이 event port + notification persistence move |
| SPW-01 audience/evidence | PASS | 이 문서 1·2절; Issue #409, develop SHA, ADR-15, #393 문서, source/build path 고정 |
| SPW-02 artifact contract | PASS | 문제, 경계, alternatives, ABI migration, 실패 모드, acceptance/DoD 포함 |
| SPW-03 Korean technical register | PASS | Korean-only repo 정책, API/identifier/command 보존, 자연스러움 checklist 적용 |
| SPW-04 traceability | PASS | 표·fixture·source path·migration/test 명령을 현재 source에 대조 |
| SPW-05 read-back | PASS | 작성 후 headings, tables, code fence, links, identifiers를 다시 읽고 누락 없음 확인 |
| KO-01..KO-07 | PASS | facts/identifier 보존, hollow claim 제거, 용어 일관성, 이 문서 terminology audit 결과 `findings=0` |

이 명세가 승인되면 다음 단계에서 `writing-plans`를 사용해 파일별 TDD 구현 계획을
작성한다. 계획 승인 전에는 production code를 수정하지 않는다.
