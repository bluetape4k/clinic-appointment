# Issue #315 조회 전용 Spring Data projection 위험 원장

## 범위와 중단 원칙

이 원장은 `appointment-api/src/test`에 한정된 pilot의 위험을 기록한다.
production source, route, dependency scope, public ABI를 변경하지 않으며,
아래 위험의 완화 조건을 만족하지 못하면 기존 `ClinicRepository`를 유지하고
Spring Data projection을 운영 경계로 승격하지 않는다.

## 위험 원장

| ID | 위험 신호 | 완화와 판정 | 재실행/rollback 지점 |
|---|---|---|---|
| R1 | Spring refresh 중 `Database` 등록이 일어나고 실패 경로에 전역 상태가 남을 수 있다. | `defaultDatabase`와 `primaryDatabase`의 이전 상태를 저장하고 default를 임시 해제한다. `springTransactionManager` 단일 bean, transaction의 실제 DB handle, narrow allow-list를 fail-closed로 확인한다. callback 완료와 resource unbind 뒤 context를 닫고 새 handle만 unregister한 뒤 이전 상태를 복원한다. | refresh/transaction/close 실패 테스트가 하나라도 실패하면 pilot 설정만 되돌리고 운영 채택을 보류한다. |
| R2 | PartTree가 `EntityID` tenant predicate 또는 정렬을 잘못 만들거나 row별 추가 조회를 만들 수 있다. | `StatementInterceptor`로 typed tenant predicate, `ORDER BY id ASC`, 대표 SELECT 1회와 추가 `findById` SELECT 0회를 검증한다. raw `@Query` fallback은 사용하지 않는다. | SQL 증거가 조건을 충족하지 않으면 repository 설계를 중단하고 기존 Table DSL을 유지한다. |
| R3 | Table DSL과 Spring Data가 서로 다른 connection 또는 transaction을 사용할 수 있다. | `TransactionTemplate` 안에서 Spring synchronization을 확인하고 `DataSourceUtils` connection과 Exposed current transaction의 physical connection identity 및 `springTransactionManager` factory 설정을 read-back한다. | identity 또는 factory 설정이 다르면 production 통합을 금지하고 test-only pilot만 보존한다. |
| R4 | H2 결과를 PostgreSQL 운영 근거로 오인할 수 있다. | H2/PostgreSQL profile, dialect, 고유 schema, readiness/connection/statement/transaction timeout을 raw evidence에 기록한다. PostgreSQL 준비 실패 시 H2 결과를 승격하지 않고 `PENDING`으로 남긴다. | PostgreSQL evidence와 `EXPLAIN`이 없으면 운영 채택을 보류한다. |
| R5 | full-row DAO가 민감 column을 읽거나 상속 CRUD surface가 운영 경계로 노출될 수 있다. | synthetic fixture만 사용하고 Entity/repository/adapter를 전용 `internal` package에 둔다. adapter는 `Long` 입력과 `ClinicRecord` 출력만 노출하며 CRUD 메서드를 호출하지 않는다. runtimeClasspath와 bootJar에서 test-only class/dependency가 없음을 fail-closed로 검증한다. | artifact 경계가 깨지면 source/dependency 변경을 되돌리고 production 전환을 중단한다. column-level projection은 별도 이슈로 분리한다. |
| R6 | 성능 측정이 경로·순서·환경 면에서 비대칭일 수 있다. | cardinality 4/32/128, 5 warm-up/30 sample, legacy/candidate 실행 순서 교대, 동일 transaction total timing, component timing, median/p95, statement count와 pool 조건을 함께 기록한다. chart와 sanitized raw evidence를 필수 산출물로 만든다. 단일 thread pilot의 pool 동시성은 `NOT_TESTED`로 고정한다. | 결과·chart·secret scan이 없거나 pool 동시성이 미검증이면 참고값으로만 남기고 운영 채택을 보류한다. |

## 공통 운영 보호

- 모든 테스트는 `@ResourceLock(API_INTEGRATION_RESOURCE, READ_WRITE)`와
  `SAME_THREAD`로 전역 Exposed registry와 context lifecycle을 직렬화한다.
- PostgreSQL은 pilot 전용 고유 schema를 생성하고 Hikari pool을 닫은 뒤에만
  해당 schema를 삭제한다. 공유 `public` schema에서 `deleteAll()`을 실행하지
  않는다.
- raw output은 저장 전에 JDBC URL, 비밀번호, 환경 기반 credential을
  `<redacted>`로 정규화하고 secret scan을 통과한 sanitized artifact만
  보존한다.
- timeout, cleanup, residual row/FK count, schema ownership을 실패 경로에도
  기록해 재현 가능한 rollback 지점을 남긴다.

## 최종 판정 규칙

다음 중 하나라도 만족하지 못하면 Issue #315의 운영 적용은 `보류`다.

1. 결과와 tenant 격리가 기존 Table DSL과 일치한다.
2. transaction/connection/manager wiring과 cleanup이 반복 실행에서 안전하다.
3. H2와 PostgreSQL의 SQL/`EXPLAIN`/성능 evidence가 있다.
4. runtime artifact 경계와 sanitized evidence가 검증된다.
5. pool 동시성 미검증을 숨기지 않고 후속 검증 항목으로 등록한다.
