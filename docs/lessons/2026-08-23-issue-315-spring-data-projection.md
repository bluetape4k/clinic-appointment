# Issue #315 조회 전용 Spring Data projection 교훈

## 맥락

기존 `ClinicRepository`의 `Clinics` tenant 조회를
`bluetape4k-exposed-spring-boot-jdbc`의 Spring Data PartTree repository로
대체할 수 있는지 production source를 건드리지 않는 test-only pilot으로
검증했다. 승인된 설계와 계획은 각각
`docs/superpowers/specs/2026-08-23-issue-315-spring-data-projection-design.md`와
`docs/superpowers/plans/2026-08-23-issue-315-spring-data-projection-plan.md`에
있다.

## 선택과 거부

- 선택: `internal` 전용 `ClinicProjectionEntity`/repository/Long adapter,
  명시적 `ApplicationContextRunner`, typed `EntityID` PartTree predicate,
  Spring `TransactionTemplate` 안의 조회만 검증했다.
- 거부: production repository·controller·dependency scope 변경, raw
  `@Query`, full CRUD adapter, API pagination 교체, shared PostgreSQL public
  schema cleanup을 적용하지 않았다.
- 판정: 결과·tenant 격리·transaction identity·단일 SELECT·고유 schema
  cleanup은 통과했지만 candidate total median이 legacy보다 느리고 pool
  동시성 및 column-level projection 증거가 없어 운영 채택을 보류했다.

## 예상 밖의 실패와 수정

`ExposedSpringDataAutoConfiguration`을 그대로 narrow context에 import하면
`springTransactionManager(DataSource)` factory와 `SpringTransactionManager`
생성자가 각각 `Database.connect`를 호출해 전역 registry에 두 handle을
만들었다. 첫 cleanup 구현은 한 handle만 unregister해 sentinel 상태 복원
검증에서 실패했다. 파일럿은 upstream auto-configuration을 복제하지 않고
test-only `SpringTransactionManager(dataSource, DatabaseConfig {}, false)`를
단일 bean으로 정의했고, `defaultDatabase`를 context 생성 동안 임시 해제한
뒤 candidate handle만 unregister하도록 수정했다.

또한 Spring context destroy callback은 `DisposableBean` 예외를 로그로
소비한다는 점을 확인했다. close probe가 관측한 원본 예외를 helper가
read-back해 callback 예외의 `suppressed`에 연결하도록 보강했다. 이 규칙은
향후 ApplicationContextRunner lifecycle 테스트에서 반드시 유지한다.

계획에 적었던 in-process `Future.cancel/join`, synthetic 다중
`Database.connect` tracker와 pool active-count read-back은 이번 pilot에
구현하지 않았다. 실제 cleanup은 context가 만든 current primary 후보와
고유 schema owner를 정리하고, 외부 Gradle process deadline으로 무기한 실행을
막는 범위다. 이 차이는 production adoption의 미검증 조건으로 남긴다.

## 실제 검증 증거

- RED: 초기 skeleton에서 `ClinicProjectionEntity`, repository, adapter,
  `withPilotContext` missing symbol로 `compileTestKotlin` 실패.
- GREEN: H2와 PostgreSQL profile에서
  `ClinicSpringDataProjectionPilotTest` 7개가 각각 통과했다.
- PostgreSQL: `postgres:18-alpine`, 고유 `issue315_*` schema,
  Hikari `maximumPoolSize=2`, `statement_timeout=5000ms`, `lock_timeout=2000ms`,
  `idx_clinics_tenant` index scan, context close 뒤 `pg_namespace` 부재.
- 측정: 4/32/128건, warm-up 5회, 측정 30회, 실행 순서 교대, representative
  SELECT legacy/candidate 각 1회.
- 차트: semantic audit, visual audit, asset-pair audit와 full-size PNG
  inspection이 PASS.
- raw artifact: JDBC/credential sanitization 뒤
  `gitleaks detect --no-banner --redact --no-git --config .gitleaks.toml`
  PASS.
- artifact boundary: `runtimeClasspath` forbidden exact match 0, fresh
  `bootJar` exact pilot class match 0, jar 1개.
- module gate: `:appointment-api:test` 836 passing/3 pending, failures/errors 0;
  `:appointment-api:build` 성공.

재현 명령:

```bash
./gradlew --no-daemon :appointment-api:test --tests \
  "io.bluetape4k.clinic.appointment.api.projection.ClinicSpringDataProjectionPilotTest"
./gradlew --no-daemon :appointment-api:test \
  -Dspring.profiles.active=test-postgresql \
  --tests "io.bluetape4k.clinic.appointment.api.projection.ClinicSpringDataProjectionPilotTest"
```

## 다음 이슈의 guard

1. Spring Data Exposed auto-configuration을 추가할 때 `Database.connect`
   호출 수와 global `TransactionManager` registry 복원을 먼저 검증한다.
2. test-only full-row DAO를 production으로 옮기기 전에 column-level projection,
   민감 필드 접근, authenticated tenant authorization을 별도 acceptance로
   고정한다.
3. single-thread benchmark 결과를 pool 동시성 근거로 재사용하지 않는다.
   `poolConcurrency = NOT_TESTED`를 누락하면 adoption gate를 닫지 않는다.
4. PostgreSQL 결과는 H2 fallback과 섞지 않고 profile, 고유 schema, EXPLAIN,
   schema drop/read-back을 함께 보존한다.

## 현재 상태

Issue #315는 기능 검증과 운영 경계 확인까지 완료했지만 성능 우위와 운영
안전성의 나머지 증거가 없으므로 기존 Table DSL을 유지한다. 후속 이슈가
명시적으로 승인되기 전에는 production source나 API ABI를 확장하지 않는다.
