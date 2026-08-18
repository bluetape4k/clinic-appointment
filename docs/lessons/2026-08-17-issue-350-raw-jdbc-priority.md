# Issue #350 raw JDBC 우선 경계와 검증 기록

## 결정

Exposed transaction 안의 direct JDBC는 Exposed DSL 또는
`TransactionManager.current().exec(...)`를 우선한다. 다만 Flyway `DataSource`,
Spring readiness metadata, Gatling 진단 fixture, benchmark schema·pool lifecycle처럼
애플리케이션 transaction 밖에서 소유권이 분명한 호출은 raw JDBC allowlist에 남긴다.
모든 호출을 기계적으로 삭제하거나 새로운 JDBC abstraction을 추가하지 않는다.

PostgreSQL은 production-schema를 Testcontainers로 재현하는 기준이다. H2는 빠른
unit/wiring 및 fixture 보조 역할만 맡는다. 이 예제의 acceptance에는 production 배포
SLO나 외부 운영 endpoint 증거를 포함하지 않는다.

## 기준 inventory와 RED

초기 inventory는 38개 파일과 direct JDBC marker 334줄이었다. 첫 validator 실행은
다음 네 가지를 의도적으로 RED로 보고했다.

| 파일·심볼 | RED marker | 원인 |
|---|---|---|
| `VisitCommitmentCommandTestSupport.tableReadable` | `TransactionManager.current().connection` | Exposed transaction이 이미 소유한 connection을 직접 cast해 metadata cursor를 열었다. |
| `VisitCommitmentGatlingFixture.createSchedulingOutboxEventsTable` | `TransactionManager.current().connection`, `prepareStatement`, `executeUpdate` | H2 DDL이 현재 transaction을 우회해 statement를 만들었다. |

두 호출만 기능 의미를 보존하는 최소 전환 대상으로 삼고, 나머지는 책임 경계를
allowlist로 고정했다.

## 최종 분류

| 분류 | 파일 수 | 기준 |
|---|---:|---|
| `MIGRATE` | 2 | Exposed transaction 내부에서 `exec`/binding으로 대체 가능한 호출 |
| `ADAPTER` | 9 | migration, query-plan, benchmark adapter처럼 외부 계약을 연결하는 호출 |
| `ALLOWED-BOUNDARY` | 29 | Flyway/DataSource, readiness, Gatling 진단, schema/pool lifecycle 등 owner가 명확한 경계 |

최종 manifest는 `scripts/raw-jdbc-allowlist.json`에 파일, 심볼, owner, 유지 이유,
거부한 대안, 검증 명령과 허용 marker를 함께 기록한다. 새로운 direct JDBC는
manifest 근거와 validator read-back 없이 추가하지 않는다.

## 구현 결과

- `VisitCommitmentCommandTestSupport.tableReadable`는 고정
  `information_schema.tables` SQL과 `VarCharColumnType() to candidate` binding을
  사용한다.
- `VisitCommitmentGatlingFixture.createSchedulingOutboxEventsTable`는 별도
  `prepareStatement` 없이 current transaction의
  `exec(..., explicitStatementType = StatementType.CREATE)`를 사용한다.
- Spring readiness probe는 Spring-managed `DataSource`가 connection lifecycle을
  소유하는 startup boundary로 문서화했다.
- source guard의 JDBC 문자열은 실행 호출이 아니라 ownership assertion으로
  분리해 inventory false positive를 막았다.

## Fresh evidence

최종 validator는 14개 source root의 Kotlin/Java 954개 파일을 스캔했다.

```json
{
  "allowlistedFileCount": 38,
  "directMarkerCount": 336,
  "boundaryMarkerCount": 236,
  "violations": [],
  "staleEntries": [],
  "bindingViolations": [],
  "ok": true
}
```

검증 명령과 결과:

| 명령 | 결과 |
|---|---|
| `node --test scripts/validate-appointment-raw-jdbc-inventory.test.mjs` | 4 tests passed |
| `./gradlew :appointment-api:compileTestKotlin :appointment-api:compileGatlingKotlin --no-build-cache --no-daemon --console=plain` | `BUILD SUCCESSFUL` |
| API commitment load/wiring targeted tests | 5 tests passed |
| API ownership/wiring contract tests | 18 tests passed |
| API migration/query-plan tests | 4 tests passed |
| `./gradlew :appointment-core:test --tests '*PostgreSqlOnlyContractTest' --tests '*TableSchemaTest' --no-build-cache --no-daemon --console=plain` | 14 tests passed; H2/PostgreSQL 모두 통과 |
| `./gradlew :appointment-messaging:test --tests '*AppointmentMessagingAutoConfigurationTest' --tests '*AppointmentMessagingReadinessValidatorTest' --no-build-cache --no-daemon --console=plain` | 13 tests passed |
| `./gradlew :appointment-api:build -Dspring.profiles.active=test,test-postgresql --no-build-cache --no-daemon --console=plain` | `BUILD SUCCESSFUL`; API 801 tests (1 skipped) and Kover verification passed |
| `./gradlew :appointment-core:test :appointment-messaging:test --no-build-cache --no-daemon --console=plain` | `BUILD SUCCESSFUL`; core 549 tests and messaging 115 tests passed |
| `node scripts/validate-appointment-raw-jdbc-inventory.mjs --json` | 위 JSON과 동일한 GREEN |
| `./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark --no-daemon --console=plain` | PostgreSQL `postgres:18-alpine` smoke 성공; Hikari shutdown completed |
| `node scripts/collect-appointment-messaging-benchmark.mjs ... --config smoke` + `node scripts/validate-appointment-messaging-benchmark.mjs ...` | `build/reports/appointment-messaging-postgresql/benchmark.json` 생성·검증 성공; `deploymentSloEvidence=false` |

로컬 Docker 증거는 `colima status`, `docker context show`, `docker info`로 확인했다.
Colima는 `running`, Docker context는 `default`, server는 `28.4.0`이었다. Context 실행기의
Gradle worker가 `DOCKER_HOST` 환경을 제거하는 경로에서는 Testcontainers가 실패했지만,
관리된 셸 환경을 보존한 동일 Gradle 명령에서는 `PostgreSQLServer.Launcher.postgres`
singleton으로 core 14개와 messaging 13개가 모두 통과했다. 정상 Colima를 재시작하지
않았고, 임시 probe/helper 파일도 제거했다.

## 재현·회귀 규칙

1. Exposed transaction 안에 direct JDBC를 추가하기 전에 DSL/`exec` 대체 가능성을
   검토한다.
2. 대체할 수 없는 경계는 manifest에 owner·이유·거부 대안·검증 명령을 추가한다.
3. `node --test ...`와 validator JSON에서 `violations`, `staleEntries`,
   `bindingViolations`가 모두 빈 배열인지 확인한다.
4. PostgreSQL 의미가 필요한 schema, readiness, query-plan, load 테스트는
   `PostgreSQLServer` singleton으로 실행한다. H2만 통과하도록 SQL branch를
   약화시키지 않는다.

## Rollback과 남은 범위

기능 전환만 되돌릴 때는 `0757b563`를, inventory·설계 산출물까지 함께 되돌릴 때는
`dfba7c80`부터 이후 Issue #350 커밋을 순서대로 revert한다. allowlist를 지우는
대신 기존 경계를 보존해야 한다.

이번 작업은 production deployment/SLO 증명을 요구하지 않는다. smoke benchmark의
`p50=p95=p99=0.02398874585123208 ops/ms`는 PostgreSQL Testcontainers 재현 결과일 뿐
배포 SLO가 아니다. CI와 동일한 PostgreSQL 프로파일의 API 전체 build와 core/messaging
전체 test까지 통과했으며, source 전환의 correctness는 위의 Testcontainers, compile,
wiring, query-plan, static evidence로 고정한다.
