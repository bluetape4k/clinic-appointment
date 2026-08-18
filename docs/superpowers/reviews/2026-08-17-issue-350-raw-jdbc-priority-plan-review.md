# Issue #350 구현 계획 검토 통합 기록

## 검토 범위와 판정

- 대상 계획: `docs/superpowers/plans/2026-08-17-issue-350-raw-jdbc-priority-plan.md`
- 기준 설계: `docs/superpowers/specs/2026-08-17-issue-350-raw-jdbc-priority-design.md`
- 설계 검토: `docs/superpowers/reviews/2026-08-17-issue-350-raw-jdbc-priority-spec-review.md`
- 기준 브랜치: `origin/develop` (`f0c7614beed766efc4b88a1a59aa5c370f8fccf7`)
- 대상 inventory: 38개 파일, 334 marker line
- 검토 방식: 계획을 작업별 파일·명령·기대 결과·커밋 경계로 read-back하고
  성능·안정성·보안·운영·개발자/API·사용자/호출자 여섯 관점으로 통합 검토했다.
  네이티브 spec lane timeout은 성공 증거로 취급하지 않았으며, 이전 spec
  review의 main-session fallback 판정을 그대로 승계했다.

## 계획-설계 추적

| 설계 요구 | 계획 위치 | 판정 |
|---|---|---|
| `MIGRATE`/`ADAPTER`/`ALLOWED-BOUNDARY` 호출 분류 | 파일 구조 표, 작업 1·3 | PASS |
| Exposed DSL → `TransactionManager.current().exec` → 정확한 helper 우선 | 작업 2 | PASS |
| Exposed transaction 밖 Flyway/readiness/Gatling/benchmark 경계 보존 | 파일 구조 표, 작업 4·5·6 | PASS |
| Issue #309 repository/transaction ownership 분리 | 설계·작업 2·중단 조건 | PASS |
| PostgreSQL Testcontainers singleton, H2 보조 계약, `@Testcontainers` 금지 | 작업 5·6·중단 조건 | PASS |
| SQL 값 binding과 고정/검증된 fragment | 작업 1·2·3·6 | PASS |
| benchmark smoke/full 및 기존 collector/validator | 작업 6 | PASS |
| README/KDoc/lesson과 rollback/DoD | 작업 7·8 | PASS |

## 여섯 관점 결과

| 우선순위 | 관점 | 검토 근거 | 판정 |
|---|---|---|---|
| P0/P1 없음 | Performance | MIGRATE 대상은 metadata lookup과 H2 DDL 두 호출로 제한하고, Gatling lock-wait 계측·query-plan·benchmark measurement는 경계로 보존한다. benchmark threshold/SLO를 새로 만들지 않으며 기존 smoke/full task와 validator만 증거로 사용한다. | PASS |
| P0/P1 없음 | Stability | enclosing transaction 참여, Hikari/DataSource owner, singleton launcher, contention executor 종료, bounded Gradle 명령과 failure stop condition을 작업별로 고정했다. `exec` 전환으로 connection pool 수명을 바꾸지 않는다. | PASS |
| P0/P1 없음 | Security | `?` binding, fixed SQL fragment, fixed schema identifier, 문자열 보간 negative test, validator self-exclusion을 명시했다. `EXPLAIN` wrapper와 사용자 입력 identifier를 혼동하지 않는다. | PASS |
| P0/P1 없음 | Operator/Ops | 38개 manifest, JSON report, fresh PostgreSQL/Testcontainers evidence, report collector/validator, production SLO 비주장, remote branch 보존과 rebase merge gate를 기록했다. | PASS |
| P0/P1 없음 | Developer/API | 변경 파일과 심볼, 정확한 Gradle/Node 명령, RED→GREEN 순서, 커밋 경계, public API/repository signature 보존이 명확하다. 새 dependency나 공통 abstraction을 추가하지 않는다. | PASS |
| P0/P1 없음 | User/Caller | README/lesson/KDoc에 호출자 관점의 raw JDBC 허용 경계와 H2/PostgreSQL 역할을 기록하고, merge approval 전 merge하지 않는 전달 절차를 보존한다. | PASS |

## 구현 위험과 통제

1. `StatementType.CREATE` 또는 `VarCharColumnType`의 실제 import/API가 모듈
   compile에서 달라질 수 있다. 작업 2의 `compileTestKotlin`/`compileGatlingKotlin`
   를 source 수정 직후 첫 검증으로 실행하고, 실패하면 기존 Exposed 1.4.0
   local usage를 기준으로 최소 import만 조정한다.
2. Node scanner는 import/URL/예외 타입과 실제 resource call을 구분해야 한다.
   작업 1의 RED fixture와 작업 3의 self-test를 먼저 통과시키며, broad regex로
   38개 파일을 무차별 허용하지 않는다.
3. `PatientAppointmentCancelPostgresFixture.sampleLockWaits`는 transaction
   block 안에 있지만 Gatling 진단 SQL이라는 책임 경계가 명확하다. 이를
   MIGRATE로 바꾸면 load-generation 계측 의미가 흔들리므로 allowlist와
   고정 query/close 검증을 유지한다.
4. `appointment-core/src/test/.../TestDB.kt`는 자체 JUnit test class가 아니므로
   `--tests '*TestDB*'`를 사용하지 않는다. 계획은 실제 존재하는
   `PostgreSqlOnlyContractTest`와 `TableSchemaTest`를 targeted command로
   고정했다.

## 통합 판정

| 항목 | 판정 | 확인 내용 |
|---|---|---|
| 범위 | PASS | direct JDBC를 기계적으로 삭제하지 않고, 정확히 두 transaction-owned 호출만 우선 전환하며 38개 전체의 잔여 경계를 기록한다. |
| 순서 | PASS | inventory RED → source GREEN → allowlist/read-back → 모듈 회귀 → PostgreSQL/benchmark → 문서/전체 검증 → PR/merge 순서다. |
| 검증 가능성 | PASS | 각 단계에 파일, 명령, 기대 출력, 실패 중단 조건, 커밋 경계가 있다. |
| 롤백 | PASS | public API·schema·repository ownership을 건드리지 않고 파일/커밋 묶음 단위로 되돌릴 수 있다. |
| 운영 증거 | N/A/명시 | 실제 production 배포/SLO 증거는 요구하지 않고 Testcontainers PostgreSQL production-schema simulation과 기존 benchmark validator만 요구한다. |
| 계획 품질 | PASS | placeholder 검색, `git diff --check`, 실제 task/class 이름 read-back을 반영했다. |

## 결론

**Step 3-R 구현 계획 검토: PASS — P0=0, P1=0, P2=0, P3=0.**

계획 승인 전에는 production Kotlin/Java source를 수정하지 않는다. 다음 gate는
사용자의 구현 계획 승인이다. 승인 후 작업 1의 RED 테스트부터 시작하고, 각 작업의
검증 결과를 fresh output으로 기록한다.
