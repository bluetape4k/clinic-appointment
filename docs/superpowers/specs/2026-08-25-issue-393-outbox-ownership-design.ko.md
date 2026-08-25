# Issue #393 outbox 스키마·저장소 소유권 경계 설계

> 상태: 사용자 설계 승인 완료, 구현 전 명세
>
> historical train fence: `28e38915cc153fc01275a2c6acad632d99340b93`
>
> PR #410 live develop 재검증 기준: `8d4a26e2f2c96617b5697214d58183a0dee771aa`
>
> 대상 이슈: [#393](https://github.com/bluetape4k/clinic-appointment/issues/393)
>
> Epic: [#407](https://github.com/bluetape4k/clinic-appointment/issues/407)

> 이 명세는 승인 당시의 historical train fence
> `28e38915cc153fc01275a2c6acad632d99340b93`를 보존한다. 구현 PR을 재검증할 때는
> live `develop` `8d4a26e2f2c96617b5697214d58183a0dee771aa`를 별도 기준으로 기록하고,
> notification transitional exception·V19 readiness 범위를 이 명세와 함께 유지한다.
> Issue read-back으로 제목, 본문, parent 링크와 metadata를 확인한다.

## 1. 목표

`appointment-event`가 messaging과 notification의 영속성 구현을 우연히 전이
노출하는 문제를 줄이고, 각 capability의 outbox 책임을 현재 코드와 일치하는
문서·Gradle API 계약·compile fixture로 고정한다.

이번 변경은 outbox 행의 상태 전이와 migration 순서를 바꾸지 않는다. 다만 현재
readiness가 누락한 waitlist outbox table/index preflight를 notification capability의
책임으로 고정하고 회귀 테스트로 잠근다. 물리 table과 repository를 다른 모듈로 옮기는
작업은 별도 설계가 필요한 후속 범위로 남긴다.

## 2. 현재 근거

| 근거 | 현재 동작 | 경계 문제 |
|---|---|---|
| `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingOutboxEvents.kt` | `scheduling_outbox_events` Exposed table과 generic scheduling row를 정의한다. | appointment relay가 같은 table의 V22 열을 직접 읽는다. |
| `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxEvents.kt` | `clinic_notification_outbox` table과 notification 상태·index metadata를 정의한다. | notification worker가 event package의 table을 직접 사용한다. |
| `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.kt` | caller transaction 안에서 enqueue·claim·retry·terminal 처리를 제공한다. | API와 notification이 event package의 repository를 public contract로 받는다. |
| `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/waitlist/WaitlistNotificationOutboxAdapter.kt` | `clinic_waitlist_notification_outbox` table, caller-transaction adapter/repository와 waitlist row contract를 정의한다. | notification worker가 event package의 row contract를 사용하고 별도 claim·lease·retry lifecycle을 실행한다. |
| `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxStore.kt` | appointment row만 claim하고 lease/token fence로 publish 결과를 기록한다. | 구현에는 event table이 필요하지만 public signature에는 event 타입이 없다. |
| `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/WaitlistOfferNotificationStore.kt` | waitlist notification row의 claim·pre-send authorize·result CAS와 retry/terminal 처리를 수행한다. | V19 table 선언은 API migration에 있고 event가 row contract를 제공하는 transitional 경계다. |
| `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadiness.kt` | notification table, event log, Flyway version과 required index를 readiness에서 확인한다. 현재 V19 waitlist table/index는 preflight 목록에 없다. | 물리 schema 선언과 worker/readiness 책임이 서로 다른 모듈에 있고 waitlist readiness가 누락되어 있다. |
| `appointment-api/src/main/resources/db/migration` | 모든 Flyway migration을 API가 중앙 관리한다. V14·V21 notification, V19 waitlist delivery, V22 appointment messaging row를 포함한다. | 각 capability README가 migration 책임을 설명하지 않는다. |

현재 `appointment-messaging/build.gradle.kts`는
`api(project(":appointment-event"))`를 선언한다. messaging source는 event package의
`CancellationReasonCode` typealias와 `SchedulingOutboxEvents`를 사용하지만, public
reason-code·record 타입은 `appointment-core`에 있고 `AppointmentMessagingContext`는
messaging module이 정의한다. 따라서 선택한 구현은 public source import를 core의
`commitment.CancellationReasonCode`로 정렬하고
`api(project(":appointment-core"))`를 직접 선언한 뒤 event dependency를
`implementation`으로 낮춘다. 이를 통해 event table은 내부 implementation classpath에만
남고 messaging consumer API에는 전파되지 않는다. 반대로 notification의
`JdbcNotificationOutboxWorkStore`와 관련 worker contract는 현재
`NotificationOutboxRepository`, `ClaimedNotification`, `NotificationEventType` 등
event package 타입을 public surface에서 사용한다. 두 모듈의 `api` 변경을 동일하게
적용하면 notification consumer API가 깨진다.

따라서 이 명세의 “우연한 persistence implementation 전파 차단” acceptance는
messaging의 event table implementation leakage에 적용한다. notification의 event
`api`는 `NotificationOutboxRepository`, `ClaimedNotification`, `NotificationEventType`,
waitlist contract처럼 실제 public type을 제공하는 **명시적 transitional exception**이다.
이 예외는 README·ADR·notification fixture에서 이름을 붙여 검증하며, event contract와
persistence contract를 완전히 분리하는 후속 작업은 물리 이동 설계와 함께 별도 이슈로
등록한다. 이번 이슈에서 notification `api`를 억지로 `implementation`으로 낮추지 않는다.

## 3. 선택한 설계

### 3.1 계약 우선 경계 수선

이번 #393은 다음 네 가지를 한 묶음으로 적용한다.

1. **Gradle 노출 경계**
   - `appointment-messaging`의 event project dependency를 `api`에서
     `implementation`으로 낮춘다.
   - `appointment-messaging`에 `api(project(":appointment-core"))`를 명시하고,
     `AppointmentMessagingContracts.kt`, `AppointmentEventEnvelopeCodec.kt`,
     `AppointmentOutboxWriter.kt`의 public reason-code import를 core source로
     정렬한다. 이 변경은 typealias의 underlying JVM ABI와 public method signature를
     바꾸지 않지만, event package typealias를 직접 import하던 source consumer는 core
     import 또는 명시적인 event dependency로 마이그레이션해야 한다.
   - messaging consumer fixture의 승인 API 좌표에서 `project::appointment-event`를
     제거하고 `project::appointment-core`를 직접 검증한다. fixture는
     `AppointmentOutboxWriter`, `AppointmentMessagingContext`, `AppointmentRecord`,
     `TenantClinicScope`, `CancellationReasonCode`를 실제 public signature anchor로
     참조한다.
   - messaging consumer가 event contract를 직접 사용하는 경우에는
     `implementation(project(":appointment-event"))`를 명시하도록 README migration
     note를 추가한다. messaging artifact만 사용하는 호출자는 기존 설치 선언을
     유지한다.
   - 기존 `assertModuleConsumerFixtureApiVariants`와
     `compileModuleConsumerFixtures`가 event transitive API가 다시 생기면 실패하도록
     계약을 유지한다.
   - notification의 event `api`는 현재 public notification contract의 실제 타입
     의존성으로 남긴다. 이를 accidental exposure로 분류하지 않고, 명세와 fixture에
     transitional contract로 기록한다.

2. **소유권 matrix**
   - 물리 table 선언과 row capability를 같은 책임으로 뭉뚱그리지 않고, table
     declaration, repository/write, claim/relay/worker, readiness, migration을
     별도 축으로 기록한다.
   - `appointment-event`는 domain event contract, event log, generic scheduling
     event row와 현재 transitional notification·waitlist notification outbox
     contract/repository를 제공한다.
   - `appointment-messaging`는 appointment row의 writer·claim·relay·lease/fence·
     readiness를 제공한다.
   - `appointment-notification`은 notification row와 waitlist notification row의
     worker·claim adapter·retry·retention·provider lifecycle·readiness를 제공한다.
     `NotificationSchemaReadiness`는 두 notification row family의 table/index를 모두
     preflight하고, 기존 fail-closed 및 진단 계약은 유지한다. preflight 목록은
     고정된 table·index 집합으로 평가한다. 기존 dispatcher/worker call site는 그대로
     유지하므로 dispatch 전 1회와 처리 row별 기존 worker preflight가 실행되며, V19
     추가로 인한 추가 table probe 상한은 dispatch당 `1 + globalConcurrency`다.
   - `appointment-api`는 세 dialect의 Flyway migration과 애플리케이션 조립을
     제공한다.
   - 각 row family는 한 capability의 lifecycle만 기준으로 삼으며, 다른 모듈의
     worker가 해당 row를 임의로 claim하지 않는다.

3. **문서 정렬**
   - `appointment-event/README.md`에 실제 transitional persistence contract와
     event가 소유하지 않는 relay/worker 책임을 명시하고, `appointment-event/build.gradle.kts`
     기준의 `appointment-core`·Exposed API dependency를 기록한다.
   - `appointment-messaging/README.md`에 `implementation(project(":appointment-messaging"))`
     설치 계약, event artifact의 내부 사용, event contract 직접 사용 시 명시적
     dependency migration, API migration/readiness 책임을 명시한다.
   - `appointment-notification/README.md`의 의존성 목록을 실제 Gradle 선언과
     맞춘다. 내부 dependency에 `appointment-messaging`을 추가하고 외부 dependency에
     Kafka 4 client, Spring Kafka 4, Micrometer, Exposed JDBC, Resilience4j, Lettuce와
     `bluetape4k-leader`를 기록한다.
   - `docs/requirements/architecture.md`에 ADR-15로 소유권 matrix를 남겨 다음 모듈
     PR이 같은 책임을 다시 추론하지 않게 한다.

4. **동작 보존**
   - table 이름, column, Flyway version, transaction 경계, lease/fencing, retry 상태
     전이와 public notification 타입은 변경하지 않는다. 기존 durable notification
     readiness 기준은 유지하고, 누락되어 있던 V19 waitlist table/index preflight만
     추가한다.
   - 새 외부 dependency, 새 module/artifact, schema migration, generated artifact를
     추가하지 않는다. `appointment-core`는 이미 event를 통해 전이되던 기존 내부
     capability를 messaging의 public API에 직접 선언하는 경계 정렬이다.

### 3.2 책임 matrix

| Capability / artifact | table declaration | write/repository | claim·relay·worker | readiness | migration source |
|---|---|---|---|---|---|
| generic scheduling event/inbox/quarantine | `appointment-event` | `appointment-event` | event handler 또는 해당 application caller | 고정된 module readiness 없음; application preflight가 필요한 table을 확인 | `appointment-api` Flyway |
| appointment messaging row (`scheduling_outbox_events` V22 subset) | 현재 `appointment-event` transitional declaration | `appointment-messaging` writer와 기존 event writer의 row family별 contract | `appointment-messaging` | `appointment-messaging` | `appointment-api` Flyway V22 |
| durable notification row (`clinic_notification_outbox`) | 현재 `appointment-event` transitional declaration | `appointment-event` caller-transaction repository | `appointment-notification` worker/direct delivery/retention | `appointment-notification` | `appointment-api` Flyway V14·V21 |
| waitlist notification row (`clinic_waitlist_notification_outbox`) | 현재 `appointment-event` transitional declaration | `appointment-event` caller-transaction adapter/repository | `appointment-notification` waitlist worker의 claim·authorize·result CAS·retry/terminal lifecycle | `appointment-notification` (`NotificationSchemaReadiness`가 table·idempotency·ready·lease index preflight) | `appointment-api` Flyway V19 |
| event log | `appointment-event` | `appointment-event` logger | 해당 consumer가 조회만 수행 | consumer별 필요한 column preflight | `appointment-api` Flyway |

이 matrix는 현재의 물리 선언과 capability 실행 책임을 숨기지 않는다. 특히
`appointment-event`의 table/repository가 transitional owner라는 사실을 명시하고,
향후 물리 이동이 필요하면 새 dependency 방향과 migration 호환성을 별도 승인받도록
한다.

## 4. 대안과 결정

### 4.1 채택: 계약 우선 경계 수선

현재 public API를 깨지 않으면서 accidental transitive dependency를 제거하고,
문서와 compile fixture로 후속 physical move의 전제 조건을 남긴다. #402 Kafka4
adapter와 #399/#400 readiness 작업이 기존 table 계약을 계속 사용할 수 있어 stacked
train의 앞단에서 안전하게 수행할 수 있다.

### 4.2 기각: 이번 이슈에서 물리 소유권까지 이동

`SchedulingOutboxEvents`, notification contracts/repository, API Flyway 조립과
모든 import를 capability module로 옮기면 경계는 선명해진다. 그러나 API의
`DatabaseConfig`·서비스 wiring과 후속 messaging/notification 테스트를 한 번에
바꾸게 되고, migration rollback과 stacked PR base가 넓어진다. 현재 이슈의 단기
완료 조건인 문서·compile fixture·`api` 노출 수선보다 범위가 크므로 기각한다.

### 4.3 기각: 새 공용 outbox schema/contract module 신설

새 artifact는 event를 순수 contract module로 만들 수 있지만 settings 등록, CI/Kover,
BOM/publish surface, dependency locking과 소비자 migration을 추가한다. 현재 코드가
이미 안정적인 event notification contract를 제공하므로, 새 module의 필요성과
호환성 증거가 없는 상태에서는 추가하지 않는다.

## 5. 비목표

- `scheduling_outbox_events`, `clinic_notification_outbox` 또는
  `clinic_waitlist_notification_outbox`의 물리 table 이동
- `NotificationOutboxRepository`·`WaitlistNotificationOutboxRepository`의 package 이동이나
  public signature 변경
- Flyway V14/V19/V21/V22 SQL 변경 또는 새 migration 추가
- Kafka, Redis, Resilience4j, Exposed dependency 버전 변경
- 새로운 공용 abstraction·dependency·module 추가
- notification worker의 retry/lease/retention semantics 변경 또는 #400 범위의 readiness
  diagnostic 원인 보존 변경

## 6. 구현·검증 계약

### 6.1 변경 파일 후보

- `appointment-messaging/build.gradle.kts`
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingContracts.kt`
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentEventEnvelopeCodec.kt`
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxWriter.kt`
- `build.gradle.kts`의 messaging API consumer fixture 승인 범위
- `src/consumerFixture/messaging/kotlin/io/bluetape4k/clinic/appointment/consumer/MessagingApiConsumerFixture.kt`
- `appointment-event/README.md`, `appointment-event/README.ko.md`
- `appointment-messaging/README.md`, `appointment-messaging/README.ko.md`
- `appointment-notification/README.md`, `appointment-notification/README.ko.md`
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadiness.kt`
- `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadinessTest.kt`
- `docs/requirements/architecture.md`의 ADR-15 outbox ownership 항목

README locale가 병렬로 유지되는 파일은 실제 repository-local Korean contract에 따라
동일한 사실을 함께 확인한다. 새 public code API가 없으므로 KDoc 추가는 필수가 아니며,
변경한 reader-facing 문서는 한국어 자연스러움 checklist를 통과해야 한다.
현재 baseline audit의 `예약서비스` 띄어쓰기 2건과 `snapshot` loanword 7건도 이번
문서 정렬에서 함께 수정해 audit의 기존 findings를 0으로 만든다.

### 6.1.1 완료조건-검증 traceability

compile fixture는 public API 좌표와 transitive leakage만 증명한다. 물리 table,
repository, migration, readiness, worker lifecycle의 소유권은 ADR-15의 matrix와
현재 source path를 함께 검토해 증명하며, fixture가 이를 자동으로 증명한다고
주장하지 않는다.

| 완료조건 | 구현 근거 | 검증 산출물 |
|---|---|---|
| messaging event persistence가 consumer API로 전파되지 않음 | messaging `implementation(event)` + 직접 `api(core)` + fixture anchor | `assertModuleConsumerFixtureApiVariants`, `compileModuleConsumerFixtures` 결과 |
| notification event API가 의도된 transitional exception임 | public event type import, README/ADR 설명, notification fixture | notification expected API scope와 fixture compile 결과 |
| 각 outbox의 table/repository/lifecycle 소유자 고정 | ADR-15 matrix와 3개 outbox source path | ADR/source-path 7-Tier review 기록 |
| V19 waitlist readiness 고정 | `NotificationSchemaReadiness` table + unique/ready/lease index preflight | missing table, 각 index 누락, 양쪽 row family 충족 UP 테스트 |
| readiness probe 비용이 bounded임 | 기존 dispatcher/worker call site와 `globalConcurrency` cap 유지 | `NotificationOutboxDispatcherTest` 동시성 상한 + source-path query budget review (`1 + globalConcurrency`) |
| migration 계약 보존 | Flyway SQL 비변경 | `FlywayMigrationTest` 현재 SHA 결과와 SQL diff 없음 |
| 문서 dependency/자연스러움 정렬 | 6개 README + ADR-15 + 기존 terminology findings 수정 | 전체 Korean terminology audit `findings=0` |

### 6.2 검증 명령

다음 명령은 구현 후 현재 branch SHA에서 다시 실행한다.

```bash
./gradlew :appointment-messaging:compileKotlin
./gradlew :appointment-event:test :appointment-messaging:test :appointment-notification:test
./gradlew :appointment-notification:test --tests '*NotificationSchemaReadinessTest*'
./gradlew --no-configuration-cache assertModuleConsumerFixtureApiVariants compileModuleConsumerFixtures
./gradlew :appointment-api:test --tests '*FlywayMigrationTest*'
git diff --check
node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  appointment-event/README.md appointment-event/README.ko.md \
  appointment-messaging/README.md appointment-messaging/README.ko.md \
  appointment-notification/README.md appointment-notification/README.ko.md \
  docs/requirements/architecture.md
```

모듈 테스트는 기존 bluetape4k singleton launcher와
`io.bluetape4k.assertions` 사용 패턴을 유지한다. `@Testcontainers`를 새로 도입하지
않는다. migration/readiness 회귀는 기존 API harness와 `SchemaUtils`/Flyway 계약을
재사용한다.

`NotificationSchemaReadinessTest`에는 다음 V19 시나리오를 명시적으로 고정한다.

- `clinic_waitlist_notification_outbox` table이 없으면 DOWN이고 reason에 table 이름이
  포함된다.
- `uk_waitlist_notification_outbox_idempotency`,
  `idx_waitlist_notification_outbox_ready`,
  `idx_waitlist_notification_outbox_lease`를 각각 제거했을 때 DOWN이다.
- notification 및 waitlist outbox table과 모든 필수 index가 있으면 active key와 함께
  UP이다.
- 기존 DOWN fixture에도 waitlist table을 생성해 다른 누락 조건이 의도한 reason을
  가리지 않도록 한다.

### 6.3 7-Tier 수용 기준

| Tier | 수용 기준 |
|---|---|
| 성능 | production claim/relay query와 allocation을 변경하지 않고, transitive compile graph만 줄인다. readiness check 1회당 고정된 table probe 1개와 waitlist index metadata 항목 3개만 추가하며, 기존 `globalConcurrency` 상한으로 dispatch당 추가 table probe를 `1 + globalConcurrency` 이하로 제한한다. 새 readiness 호출 지점은 만들지 않는다. |
| 안정성 | transaction, lease, retry, migration semantics와 기존 notification readiness fail-closed 계약을 유지하고, V19 waitlist table 및 `uk_waitlist_notification_outbox_idempotency`, `idx_waitlist_notification_outbox_ready`, `idx_waitlist_notification_outbox_lease` 각각의 누락/충족 회귀를 포함한 세 모듈 테스트가 통과한다. |
| 보안/데이터 경계 | event persistence가 messaging consumer의 우연한 API classpath로 전파되지 않는다. |
| 운영 | table·row family·migration·readiness·lifecycle 책임과 rollback 경로를 ADR에서 찾을 수 있다. |
| 개발자/API | `apiElements` fixture가 messaging event leakage를 차단하고, messaging의 core 직접 API·public writer/reason-code contract와 notification의 명시적 transitional API를 기록한다. |
| 사용자/호출자 | 세 README의 설치 dependency와 실제 Gradle 선언이 일치한다. |
| 통합/테스트 | event·messaging·notification·API migration 및 consumer fixture의 현재 SHA 증거가 있다. |

P0/P1은 구현과 PR 생성을 차단한다. P2/P3는 수정하거나 후속 Issue와 근거를
명시한다. 각 module slice는 별도의 7-Tier review 결과를 남기고, 전체 PR에서
`P0=0, P1=0` 수렴을 확인한다.

## 7. 롤백·호환성

변경은 Gradle API scope, 문서, fixture 기대값과 notification의 V19 readiness
preflight에 한정된다. build/test 또는 consumer fixture가 실패하면 dependency
scope와 fixture 기대값을 함께 되돌리고, readiness 회귀가 실패하면 V19 table/index
검사와 그 테스트 fixture를 함께 되돌린다. 문서만 되돌려서는 안 된다. table·migration
SQL·runtime state는 건드리지 않으므로 배포된 schema rollback은 필요하지 않다.

물리 ownership move는 이 명세의 비목표이므로, 이후 별도 설계에서 다음을 다시
검토해야 한다.

- event contract와 persistence contract의 package/module 분리
- API Flyway migration의 capability별 source ownership
- public notification types의 호환 facade 또는 major-version migration
- 기존 `scheduling_outbox_events` row family의 rolling deployment 순서

## 8. 완료 조건

- [ ] 책임 matrix가 README/ADR에 반영되고 현재 source path와 일치한다.
- [ ] messaging event dependency가 `api`로 노출되지 않으며 consumer fixture가 이를
  회귀 차단하고, messaging이 public core 타입을 직접 API dependency로 선언한다.
- [ ] notification의 event dependency는 실제 public type 사용에 따른 명시적
  transitional contract로 문서화된다.
- [ ] notification README dependency 목록이 Gradle 선언과 일치한다.
- [ ] table/column/migration/relay/worker 동작 변경이 없고, readiness는 기존
  fail-closed 계약을 유지한 채 V19 waitlist table·idempotency/ready/lease index
  누락을 차단한다.
- [ ] 명시한 모듈·fixture·migration 검증과 Korean terminology audit가 현재 SHA에서
  통과한다.
- [ ] module별 7-Tier review에서 P0=0, P1=0이고 P2/P3 처리가 기록된다.
