# Spring-managed Exposed DataSource 운영 규칙

Issue #223의 목적은 Spring이 생성·설정·종료하는 HikariCP `DataSource`를 Exposed
`Database` handle이 재사용하도록 연결 경계를 고정하는 것이다. `ExposedDatabaseFactory`는
주입받은 `DataSource`로 handle만 등록하고, 등록 중 바뀐
`TransactionManager.defaultDatabase`를 복원한다. Spring context가 닫힐 때
`ExposedDatabaseLifecycle`이 factory 소유 handle의 Exposed manager를 해제한 뒤
Spring이 pool을 닫는다.

## 운영 규칙

1. Spring이 `DataSource` 생성, pool 설정, secret/property 바인딩, shutdown을 소유한다.
2. API/service/repository 코드는 pool을 생성하거나 닫지 않는다. 트랜잭션은 선택한
   `Database`를 명시해 `transaction(database) { ... }`로 연다.
3. 현재 runtime은 단일 `DataSource`다. 두 개 이상의 pool을 추가할 때는 bean name 또는
   `@Qualifier`를 factory 호출부에 명시하고, 각 pool의 고유 marker를 읽는 wiring test를
   함께 추가한다. 이름 없는 `DataSource` 주입으로 tenant/pool을 추측하지 않는다.
4. factory가 만든 handle에만 lifecycle bean을 연결한다. 외부에서 제공한 `Database`를
   임의로 `closeAndUnregister`하지 않는다.
5. 직접 만든 standalone fixture는 자신이 만든 connection/pool만 닫는다. Spring bean을
   주입받은 테스트는 동일 인스턴스를 수동으로 닫지 않고 context 종료를 검증한다.

## 현재 allowlist

| 분류 | 대표 파일/범위 | Spring 전환을 하지 않는 이유 | resource owner/close 규칙 |
|---|---|---|---|
| Spring runtime | `appointment-api/src/main/.../ServiceConfig.kt`, `ProfileReevaluationConfiguration.kt` | 실제 application context의 bean graph | Spring `DataSource`; factory handle은 lifecycle bean이 manager를 해제 |
| Spring wiring test | `appointment-api/src/test/.../*WiringTest.kt` | context 조건/feature flag를 검증해야 함 | 테스트가 Hikari를 supplier로 만들고 context가 종료; marker query와 `isClosed` 검증 |
| Shared migration support | `appointment-api/src/test/.../migration/*MigrationTestSupport.kt` | migration helper가 `DataSource`를 인자로 받고 backend별 setup을 제어 | 호출자가 주입한 `DataSource`; helper는 Exposed handle만 사용 |
| Standalone unit/integration | `appointment-core/src/test`, `appointment-event/src/test`, `appointment-notification/src/test`, `appointment-solver/src/test` | Spring context 없이 domain/SQL 격리를 빠르게 검증 | fixture가 만든 H2 connection/pool을 fixture 종료 시 닫음 |
| Migration/dialect/query-plan | `appointment-api/src/test/.../migration`, `.../integration/*Dialect*`, `.../integration/*QueryPlan*` | vendor-specific DDL/plan과 실제 backend를 명시적으로 검증 | `SimpleDriverDataSource`/`DriverManager` 소유자가 각 테스트의 cleanup 수행 |
| Gatling/load | `appointment-api/src/gatling/.../VisitCommitmentGatlingFixture.kt` 및 load tests | Spring application context와 분리된 부하 실행 경로 | load fixture가 만든 H2/pool을 scenario 종료 시 닫음 |

새 direct setup을 추가할 때는 이 표의 정확한 파일/분류/owner/close 방법을 먼저
갱신하고, production source라면 factory 경계를 우회하지 않는다.

## 점검 명령

저장소 루트에서 다음을 순서대로 실행한다.

```bash
./gradlew :appointment-api:test --tests '*DataSourceOwnershipContractTest' --no-build-cache
rg -n 'Database\.connect|HikariDataSource|SimpleDriverDataSource|DriverManager\.getConnection|jdbc:' appointment-*/src/main appointment-*/src/test appointment-api/src/gatling
git diff --check
```

`appointment-*/src/main`의 `Database.connect(`는
`ExposedDatabaseFactory.kt` 한 곳만 허용한다. `src/test`와 `src/gatling` 결과는 위
allowlist와 대조한다. `appointment-api/src/main/resources/application*.yml`의
`spring.datasource` URL은 Spring 설정 입력이며 이 Kotlin/Java direct-setup guard의
대상이 아니다. 배포 환경은 profile별 secret/config 공급과 TLS 정책을 별도로 검토해야
하며, 이 문서는 그 credential 정책을 완화하지 않는다.
