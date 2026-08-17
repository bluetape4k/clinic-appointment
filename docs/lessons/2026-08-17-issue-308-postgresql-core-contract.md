# Issue #308 PostgreSQL 단일 코어 계약 교훈

## 범위

Issue #308의 후속 Slice A는 `appointment-core`, `appointment-event`,
`appointment-messaging`의 production SQL과 동시성 계약을 PostgreSQL 하나로
고정한다. H2는 순수 단위·배선 fixture로만 남기고, lock·unique·outbox claim·query
plan 증거는 `bluetape4k-testcontainers`의 PostgreSQL singleton에서 실행한다.
API·Flyway·migration·README의 Slice B와 실제 배포 SLO는 이 변경 범위에 포함하지
않는다.

## 결정

- production repository에서 H2/MySQL/MariaDB/Cockroach dialect 분기와
  dialect-specific fallback을 제거하고 PostgreSQL lock, retry SQLSTATE,
  duplicate SQLSTATE 계약만 유지한다.
- `TestDB`는 `H2`, `H2_COMMITMENT`, `POSTGRESQL`만 제공한다. 기본 통합 행렬은
  H2와 PostgreSQL이며, H2 결과는 빠른 단위·배선 확인으로만 해석한다.
- query plan fixture의 raw PostgreSQL `EXPLAIN`, `pg_catalog` index metadata,
  deterministic update/select는 별도 JDBC connection을 열지 않고
  `transaction { TransactionManager.current().exec(...) }`로 실행한다.
  대량 seed는 Exposed `batchInsert`를 사용하고, 실제 claim은 production
  `JdbcAppointmentOutboxStore`를 그대로 호출한다.
- `EXPLAIN`은 Exposed가 `EXPLAIN`을 update 문장으로 추론할 수 있으므로
  `StatementType.SELECT`를 명시한다. anti-join predecessor의 `Seq Scan`은
  정상 계획일 수 있으므로 ready/lease index 사용을 직접 검증한다.
- PostgreSQL의 `FOR UPDATE SKIP LOCKED`는 `LIMIT`에 포함된 모든 후보를 잠근다.
  따라서 candidate page는 `limit * clinicBatch`가 아니라 실제 요청 `limit`으로
  제한해 경쟁 relay가 후속 row까지 독점하지 않게 한다.
- PostgreSQL 테스트가 `TransactionManager.defaultDatabase`를 바꾼 뒤 H2
  테스트로 새지 않도록 Query Plan fixture는 `@AfterEach`에서 이전 default를
  복원한다.

## 검증

- `./gradlew :appointment-core:test --no-build-cache --no-daemon`: 549개 통과
- `./gradlew :appointment-event:test --no-build-cache --no-daemon`: 199개 통과
- `./gradlew :appointment-messaging:test --no-build-cache --no-daemon`: 115개 통과
- `AppointmentOutboxQueryPlanTest`: PostgreSQL Testcontainers 4개 통과
  - V22 ready index와 bounded page
  - invalid metadata predecessor terminalization
  - fenced lease predicate
  - fixed seed 20,000-row claim percentile 및 2-relay contention
- `git diff --check`: 통과
- Colima `running`, Docker context `default`에서 실행했으며 별도
  `@Testcontainers` annotation을 추가하지 않았다.

## 운영 경계

이 증거는 실제 PostgreSQL 환경을 Testcontainers로 시뮬레이션한 로컬 계약
검증이다. production 배포, canary, SLO, 외부 broker/DB 운영 지표는 예제
서비스의 현재 범위가 아니므로 이번 완료 조건에서 요구하지 않는다.

## 후속 범위

전체 source tree의 남은 raw JDBC inventory와 Exposed/Bluetape JDBC 우선 전환은
후속 Issue #350으로 분리했다. 이 Slice A의 query-plan·outbox fixture 전환과
겹치지 않도록 API migration support, benchmark, Gatling 및 framework boundary는
해당 이슈에서 허용 목록과 대체 근거를 함께 정리한다.

## 재발 방지 규칙

1. 새 raw PostgreSQL 검증은 direct JDBC helper를 만들기 전에 Exposed transaction
   안의 `TransactionManager.current().exec` 가능 여부를 먼저 확인한다.
2. PostgreSQL lock/unique/claim 의미를 H2 GREEN으로 대체하지 말고 named
   Testcontainers regression을 함께 둔다.
3. Test fixture가 `TransactionManager.defaultDatabase`를 바꾸면 setup에서 이전
   값을 저장하고 teardown에서 반드시 복원한다.
4. candidate page 상한을 변경할 때는 claim 정확성뿐 아니라 두 relay의
   distinct id와 lock 대기 시간을 함께 검증한다.
