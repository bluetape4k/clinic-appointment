# Spring-managed DataSource와 Exposed Database 연결 표준화 설계

## 문제

Issue #223은 Spring 애플리케이션이 관리하는 HikariCP `DataSource`를 Exposed
`Database` handle이 재사용하도록 연결 경계를 명확히 하는 작업이다. 현재 운영
코드에는 이미 `Database.connect(dataSource)`를 사용하는 두 경로가 있지만, 두
설정 클래스가 전역 Exposed 기본 database 복원과 registration lock을 각각 구현하고
있다. 테스트·migration·dialect·load·Gatling 경로에는 의도적으로 독립적인 JDBC
fixture가 섞여 있어, 단순한 전역 치환은 pool 소유권과 schema 격리를 깨뜨릴 수 있다.

## 승인된 범위와 증거

- 저장소: `bluetape4k/clinic-appointment`
- 이슈: [#223](https://github.com/bluetape4k/clinic-appointment/issues/223)
- 승인: 2026-08-05 현재 대화에서 Type A 첫 계획을 사용자가 `승인`함
- 기준: `origin/develop`의 `89a1405031333b6f40364443f2d4e938af100a2b`
- 현재 운영 source 검색 결과: `ServiceConfig.kt`와
  `ProfileReevaluationConfiguration.kt`의 두 `Database.connect(dataSource)`만
  존재한다.
- 현재 테스트 검색 결과: `appointment-api` 38개, `appointment-core` 4개,
  `appointment-event` 14개, `appointment-notification` 3개,
  `appointment-solver` 1개의 fixture 파일과 Gatling 1개 파일에 직접 연결 설정이
  있다. 이 수치는 파일 단위 inventory이며 occurrence 수가 아니다.
- `gno search`의 관련 collection 검색은 결정적 결과를 반환하지 않았고, live
  `gh issue view`로 Issue #223 본문·상태·metadata를 확인했다.

## 설계 선택

### 권장안: 내부 공용 factory로 registration/lifecycle 계약 단일화

`appointment-api` production source에 `ExposedDatabaseFactory`와
`ExposedDatabaseLifecycle`을 추가한다. factory는
주입받은 `javax.sql.DataSource`로만 `Database.connect(dataSource)`를 실행하고,
공용 `ReentrantLock` 안에서 기존 `TransactionManager.defaultDatabase`를 저장한 뒤
항상 `finally`에서 복원한다. `ServiceConfig`와
`ProfileReevaluationConfiguration`은 각자의 lock/중복 코드를 제거하고 factory를
호출한다. 각 configuration은 자신이 생성한 `Database` bean 이름에만 lifecycle bean을
연결하여 context 종료 시 `TransactionManager.closeAndUnregister(database)`를
호출한다.

이 factory는 `DataSource`를 생성하거나 닫지 않는다. Spring이 pool의 생성·설정·종료를
소유하며, 업무 코드는 선택된 `Database`로 명시적 `transaction(database) {...}`
경계를 사용한다. 현재 runtime에 단일 Spring `DataSource`만 존재하므로 qualifier를
새로 발명하지 않는다. 이후 둘 이상의 pool을 등록하는 경로는 bean name 또는
`@Qualifier`를 factory 호출부에서 명시해야 한다는 규칙을 문서와 audit에 남긴다.

### 검토했지만 채택하지 않은 대안

1. 두 설정 bean의 현재 구현을 그대로 두고 wiring test만 추가한다. 운영 동작은
   보존되지만 registration lock과 복원 계약이 중복되어 두 runtime 경로가 달라질
   위험을 남긴다.
2. Exposed Spring Boot starter 대신 전역 `Database` 자동설정을 새로 만든다.
   starter와 역할이 겹치고 Spring Boot lifecycle의 소유권을 다시 정의하게 되므로
   새 자동설정·pool·dependency를 추가하지 않는다.
3. 모든 test/migration/Gatling fixture를 Spring context로 전환한다. 명시적 dialect,
   독립 schema, load runner의 목적과 실행 비용을 훼손한다. 해당 파일은 allowlist로
   분류하고 lifecycle 규칙만 검증한다.

## 구성 요소와 데이터 흐름

```text
Spring Boot DataSource bean
        │ constructor/parameter injection
        ▼
ExposedDatabaseFactory.connect(dataSource)
        │ Database.connect(dataSource)
        │ lock + previous default restore
        ▼
Feature Database bean ──► repository/service transaction(database)
```

factory 생성 중에만 Exposed 전역 default를 잠그고 복원한다. factory가 반환한
`Database`는 injected `DataSource`를 통해 connection을 획득하며, factory나
request/service 코드는 `DataSource` 또는 connection을 닫지 않는다. context 종료 시
등록된 Exposed manager를 먼저 해제하고, Spring context 종료가 pool close를 담당한다.

## 변경 대상

### Production

- `appointment-api/.../config/ExposedDatabaseFactory.kt` 신규: 내부 factory와
  Korean KDoc
- `appointment-api/.../config/ExposedDatabaseLifecycle.kt` 신규: factory handle의
  context destroy/unregister 경계
- `appointment-api/.../config/ServiceConfig.kt`: factory 사용, 중복 lock 제거
- `appointment-api/.../config/ProfileReevaluationConfiguration.kt`: factory 사용,
  중복 lock 제거

### Tests

- `AppointmentCommitmentApplicationWiringTest.kt`:
  Hikari-backed Spring `DataSource`를 주입하고 자동 생성된 `Database`를 검증
- `ProfileReevaluationWiringTest.kt`: 같은 방식으로 profile `Database` wiring 검증
- `NotificationReminderRecoveryWiringTest.kt`: 직접 `Database` fixture를
  Hikari-backed `DataSource` fixture로 교체
- `ExposedDatabaseFactoryTest.kt` 신규: injected pool marker query와 default
  database 복원, concurrent registration, repeated acquisition, manager cleanup을
  검증
- repository static audit test 신규 또는 기존 compliance test 확장: production
  source에 직접 URL/Hikari/`Database.connect`가 다시 생기지 않는지 검증

### Documentation

- `docs/runbooks/spring-managed-exposed-datasource.ko.md`: ownership, qualifier,
  standalone allowlist, 변경 시 점검 명령
- `docs/lessons/2026-08-05-issue-223-spring-managed-exposed-datasource.md`:
  재사용 가능한 결정·검증·future guard
- 승인된 설계와 실행 plan은 이 디렉터리의 `superpowers` 문서에 보존한다.

## 분류 및 allowlist

| 분류 | 현재 예 | 처리 |
|---|---|---|
| Spring runtime | `ServiceConfig`, `ProfileReevaluationConfiguration` | 공용 factory로 표준화 |
| Spring context wiring test | 세 wiring test | Hikari-backed `DataSource` 주입으로 전환 |
| Shared migration support | `*MigrationTestSupport`의 `DataSource` 인자와 `Database.connect(dataSource)` | 이미 datasource 경계를 사용하므로 유지, fixture 소유권 문서화 |
| Standalone unit/integration test | core/event/notification/solver H2 fixture | Spring 강제 전환하지 않고 allowlist에 기록 |
| Migration/dialect fixture | `SimpleDriverDataSource`, `DriverManager` | 명시적 backend/schema 검증 목적이므로 유지, close 규칙 확인 |
| Gatling/load fixture | `VisitCommitmentGatlingFixture`, load tests | Spring context가 없으므로 유지, pool close와 URL 범위 기록 |

allowlist는 파일과 분류 이유를 포함한다. 새로운 production direct setup은 audit
test를 통과하지 못하며, standalone fixture 추가는 allowlist와 lifecycle 설명을
동시에 갱신해야 한다.

## 실패 모드와 완화

1. 두 Spring configuration이 동시에 factory를 호출하면서 전역 default가 서로
   덮어쓴다. 공용 lock과 `finally` 복원, concurrent factory test로 완화한다.
2. 잘못된 `DataSource` qualifier로 다른 tenant/pool에 연결된다. 현재 단일 pool
   evidence를 유지하고, multi-pool 추가 시 명시적 qualifier와 marker query wiring
   test를 요구한다.
3. factory가 injected pool 대신 URL로 새 connection을 만든다. Hikari pool에만
   존재하는 marker table을 만든 뒤 반환된 `Database` transaction에서 조회하여
   검증한다.
4. 테스트 context가 Hikari pool을 닫지 않아 connection leak가 발생하거나 Exposed
   manager가 stale registry에 남는다. context destroy에서 manager를 해제하고 pool
   `close` ownership을 검증하며, 독립 fixture는 테스트가 생성한 resource만 닫는다.
5. Exposed global default 복원 누락으로 다른 테스트의 `transaction {}`가 잘못된
   DB를 사용한다. sentinel default를 설정한 factory test와 wiring test isolation으로
   회귀를 막는다.

## 호환성·마이그레이션

- database schema, tenant isolation, Exposed transaction API, dependency catalog는
  변경하지 않는다.
- context 종료 시 factory가 등록한 Exposed manager만 해제하며, Spring이 소유한
  `DataSource` close 순서와 pool 소유권은 변경하지 않는다.
- production runtime의 bean 조건과 feature flags는 유지한다.
- 기존 standalone fixture는 동작을 보존하고, 문서화·audit만 추가한다.
- 두 production wiring test는 직접 `Database` bean 공급을 Spring `DataSource` 공급으로
  바꾸고, notification wiring test는 동일한 factory를 호출하는 전용 test configuration을
  통해 Hikari pool 경계를 확인한다. 세 테스트 모두 실제 context 종료/marker path를
  검증한다.
- rollback은 두 configuration의 lifecycle bean 제거, lifecycle/factory 파일 삭제,
  이전 inline block 복원 순서로 제한되며, schema/data migration rollback은 필요하지
  않다. 부분 context 실패 시 context close와 manager unregister 및 pool close를 먼저
  확인한다.

## 수용 기준

1. production source에 direct Hikari/JDBC URL/`Database.connect(url, ...)`가 없다.
2. 두 runtime configuration이 공용 factory를 사용하고 previous default database를
   복원한다.
3. wiring test가 injected Hikari pool에 기록한 marker를 생성된 Exposed handle로
   읽고 context가 정상 시작하며, 종료 후 Hikari pool이 닫히고 Exposed manager가
   registry에서 해제된다.
4. 독립 fixture, migration, dialect, Gatling 파일은 분류 이유와 pool/connection
   ownership이 allowlist/runbook에 기록된다.
5. static inventory와 targeted module tests가 위 계약을 재현하며, `git diff --check`
   와 Kotlin checklist가 PASS한다.
6. Issue #39 tenant isolation 및 관련 transaction boundary에는 변경이 없다.

## 설계 DoD

- [ ] 설계 문서 self-review에서 placeholder·모순·범위 누락이 없다.
- [ ] implementation plan이 모든 수용 기준을 파일·테스트·명령으로 연결한다.
- [ ] production/test 변경은 TDD RED→GREEN으로 증명한다.
- [ ] P0/P1 finding이 0이고, PR/CI 단계는 별도 delivery authority를 따른다.
