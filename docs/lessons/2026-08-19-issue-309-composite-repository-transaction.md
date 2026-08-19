# Issue #309 Composite repository와 transaction 경계 교훈

## 배경

저장소를 하나의 facade로 합치지 않으면서 반복적인 Exposed JDBC CRUD를
`bluetape4k-exposed`로 재사용하고, 호출자가 transaction을 소유한다는 규칙을 코드와
테스트로 고정해야 했다. 대상은 `AppointmentIdempotencyRepository`,
`AppointmentStateHistoryRepository`, `TreatmentSpaceRepository`와 Composite/append
DSL, 그리고 legacy appointment service의 read/write 진입점이었다.

## 결정

승인된 설계안 2인 `경계 우선 전환`을 적용했다.

1. 세 단순 record 저장소는 `LongJdbcRepository<Record>`의 `table`, `extractId`,
   `ResultRow.toEntity` 계약을 구현했다. 기존 `save`, scope 조회, 만료 삭제, 상태
   이력 정렬과 capability 검증은 명시적 메서드로 유지했다.
2. `AppointmentItemRepository`는 proposal/plan revision scope 검증과 batch insert를
   계속 담당하고, `AppointmentOperationalExceptionRepository`는 ID 없는
   append/status 포트로 남겼다. 두 경계의 public DSL은 caller transaction이 없으면
   repository 이름을 포함한 `IllegalStateException`으로 실패한다.
3. `AppointmentService`의 non-suspend create/read 진입점에 Spring `@Transactional`과
   read-only 계약을 선언했다. Spring transaction이 활성화된 create의 legacy signal은
   after-commit synchronization으로 전달하고, coroutine status/cancel은 dispatcher
   내부의 명시적 Exposed transaction을 유지해 imperative interceptor와 겹치지 않게
   했다.
4. Spring Data DAO용 `ExposedJdbcRepository`는 현재 record/Table DSL과 타입 계약이
   달라 도입하지 않았다. DAO Entity 전환은 별도 설계가 필요한 후속 범위다.

## 검증 결과

- 구현 전 RED: core contract/guard 3건과 API annotation 3건이 의도대로 실패했다.
- `AppointmentServiceSpringProxyTest`: H2 Spring proxy에서 실제 test outbox row를
  포함한 create commit·after-commit signal·writer 예외 rollback 2건이 통과했다.
- `:appointment-core:test`: 560 tests, failures 0, errors 0, skipped 0.
- `:appointment-api:test`: 823 tests, 820 passing, 3 pending, failures 0, errors 0.
- `AppointmentDddEventTransactionBoundaryTest` 7건과 core/api Kotlin compile은
  `BUILD SUCCESSFUL`.
- 현재 main source inventory는 `transaction(database)` 55건(21개 파일),
  `private val database: Database` 23개 파일이며, bootstrap/readiness, split,
  worker claim, projection/recovery 경계로 분류해 일괄 제거하지 않았다.
- core repository targeted suite는 H2와 bluetape4k singleton PostgreSQL dialect를
  포함해 통과했다.
- `@Testcontainers` matches는 0이고 `git diff --check`도 통과했다.

## 놓칠 뻔한 점과 대응

### generic CRUD 적용 범위

클래스가 한 테이블을 다룬다는 이유만으로 모든 저장소를 generic CRUD로 바꾸면
scope·lock·batch·상태 전이의 의미가 사라진다. `LongJdbcRepository`는 record와
`LongIdTable`이 이미 맞는 세 저장소에만 적용하고, Composite/append 포트는 분류표에
남겨 둔다.

### current transaction guard의 의미

`TransactionManager.currentOrNull()` 검사는 특정 `Database`를 선택하는 기능이
아니다. caller가 연 현재 Exposed JDBC transaction이 있는지만 보장하며, 별도
transaction을 열거나 connection을 교체하지 않는다. 따라서 guard는 Composite DSL의
잘못된 호출을 빠르게 드러내는 경계이지 transaction manager 대체물이 아니다.

### suspend service annotation

`updateStatus`와 `cancel`은 suspend public method이면서 `Dispatchers.IO` 안에서
명시적 Exposed transaction을 연다. imperative Spring interceptor를 덧씌우지 않고
현재 경계를 유지했으며, 다음에 suspend Spring transaction을 도입할 때는 proxy 호출과
rollback/connection identity 테스트를 먼저 추가한다.

## 다음 작업의 규칙

- 새 repository를 `LongJdbcRepository`로 바꾸기 전에 record ID·mapper·scope/lock
  불변식을 확인하고 기존 public API 회귀 테스트를 먼저 만든다.
- custom DSL은 `transaction {}`를 내부에서 열지 말고 caller-owned current
  transaction 계약과 fail-fast guard를 유지한다.
- `Database` inventory를 줄일 때 bootstrap, readiness, worker, split transaction을
  하나의 일괄 제거 작업으로 취급하지 않는다.
- suspend Spring transaction 경계를 추가할 때는 annotation 존재 확인만으로
  완료를 선언하지 말고 실제 proxy 호출, rollback, 동일 connection을 테스트한다.
