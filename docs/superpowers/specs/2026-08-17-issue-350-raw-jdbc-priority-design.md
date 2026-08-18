# Issue #350 raw JDBC 우선 경로 정렬 설계

## 문서 상태

- 대상: `bluetape4k/clinic-appointment` Issue #350
- 기준 브랜치: `origin/develop` (`f0c7614beed766efc4b88a1a59aa5c370f8fccf7`)
- 작업 브랜치: `refactor/issue-350-raw-jdbc-priority`
- 설계 승인: 2026-08-17, 사용자 승인
- 문서 언어: 한국어
- 설계 범위: 전체 source set의 direct JDBC 호출 분류와 최소 경로 전환

## 문제와 목표

현재 저장소에는 Exposed를 사용하는 애플리케이션 코드와 직접 JDBC를 사용하는
코드가 함께 있다. 직접 JDBC 자체가 모두 잘못된 것은 아니지만, Exposed
트랜잭션 안에서 `Connection`, `PreparedStatement`, `ResultSet`을 직접 다루면
트랜잭션 경계와 자원 수명이 호출자·프레임워크·드라이버 사이에서 분산된다.
반대로 Flyway, readiness metadata, Gatling, benchmark driver setup처럼
애플리케이션 Exposed 트랜잭션의 외부 경계에서는 JDBC가 책임에 맞는 구현일 수
있다.

Issue #350의 목표는 모든 direct JDBC를 기계적으로 삭제하는 것이 아니라, 각
호출을 다음 세 가지로 분류하고 책임에 맞는 우선 경로를 적용하는 것이다.

- `MIGRATE`: Exposed 트랜잭션 또는 일반 CRUD/query/fixture 안에서 직접 JDBC를
  사용하므로 Exposed DSL 또는 `TransactionManager.current().exec(...)`로
  전환한다.
- `ADAPTER`: Exposed/Bluetape 경로를 재사용할 수 있지만 호출 형태가 이미
  정해진 경계 어댑터인 경우, 기존 helper를 확인한 뒤 필요한 최소 래퍼만
  둔다.
- `ALLOWED-BOUNDARY`: Flyway `DataSource`, Spring/JDBC readiness metadata,
  Gatling load generation, benchmark/Testcontainers driver setup 등
  애플리케이션 트랜잭션 밖의 책임으로서 raw JDBC를 유지한다. 경로·호출·유지
  이유·대체하지 않은 이유를 allowlist에 기록한다.

성공 조건은 기능과 트랜잭션 의미를 보존하면서 새로운 애플리케이션 raw JDBC를
추가하지 않고, 최종 inventory가 각 잔여 호출의 분류와 근거를 제공하는 것이다.

## 현재 근거

기준 브랜치에서 Issue #350의 검색식으로 현재 source set을 재검사한 결과는
다음과 같다.

| 영역 | 파일 수 | marker line 수 | 설계상 우선 검토 |
|---|---:|---:|---|
| 전체 | 38 | 334 | 모든 Kotlin/Java source, test, Gatling, benchmark, fixture |
| `appointment-api` | 34 | 293 | migration support, query-plan/performance test, readiness와 API test |
| `appointment-core` | 1 | 1 | core test/helper의 단일 호출 |
| `appointment-messaging` | 2 | 23 | outbox query-plan/performance와 readiness 경계 |
| `benchmark` | 1 | 17 | PostgreSQL production-schema benchmark fixture |
| event/notification/solver | 0 | 0 | inventory 확인만 수행 |

현재 코드에는 이미 다음과 같은 기준 패턴이 있다.

- `appointment-api`와 `appointment-messaging`의 애플리케이션 경로가
  `transaction {}`을 소유한다.
- `appointment-messaging` 테스트에는
  `TransactionManager.current().exec(...)`를 사용하는 PostgreSQL 전용
  query-plan 검증 코드가 있다.
- 버전 카탈로그에는 `bluetape4k-jdbc`와
  `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc` 좌표가 있지만,
  이번 변경은 새 공통 추상화를 추가하지 않고 현재 모듈 의존성과 helper가
  실제로 제공하는 기능을 확인한 뒤 재사용한다.
- Issue #309가 repository abstraction, 현재 transaction, caller-owned
  transaction 책임을 별도 범위로 다룬다.

GNO 검색에서는 Issue #350에 직접 적용할 추가 결정 자료를 찾지 못했다. 따라서
현재 저장소 코드, live GitHub Issue #350/#309, 기존 테스트와 모듈 계약을
기준 데이터 원본으로 사용한다.

## 설계 원칙과 경계

### 1. 트랜잭션 우선

호출이 이미 Exposed `transaction {}` 안에 있으면 새 `Connection`을 열지 않는다.
다음 우선순위로 전환한다.

1. 테이블/컬럼 조합이 Exposed DSL로 표현 가능하면 DSL을 사용한다.
2. PostgreSQL 전용 SQL, CTE, `FOR UPDATE SKIP LOCKED`, `EXPLAIN`, `ANALYZE`
   등 DSL보다 SQL 보존이 중요한 경우
   `TransactionManager.current().exec(...)`를 사용한다.
3. SQL 인자는 문자열 보간하지 않고 Exposed의 바인딩 가능한 인자 경로를
   사용한다. 기존 helper가 정확히 같은 바인딩과 결과 매핑을 제공하면
   `bluetape4k-exposed-jdbc`를 재사용한다.

`Connection`, `PreparedStatement`, `ResultSet`의 수동 생성·종료를 애플리케이션
트랜잭션 안에 남기지 않는다. `exec` 호출은 enclosing transaction의 connection,
commit/rollback, timeout 수명을 그대로 따른다.

### 2. 경계 보존

다음 호출은 먼저 책임 경계를 확인한다.

- Flyway migration과 migration test support의 `DataSource`/metadata 호출
- Spring Boot readiness validator가 애플리케이션 시작 시 JDBC metadata를
  확인하는 호출
- Gatling Java/Kotlin load generation의 HTTP 시나리오 보조 JDBC 호출
- benchmark fixture가 PostgreSQL schema, connection setup, driver-level
  timing을 직접 제어하는 호출
- Testcontainers PostgreSQL launcher와 container lifecycle을 준비하는 호출

이 호출은 `ALLOWED-BOUNDARY` 또는 `ADAPTER`로 남길 수 있지만, allowlist에
정확한 파일 경로, 심볼/호출, 책임, raw JDBC를 유지하는 이유, Exposed/Bluetape로
바꾸지 않는 이유, 검증 명령을 기록해야 한다. 단지 테스트 파일이라는 이유로
허용하지 않는다.

### 3. Issue #309와의 분리

이번 변경은 repository interface, caller-owned transaction, 서비스 계층의
transaction 책임을 재설계하지 않는다. 기존 호출 순서와 public API를 유지한
상태에서 JDBC 실행 경로만 정렬한다. repository 추상화가 필요한 항목은
Issue #309에 후속 근거로 남긴다.

### 4. 데이터베이스 계약

- PostgreSQL은 production-schema 및 Testcontainers 계약의 기준이다.
- H2는 unit/wiring 보조 범위로 유지하고 production dialect matrix를 다시
  도입하지 않는다.
- PostgreSQL 전용 SQL을 Exposed 일반식으로 억지로 바꾸지 않는다. 필요한 경우
  `exec`와 명시적인 allowlist 근거를 사용한다.
- Testcontainers 검증은 저장소의 bluetape4k singleton launcher 규칙을
  따른다. `@Testcontainers`를 도입하지 않는다.
- SQL 값은 항상 바인딩 경로로 전달한다. `EXPLAIN` 래퍼, 테이블/스키마 이름,
  정렬 키처럼 SQL 문법을 구성하는 동적 fragment는 고정 상수 또는 검증된
  allowlist에서만 선택하며 요청 값이나 사용자 입력을 식별자로 사용하지
  않는다.

benchmark fixture와 결과 검증은 기존 저장소 계약을 그대로 사용한다.

- 변경된 측정 경로가 없으면
  `./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark --no-daemon --console=plain`
  을 실행한다.
- fixture 또는 측정 경로가 변경되면 smoke에 더해
  `./gradlew :appointment-messaging-benchmark:mainBenchmark --no-daemon --console=plain`
  을 실행한다.
- 생성된 report는 기존 `scripts/collect-appointment-messaging-benchmark.mjs`
  와 `scripts/validate-appointment-messaging-benchmark.mjs`로 수집·검증한다.
  이 Issue에서는 새로운 성능 임계값이나 production SLO를 추론하지 않고,
  task 성공과 기존 validator 결과를 증거로 사용한다.

## 구현 단위와 순서

### 단계 A — inventory와 allowlist 기준선

1. Issue #350 검색식을 전체 source set에 실행해 파일·호출·모듈·분류를
   기록한다.
2. 각 marker를 `MIGRATE`, `ADAPTER`, `ALLOWED-BOUNDARY` 중 하나로 분류하고,
   분류 불가 항목은 구현 전에 책임 경계를 확인한다.
3. allowlist 문서를 추가해 raw JDBC 유지 항목의 파일·심볼·이유·대체하지
   않은 이유를 고정한다.
4. 기준 inventory 검증 스크립트는 새로운 direct JDBC 호출이 추가되면 실패하고,
   allowlist 항목은 파일과 심볼이 실제로 존재하는지 확인한다.

### 단계 B — `appointment-api`

1. Exposed `transaction {}` 내부의 migration/query-plan/fixture/helper부터
   Exposed DSL 또는 `TransactionManager.current().exec(...)`로 전환한다.
2. Spring readiness와 migration framework boundary는 호출 수명과 책임을
   보존하는 최소 adapter 또는 allowlist로 정리한다.
3. 기존 테스트의 상태 초기화, parameter binding, row mapping, PostgreSQL
   explain/locking semantics를 유지한다.
4. 변경 단위마다 모듈 테스트를 실행하고, raw JDBC inventory를 재실행한다.

### 단계 C — messaging/core/benchmark

1. `appointment-messaging` outbox query-plan/performance path는 기존
   Exposed transaction와 PostgreSQL `exec` 패턴을 기준으로 정리한다.
2. `appointment-core`의 단일 호출은 caller transaction과 repository 책임을
   바꾸지 않는 최소 수정으로 처리한다.
3. benchmark fixture는 production-schema와 driver timing을 보존해야 하므로
   Exposed transaction에 포함되는 query만 전환하고, setup/measurement
   boundary는 allowlist로 남긴다.
4. event/notification/solver에 새 marker가 없는지 전체 검색으로 재확인한다.
5. Testcontainers singleton launcher, Hikari/DataSource, benchmark connection의
   종료·반납 lifecycle을 확인한다. 테스트가 통과해도 container나 pool이
   남아 프로세스를 붙잡지 않는지 bounded command와 종료 로그로 검증한다.

### 단계 D — 문서·회귀·검증

1. README/KDoc/lesson에 “Exposed transaction 우선, 경계 raw JDBC는 근거와
   allowlist 필수” 규칙과 실제 적용 범위를 기록한다.
2. 단위/통합 회귀 테스트에 transaction 참여, parameter binding, PostgreSQL
   lock/query-plan, fixture lifecycle의 성공·실패 경로를 포함한다.
3. 모든 영향 모듈의 build/test를 순차 실행하고, PostgreSQL Testcontainers
   검증을 별도로 증명한다.
4. `git diff --check`, inventory 정적 검증, 문서 read-back, PR CI를 완료한다.

## 실패 모드와 대응

| 실패 모드 | 감지 신호 | 대응 |
|---|---|---|
| `exec`가 enclosing transaction 밖에서 호출됨 | `No transaction in context`, commit/rollback 결과 불일치 | 호출자 transaction을 확인하고 기존 책임을 보존하는 위치로 이동한다. #309 범위 변경은 하지 않는다. |
| raw SQL parameter가 문자열 보간으로 남음 | inventory 또는 정적 검사에서 보간 SQL 발견 | Exposed binding/helper로 바꾸고 입력값·null·배열 경로 회귀 테스트를 추가한다. |
| PostgreSQL 전용 lock/query-plan 의미가 DSL 전환으로 변함 | Testcontainers query-plan/locking 테스트 실패 또는 plan drift | 해당 query는 `TransactionManager.current().exec(...)`로 복원하고 allowlist에 SQL 책임을 기록한다. |
| framework boundary를 애플리케이션 경로로 잘못 분류함 | Flyway/readiness/Gatling/benchmark 시작·종료 실패 | 원래 lifecycle을 복구하고 `ALLOWED-BOUNDARY` 또는 `ADAPTER` 근거를 명시한다. |
| H2에서만 통과하고 PostgreSQL에서 실패함 | PostgreSQL Testcontainers 또는 production-schema fixture 실패 | H2를 정답으로 취급하지 않고 PostgreSQL 계약과 migration schema를 기준으로 수정한다. |
| 값 바인딩과 SQL fragment 선택을 혼동함 | 동적 식별자/`EXPLAIN` fragment가 문자열 보간으로 생성되거나 사용자 값이 식별자로 전달됨 | fragment를 고정 상수·검증 allowlist로 제한하고 값은 `exec`/기존 helper의 바인딩으로 전달한다. 관련 회귀 테스트와 inventory 근거를 남긴다. |
| 테스트 자원이 종료되지 않음 | bounded Gradle command 이후 Hikari pool 또는 Testcontainers launcher가 살아 있음 | singleton launcher와 pool close 경로를 확인하고 종료·반납 회귀 검증을 추가한다. `@Testcontainers`는 사용하지 않는다. |
| Issue #309와 책임 변경이 섞임 | repository API, transaction ownership, caller contract diff 증가 | 변경을 되돌리고 #309 후속 항목으로 기록한다. 이번 PR은 SQL 실행 경로만 남긴다. |

## 호환성과 롤백

- public API, repository method signature, table/schema 이름, Flyway migration
  순서, PostgreSQL SQL semantics를 변경하지 않는다.
- 전환은 파일/호출 묶음 단위로 수행하고 각 묶음 직후 해당 모듈 테스트와
  inventory를 실행한다.
- 실패 시 마지막 통과한 묶음의 commit으로 되돌리거나 해당 파일 단위로
  롤백한다. migration 파일과 production schema를 되돌리는 별도 작업은 하지
  않는다.
- allowlist에 남은 raw JDBC는 후속 issue에서 제거할 수 있도록 근거와
  검증 명령을 보존한다.

## 수용 기준

1. 전체 source set의 direct JDBC marker가 모두 세 분류와 근거를 가진다.
2. `MIGRATE` 항목은 Exposed DSL, `TransactionManager.current().exec(...)`,
   또는 정확히 맞는 기존 Bluetape helper를 사용한다.
3. 애플리케이션 코드에서 Exposed transaction을 우회하는 새 raw
   `Connection`/`PreparedStatement` 경로가 없다.
4. `ALLOWED-BOUNDARY`와 `ADAPTER` 항목은 파일·심볼·책임·유지/대체 사유가
   allowlist에 문서화되어 있다.
5. Issue #309의 repository·transaction 책임은 변경되지 않는다.
6. PostgreSQL Testcontainers contract, H2 unit/wiring contract, migration 및
   outbox/query-plan/benchmark 동작이 회귀하지 않는다.
7. 영향 모듈 build/test, PostgreSQL Testcontainers 검증, benchmark smoke 및
   필요 시 full task와 기존 report collector/validator, inventory 정적 검사,
   `git diff --check`가 통과한다.
8. README/KDoc/lesson이 실제 변경과 분류 결과를 반영한다.
9. SQL 값 바인딩, 동적 SQL fragment allowlist, Testcontainers/Hikari 종료·반납
   lifecycle에 대한 회귀 검증 결과가 남아 있다.

## DoD

- [ ] 설계·구현 계획·review artifact가 현재 Issue #350/#309와 기준 브랜치에
      추적된다.
- [ ] `appointment-api`, `appointment-messaging`, `appointment-core`,
      `benchmark`의 분류·전환·회귀 증거가 있다.
- [ ] raw JDBC allowlist와 inventory 검증이 저장소에 남아 있다.
- [ ] PostgreSQL Testcontainers 검증 결과가 fresh command output으로 남아
      있다.
- [ ] PR body, Issue metadata, CI와 exact head가 일치한다.
- [ ] 사용자의 최신 merge approval 이후에만 rebase merge하고, develop을
      remote와 동기화한 뒤 feature worktree를 정리한다.

## 설계 writer gate

- `SPW-01`: PASS — Issue #350/#309, 현재 inventory, 모듈 계약과 scope를
  명시했다.
- `SPW-02`: PASS — 분류 계약, 경계, failure mode, 수용 기준, DoD를 포함했다.
- `SPW-03`: PASS — 한국어 technical register와 repository-local language
  policy를 적용하고 identifier/command/API token을 보존했다.
- `SPW-04`: PASS — 기준 브랜치·live issue·현재 소스 검색 결과와 설계
  결정을 연결했다.
- `SPW-05`: PASS — 전체 Markdown을 read-back했고 placeholder와 범위 충돌을
  제거했다.

설계 review는 여섯 관점(performance, stability, security, operator/Ops,
developer/API, user/caller)과 main-session integration을 거친 뒤 P0=0,
P1=0일 때 완료한다.
