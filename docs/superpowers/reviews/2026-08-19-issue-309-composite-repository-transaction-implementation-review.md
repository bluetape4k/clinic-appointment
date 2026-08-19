# Issue #309 구현 6면 검토

## 검토 대상과 결론

- 이슈: [#309](https://github.com/bluetape4k/clinic-appointment/issues/309)
- 설계: 승인된 2번 `경계 우선 전환`
- 브랜치: `refactor/issue-309-composite-repository-transaction`
- 검토 범위: `LongJdbcRepository` 수직 전환, Composite/append caller transaction
  guard, `AppointmentService` Spring transaction 선언, 회귀 테스트와 문서
- 결론: **P0 0건, P1 0건, P2 2건 — 통과**

이번 구현은 Composite repository를 하나의 facade로 합치지 않고 세 개의 반복적인
record 저장소에만 `LongJdbcRepository` 계약을 적용한다. `AppointmentItemRepository`와
운영 예외 append 포트는 custom DSL과 caller-owned transaction을 유지한다.

## 변경 근거

| 영역 | 검토 근거 | 판정 |
| --- | --- | --- |
| 단순 저장소 | `AppointmentIdempotencyRepository:16-24`, `AppointmentStateHistoryRepository:19-27`, `TreatmentSpaceRepository:19-26`의 `table`, `extractId`, `ResultRow.toEntity` 구현 | 통과 |
| Composite DSL | `AppointmentItemRepository:47-52`, `:103-108`의 current transaction guard와 기존 scope/batch 검증 유지 | 통과 |
| append DSL | `AppointmentOperationalExceptionRepository:17-60`의 append·acknowledge·resolve 상태 전이와 caller transaction guard | 통과 |
| Spring 경계 | `AppointmentService:76-80`, `:100-122`, `:284-292`, `:426-433`, `:530-552`의 read/create 선언과 after-commit signal | 통과 |
| API 호환 | 기존 public 메서드명·입력 검증·반환 record·상태 정렬을 보존하고 공통 CRUD를 특수 SQL로 대체하지 않음 | 통과 |

## 독립 관점 검토

### 1. 요구사항·수용 기준

- 세 `LONG-JDBC` 대상이 실제 `io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository`
  를 구현한다.
- Composite와 `APPEND-DSL` 공개 API를 보존하고 transaction 밖 호출은 명시적
  `IllegalStateException`으로 빠르게 실패한다.
- public non-suspend read/create 경계에 Spring annotation 계약을 추가하고, outbox와
  idempotency 순서는 유지하면서 signal만 commit 이후에 전달한다. suspend status/cancel은
  명시적 Exposed transaction을 유지한다.
- H2와 PostgreSQL을 포함한 기존 repository/API 회귀 테스트가 통과했다.

**판정: 통과.** 이슈의 핵심 변경과 설계안 2의 범위를 벗어난 일괄 변환은 없다.

### 2. 아키텍처·의존성

`LongJdbcRepository`는 현재 record/Table DSL과 맞는 generic 계약만 재사용한다.
Spring Data DAO용 `ExposedJdbcRepository`로 기계 변환하지 않았고, repository가
`Database`를 받아 독립 transaction을 시작하지 않는다. `AppointmentOperationalException`
은 생성 ID를 외부 record로 관리하지 않는 append 포트이므로 generic CRUD를 억지로
노출하지 않았다.

**판정: 통과.** 변경된 저장소는 기존 bean 생성자와 도메인 경계를 유지한다.

### 3. 보안·데이터 무결성

tenant/clinic scope, proposal·plan revision 검증, 상태 전이 조건, idempotency 만료
조건, outbox/event 순서는 기존 코드에 남아 있다. guard는 transaction이 없을 때만
실패하며 임의의 `Database` 선택이나 범위 우회를 추가하지 않는다. 운영 예외의
`acknowledge`와 `resolve`는 기존 상태 조건과 양수 ID 검증을 그대로 사용한다.

**판정: 통과.** 데이터 무결성 규칙을 공통 CRUD 뒤에 숨기지 않았다.

### 4. 성능·동시성

`AppointmentItemRepository`의 batch insert와 composite 검증, 기존 claim/outbox의
lock/`SKIP LOCKED` DSL은 변경하지 않았다. `LongJdbcRepository` 적용은 mapper와
공통 contract 노출에 한정되며 추가 query 또는 거대 transaction을 도입하지 않는다.

**판정: 통과.** 이번 diff에서 동시성 경계의 변경은 확인되지 않았다.

### 5. 운영·검증

검증 결과는 다음과 같다.

| 검증 | 결과 |
| --- | --- |
| `:appointment-core` targeted repository/guard tests | 6 passing (H2·PostgreSQL parameter 포함), `BUILD SUCCESSFUL` |
| `:appointment-api` proxy/annotation/atomicity targeted tests | 5 + 11 passing, `BUILD SUCCESSFUL` |
| `AppointmentDddEventTransactionBoundaryTest` | 7 passing, `BUILD SUCCESSFUL` |
| `:appointment-core:test` | 560 tests, failures 0, errors 0, skipped 0 |
| `:appointment-api:test` | 823 tests, 820 passing, 3 pending, failures 0, errors 0 |
| `:appointment-core:compileKotlin :appointment-api:compileKotlin` | `BUILD SUCCESSFUL` |
| `@Testcontainers` production/test scan | 0 matches |
| `git diff --check` | 통과 |

Spring proxy fixture는 no-op port가 아니라 H2 test outbox row를 같은 transaction에
insert하여 성공 시 row 1건, notification writer 예외 시 row 0건을 확인한다. 또한
실제 AOP proxy 활성화와 after-commit signal 순서를 assertion한다.

core 전체 테스트에는 H2와 bluetape4k singleton PostgreSQL dialect parameter가
포함되었고, repository fixture는 기존 `withTables`/singleton launcher 규칙을
그대로 사용했다.

**판정: 통과.** pending 3건은 기존 API suite의 조건부 실행 항목이며 이번 변경으로
추가된 실패·오류는 없다.

### 6. API·라이브러리 사용자 경험

기존 `save`, scope 조회, 만료 삭제, 상태 이력 정렬, 운영 예외 상태 전이 public
메서드와 record 반환 형태를 유지했다. 새 실패 조건은 transaction을 열 책임이 없는
Composite/append DSL에만 적용되며 예외 메시지에 repository 이름을 포함한다. KDoc은
caller-owned transaction과 Spring proxy 경계를 명시한다.

**판정: 통과.** 기존 호출자가 transaction 책임을 혼동할 수 있는 암묵적 동작을
계속 허용하지 않는다.

## P2 후속 위험

1. `LongJdbcRepository`가 제공하는 generic `findById`, `deleteAll`, `updateAll` 등은
   scope 없는 API 표면을 함께 노출한다. 특히 `TreatmentSpaceRepository`는 문서와
   scope 전용 메서드를 우선 사용하도록 고정했지만, 외부 호출을 컴파일 단계에서
   차단하려면 별도 delegate/adapter 설계가 필요하다.
2. `private val database: Database` 및 `transaction(database)` inventory는 기존
   bootstrap/readiness, split transaction, worker 경계까지 포함해 남아 있다. 이번
   변경은 새 직접 주입을 추가하지 않았고, 분류표와 후속 issue 후보로 보존했다.

두 항목은 현재 회귀·proxy 검증을 무효화하는 P1 결함은 아니며, 다음 경계 확장 시
검증을 먼저 추가해야 하는 후속 규칙이다. suspend status/cancel은 annotation을
추가하지 않고 명시적 Exposed 경계를 유지하는 설계 결정을 테스트와 KDoc으로
고정했다.

## 게이트 결과

- Type-A A-01..A-12: 기준선·설계/계획 review·구현·검증·문서·전달 항목 모두
  산출물 또는 fresh command 결과를 가짐
- SPW-01..05: 목적, 근거, 결정, 검증, 독립 review 통과
- KO-01..07: 한국어 문서/KDoc, 식별자·명령 보존, `git diff --check` 통과
- Kotlin final checklist KT-FIN-01..11: API/transaction/Exposed/동시성/문서
  항목 통과, generic scope API 표면은 P2로 기록
- Kotlin testing checklist KT-TEST-01..05: RED/GREEN, fixture 격리, H2/PG,
  full module regression, 정적 검사 통과

## 최종 판정

**통과.** P0/P1 결함은 없고 승인된 설계 범위의 구현·검증·문서화가 완료됐다.
P2 두 항목은 후속 범위로 기록하며, 이번 변경의 Composite 보존·caller transaction·
반복 CRUD 재사용 계약을 막지 않는다.
