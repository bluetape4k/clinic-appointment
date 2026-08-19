# Issue #309 Composite repository·transaction 경계 설계

## 문서 상태

- 상태: 승인된 설계안 2를 구현 가능한 결정으로 고정
- 이슈: [#309](https://github.com/bluetape4k/clinic-appointment/issues/309)
- 작업 브랜치: `refactor/issue-309-composite-repository-transaction`
- 기준 브랜치: `develop`
- 작성일: 2026-08-19
- 결정권자: 저장소 소유자 승인(`2번 승인`)

## 1. 문제와 현재 근거

Issue #309의 목적은 저장소를 하나로 합치는 것이 아니다. 현재 도메인 경계를
유지하면서 반복적인 Exposed DSL CRUD는 `bluetape4k-exposed`의 JDBC repository로
재사용하고, application service가 transaction의 소유권을 분명히 갖도록 정비하는
것이다.

현재 소스와 의존성을 다시 조사한 결과는 다음과 같다.

| 관찰 | 현재 근거 | 설계 판단 |
| --- | --- | --- |
| `LongJdbcRepository` 사용 | `AppointmentRepository`, `ClinicRepository`, `DoctorRepository`, `EquipmentRepository`, `HolidayRepository`, `PatientAccountRepository`, `PatientLoginIdentityRepository`, `RescheduleCandidateRepository`, `TenantGroupRepository`, `TreatmentTypeRepository` | 같은 `IdTable` + record 패턴은 이 API를 우선 사용한다. |
| `ExposedJdbcRepository` 적합성 | 로컬 `bluetape4k-exposed` API는 Spring Data DAO `Entity<ID>`용이며 현재 record/Table DSL 저장소와 타입 계약이 다르다. | DAO로 바꾸지 않는 한 해당 API를 기계적으로 도입하지 않는다. |
| 단순 DSL 저장소 | `AppointmentIdempotencyRepository`, `AppointmentStateHistoryRepository`, `TreatmentSpaceRepository`는 한 테이블 중심의 insert/read와 제한된 특수 검증이 주 책임이다. | `LongJdbcRepository` 공통 계약을 상속하고 기존 공개 메서드는 유지한다. |
| append-only 특수 DSL | `AppointmentOperationalExceptionRepository`는 ID를 외부 record로 노출하지 않는 append·상태 전이 포트다. | 공개 명령 API와 caller transaction을 유지하고 `LongJdbcRepository`를 강제하지 않는다. |
| Composite/custom DSL 저장소 | `AppointmentItemRepository`, `AppointmentRepository`, `ResourceAllocationRepository`, `SchedulingPolicyJobRepository`, `WaitlistRepository`, `WaitlistDeliveryRepository` 등은 scope 검증, batch, lock, join, claim 규칙을 함께 가진다. | Composite 책임과 custom DSL을 보존하고 호출자 transaction을 사용한다. |
| Database 직접 주입 | API·messaging·notification에 bootstrap, readiness, worker, split transaction, multi-database 경계가 섞여 있다. | 일괄 제거하지 않고 각 사용처를 분류하여 허용 사유를 문서화한다. |
| Spring transaction 선언 | production 코드의 실제 `@Transactional` 선언은 아직 없다. | 단일 business atomic write 경계부터 proxy를 통해 선언하고 rollback/propagation을 테스트한다. |

이 문서의 사실 목록은 작업 브랜치에서 `rg`와 소스/Gradle 의존성 확인으로 얻었다.
Issue 본문의 이전 수치가 현재 소스와 다를 때는 현재 소스를 기준으로 한다.

## 2. 승인된 결정: 경계 우선 전환

### 2.1 유지할 것

1. Composite repository와 domain service의 공개 메서드, 테이블 이름, FK/unique
   제약, tenant/clinic/patient scope 검증을 유지한다.
2. `AppointmentItemRepository`의 proposal·plan revision 일괄 검증과 batch insert,
   outbox/claim/lock 저장소의 `FOR UPDATE`, `SKIP LOCKED`, query-plan 전용 DSL을
   삭제하거나 공통 CRUD로 숨기지 않는다.
3. 저장소는 transaction을 새로 열지 않는다. 이미 시작된 caller transaction에서
   `transaction {}` 없는 Exposed DSL을 실행하는 현재 규칙을 유지한다.
4. Exposed `Database` bootstrap handle과 Spring `DataSource`의 lifecycle은
   configuration boundary에서만 관리한다.

### 2.2 전환할 것

1. 반복적인 단일 테이블 CRUD인 세 저장소에
   `io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository`를 적용한다.
   공통 기본 메서드를 노출하되, 현재 호출자가 사용하는 `save`, scope 조회,
   만료 삭제, 상태 전이 메서드는 이름과 결과를 보존한다.
2. 실제 한 business write를 소유하는 application service/command/worker의
   public entry point에는 Spring `@Transactional`을 선언한다. Spring proxy가
   호출하는 경계에서만 동작하게 하고, 내부 self-invocation을 계약으로 삼지 않는다.
3. 기존에 의도적으로 여러 짧은 transaction으로 나눈 idempotency reservation,
   외부 password 검증, replay reconciliation, notification/worker 흐름은 한
   거대 transaction으로 합치지 않는다. 해당 메서드는 `SPLIT-TRANSACTION`으로
   분류하고 각 단계의 durable commit을 테스트한다.
4. custom DSL repository에는 현재 transaction이 없을 때 조기에 실패하는
   precondition 또는 테스트 가능한 adapter 경계를 둔다. 저장소가 임의의
   `Database`를 받아 독립 transaction을 여는 방식은 새로 추가하지 않는다.

### 2.3 채택하지 않은 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| 모든 repository를 `ExposedJdbcRepository`로 기계 변환 | 현재 API는 DAO `Entity` 중심이고 record/Table DSL과 호환되지 않는다. Composite의 scope·lock·batch 의미를 잃는다. |
| 모든 service를 단일 `@Transactional`로 감싸기 | password encoder, notification, Kafka/외부 IO, retry 및 idempotency durable 단계가 transaction 안으로 들어가 latency·재시도 의미가 바뀐다. |
| repository를 하나의 거대한 facade로 합치기 | 도메인 소유권과 caller-owned transaction 계약을 훼손하며 Issue #309의 명시적 제외 범위다. |
| `Database` 주입을 한 번에 제거 | bootstrap/readiness/multi-database와 의도적인 split transaction을 구분하지 못해 회귀 위험이 크다. |

## 3. 책임 분류표

아래 분류는 구현과 후속 이슈의 기준표다. 같은 클래스 안에 두 경계가 있으면
메서드 단위로 가장 좁은 분류를 적용한다.

| 분류 | 적용 대상/예 | 허용 계약 | 금지 계약 |
| --- | --- | --- | --- |
| `LONG-JDBC` | 단일 `LongIdTable` record CRUD. 이번 수직 전환은 `AppointmentIdempotencyRepository`, `AppointmentStateHistoryRepository`, `TreatmentSpaceRepository` | `LongJdbcRepository<E>` + 명시적 mapper/특수 query, caller transaction | 저장소 내부 `transaction(database)` |
| `COMPOSITE-DSL` | `AppointmentItemRepository`, appointment/plan/resource/waitlist/claim/outbox composite 저장소 | 현재 transaction의 DSL, join/lock/batch/scope 불변식, 필요한 경우 `currentTransaction` 확인 | 공통 CRUD로 custom query 대체, 임의 Database 선택 |
| `APPEND-DSL` | `AppointmentOperationalExceptionRepository`처럼 생성 ID를 별도 record로 보존하지 않는 append/status port | 공개 명령 API, caller transaction, 명시적 상태 전이 | ID 없는 API에 억지로 generic CRUD를 노출 |
| `SPRING-TRANSACTION` | 단일 business command/write를 소유하는 Spring service/worker entry point | `@Transactional`, 기본 `REQUIRED`, write rollback, 명시적 read-only query | self-invocation을 proxy 경계로 간주, 외부 IO/긴 계산 포함 |
| `SPLIT-TRANSACTION` | `WaitlistApplicationService.confirmOffer`, `PatientAuthenticationService.login`, replay/reconciliation, notification outbox worker | reservation/business/finalization 또는 read/password/issue를 짧은 단계로 분리하고 각 commit을 검증 | 단계를 합쳐 replay/재시도 계약 변경 |
| `BOOTSTRAP-BOUNDARY` | `ExposedDatabaseFactory`, lifecycle, configuration bean, schema/readiness/backfill runner | Spring `DataSource`에서 `Database` handle 생성·해제, startup/readiness 전용 접근 | 일반 request service가 bootstrap handle을 선택해 transaction 생성 |

`AppointmentItemRepository`는 단일 테이블처럼 보이는 메서드라도 proposal/plan
revision 검증과 batch insert를 함께 수행하므로 `COMPOSITE-DSL`로 남긴다.
`TreatmentSpaceRepository`는 `LONG-JDBC` 공통 계약을 제공하되 tenant·clinic ownership
검증을 수행하는 명시적 `save`와 capability 메서드를 계속 사용한다. generic 메서드는
caller-owned transaction 안의 내부 공통 계약으로만 취급하고, 외부 호출은 scope 전용
메서드를 사용해야 한다.

### 현재 Database inventory 기준

이번 수직 전환에서는 `Database` 직접 사용을 일괄 제거하지 않았다. 영향 모듈의
main source를 다시 검색한 결과 `transaction(database)` 55건이 21개 파일에 있고,
`private val database: Database` 필드는 23개 파일에 있다. 이는 다음 경계가 섞여
있기 때문이다.

| 분류 | 대표 사용처 | 이번 범위의 판단 | 후속 후보 |
| --- | --- | --- | --- |
| `BOOTSTRAP-BOUNDARY` | `ExposedDatabaseLifecycle`, `NotificationSchemaReadiness`, `PatientCancellationHistoryBackfillRunner` | startup/readiness/backfill lifecycle에서 handle을 소유하므로 유지 | lifecycle 전용 adapter 검토 |
| `SPLIT-TRANSACTION` | `PatientAuthenticationService`, `WaitlistApplicationService`, `AppointmentReplayService` | password·외부 IO·reservation/replay 단계의 짧은 commit을 보존 | 단계별 transaction contract 보강 |
| `WORKER/CLAIM` | `AppointmentConsumerInboxStore`, `WaitlistOfferNotificationStore`, `NotificationOutboxWorkStore` | lease·lock·재시도 SQL과 같은 transaction에 있어 유지 | claim helper 재사용성 검토 |
| `PROJECTION/RECOVERY` | `AppointmentStatsProjectionConsumer`, `JdbcAppointmentReminderRecoveryStore` | projection/recovery 저장 경계의 명시적 database를 유지 | 공통 runner boundary 설계 |

이 inventory는 변경하지 않은 사용처를 후속 후보로 분류하기 위한 현재 소스 기준표다.
이번 issue에서는 이 경계를 Spring transaction 하나로 합치거나 Database 선택 책임을
repository로 이동하지 않는다.

## 4. transaction 계약

### 4.1 Spring 경계

- non-suspend application service의 public read/create entry point가 Spring proxy
  transaction 소유자다.
- 기본 propagation은 `REQUIRED`로 두어 상위 transaction이 있으면 참여하고 없으면
  Spring transaction manager가 시작한다.
- 검증 가능한 business 예외와 unchecked 예외는 write를 rollback한다. 외부 호출
  결과를 transaction에 저장해야 하면 먼저 durable intent를 기록하고 외부 동작은
  transaction 밖에서 수행한다.
- 순수 조회 entry point는 `readOnly = true`를 사용하며, read-only 메서드가 write를
  호출하는 구조를 허용하지 않는다.
- `suspend` status/cancel은 `Dispatchers.IO` 안의 명시적 Exposed transaction이
  소유자다. imperative Spring interceptor가 coroutine 완료를 기다리지 않는 경로와
  중첩하지 않으며, 별도 suspend proxy fixture를 추가하기 전에는 `@Transactional`을
  선언하지 않는다.
- `REQUIRES_NEW`는 idempotency finalization·audit처럼 의도적으로 상위 실패와
  분리해야 하는 단계에서만 사용하고, 그 이유와 재시도 결과를 KDoc/테스트에
  남긴다. 임의의 nested transaction 대체 수단으로 사용하지 않는다.

### 4.2 Exposed DSL 경계

- repository와 domain service는 transaction을 열지 않는다. caller가 연
  `org.jetbrains.exposed.v1.jdbc.transactions.transaction {}`의 현재 context를
  사용한다.
- custom DSL 호출 전 현재 JDBC transaction이 필요하면 명시적인
  `requireCurrentTransaction` 계열 guard를 호출하여 잘못된 사용을 즉시 실패시킨다.
- 서로 다른 `Database` handle을 섞지 않는다. Spring이 관리하는 DataSource와
  Exposed transaction manager가 같은 connection을 사용한다는 것을 wiring test로
  검증한다.

### 4.3 Composite와 split 흐름

`WaitlistApplicationService.confirmOffer`는 다음 순서를 유지한다.

```text
reservation commit -> claim/replacement/hold business commit
                   -> success/failure finalization commit
```

중간 business transaction이 실패하면 reservation finalization은 실패 코드를
기록하고 원래 예외를 다시 던진다. 성공 commit 뒤 finalization 실패를 호출자 재시도의
성공으로 간주하지 않으며, durable 상태를 읽어 replay한다. 이 순서를 바꾸는 경우
별도 설계 승인과 중복 side-effect 테스트가 필요하다.

## 5. API·스키마 호환성

- repository class의 Spring bean 이름과 생성자 호환성을 유지한다.
- 기존 `save`의 null ID 입력/생성 ID 반환, 상태 이력의 정렬, 운영 예외의 상태
  전이, idempotency scope와 만료 기준을 유지한다.
- `LongJdbcRepository`의 `table`, `extractId`, `ResultRow.toEntity`는 공통 조회
  계약만 제공한다. 특수한 insert/update/query는 기존 명시적 메서드로 남긴다.
- SQL migration의 `scheduling_*` 테이블과 인덱스 이름을 변경하지 않는다.
- `ExposedJdbcRepository`(Spring Data DAO)를 도입하려면 먼저 DAO Entity 전환을
  별도 issue로 설계한다. 이번 변경에서 의존성의 이름만으로 API를 선택하지 않는다.

## 6. 실패 모드와 방어책

| 실패 모드 | 방어책/검증 |
| --- | --- |
| service 예외 후 일부 row만 남음 | Spring proxy rollback 테스트와 row count 확인 |
| nested invocation이 새 transaction을 기대함 | `REQUIRED` 참여 테스트, self-invocation 금지 KDoc, proxy 호출 테스트 |
| reservation 후 business 실패 재시도 | failure finalization과 stable replay 테스트 |
| 서로 다른 Exposed `Database`/connection 혼용 | 동일 DataSource/connection identity wiring 테스트 |
| PostgreSQL lock/`SKIP LOCKED` 의미 변경 | claim/outbox custom DSL은 그대로 두고 PostgreSQL 순차 integration 테스트 |
| H2에서 PostgreSQL 전용 DSL이 우연히 통과 | H2는 schema/wiring/기본 CRUD boundary만 검증하고 PostgreSQL lock 테스트는 별도 실행 |
| container fixture가 병렬로 schema를 지움 | bluetape4k singleton launcher와 테스트 클래스 실행 직렬화, `@Testcontainers` 금지 |
| 외부 IO·긴 계산이 transaction 안으로 이동 | transaction boundary 테스트와 정적 검색(`transaction` 주변 외부 port 호출) |

## 7. 테스트 매트릭스

### RED/GREEN 수직 전환

1. 세 `LONG-JDBC` 저장소의 기존 API 계약 테스트를 먼저 실패 가능한 형태로
   고정한다. save/find/정렬·상태 전이/만료 삭제를 포함한다.
2. service proxy fixture에서 commit, rollback, propagation, read-only를 검증한다.
3. `COMPOSITE-DSL`에는 transaction 없이 호출했을 때의 fail-fast guard와 caller
   transaction 안에서의 정상 동작을 검증한다.
4. split 흐름에는 reservation·business·finalization 각 commit과 replay/중복
   side-effect 방지를 검증한다.

### 데이터베이스

- H2: 기존 test-only schema/fixture와 Spring wiring/기본 CRUD를 계속 통과시킨다.
- PostgreSQL: bluetape4k singleton launcher로 claim/lock, rollback, propagation,
  composite/outbox query를 순차 실행한다.
- 모든 테이블 준비는 `SchemaUtils.createMissingTablesAndColumns(Table)`와
  `@BeforeEach`의 `Table.deleteAll()` 규칙을 따른다.
- 실행 예시는 다음과 같다.

```bash
./gradlew :appointment-core:test --tests '*AppointmentIdempotencyRepositoryTest'
./gradlew :appointment-core:test --tests '*AppointmentStateHistoryRepositoryTest'
./gradlew :appointment-api:test --tests '*Transaction*' --tests '*WaitlistApplicationServiceTest'
./gradlew :appointment-core:test :appointment-api:test --no-daemon
```

실제 PostgreSQL suite는 저장소가 제공하는 singleton launcher와 macOS의 관리된
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`를 사용하며, H2와 병렬로 실행하지 않는다.

## 8. 구현 범위와 후속 범위

이번 구현은 경계 우선 수직 슬라이스로 다음을 포함한다.

- 세 단순 repository의 `LongJdbcRepository` 공통 계약 적용과 API 회귀 테스트
- application transaction 경계의 최소 proxy fixture, rollback/propagation/read-only
  검증
- composite/current transaction guard 및 split 흐름의 기존 순서 고정
- Database 직접 주입 inventory와 허용 사유를 KDoc/README 또는 lesson에 기록
- 영향 모듈의 compile/test/static/diff 검증

다음 항목은 이 변경에서 임의로 확장하지 않는다.

- 모든 40여 repository의 일괄 변환
- DAO Entity 기반 Spring Data `ExposedJdbcRepository` 전환
- Kafka, notification HTTP, solver 계산, 비동기 worker의 외부 실행 모델 변경
- schema/migration/table name 변경
- 테스트에서 `@Testcontainers` 도입

inventory에서 `BOOTSTRAP-BOUNDARY` 또는 `SPLIT-TRANSACTION`으로 분류된 사용처는
동일 기준으로 후속 issue를 만들 수 있도록 목록과 이유를 남긴다. 현재 issue에서
미전환이라는 사실은 경계를 훼손하지 않기 위한 의도적인 결과다.

## 9. DoD

- [ ] 설계 분류표와 구현 파일 목록이 실제 소스와 일치한다.
- [ ] 세 `LONG-JDBC` repository가 기존 공개 API와 record mapping을 유지한다.
- [ ] Composite/custom DSL은 caller-owned current transaction을 사용하며 guard가
      검증된다.
- [ ] Spring transaction proxy의 commit/rollback/propagation/read-only 계약이
      테스트로 고정된다.
- [ ] split transaction의 durable 순서와 replay 의미가 회귀 테스트로 고정된다.
- [ ] H2 wiring/basic CRUD와 PostgreSQL sequential integration이 통과한다.
- [ ] 의존성·정적 검색·`git diff --check`와 영향을 받은 Gradle test가 통과한다.
- [ ] Korean 문서·KDoc과 Issue/PR metadata가 저장소 정책을 따른다.
