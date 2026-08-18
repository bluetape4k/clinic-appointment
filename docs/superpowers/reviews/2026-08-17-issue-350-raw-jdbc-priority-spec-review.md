# Issue #350 Step 2-R 설계 검토 통합 기록

## 검토 범위와 판정

- 대상 설계: `docs/superpowers/specs/2026-08-17-issue-350-raw-jdbc-priority-design.md`
- 기준: `origin/develop` (`f0c7614beed766efc4b88a1a59aa5c370f8fccf7`)
- 검토 대상: 전체 source set 38개 파일, direct JDBC marker 334줄의 분류·전환
  경계, Issue #309와의 책임 분리, PostgreSQL Testcontainers 계약, benchmark
  증거, 문서·회귀·정적 검증 수용 기준
- 실행 방식: 최초 performance/stability/security 네이티브 lane은 bounded
  probe 후 응답 증거 없이 중단되어 liveness contract에 따라 한 번씩
  `main-session fallback`으로 대체했다. 원 lane의 timeout·probe·interrupt와
  replacement lineage는 workflow receipt에 남아 있다. 네이티브 결과를
  성공으로 추정하지 않았다.
- 최신 문서에 대해 main session이 여섯 관점과 통합 검토를 수행했으며,
  성능·안정성·보안에서 발견한 P2 설계 보강을 반영한 뒤 재검토했다.

## 여섯 관점 결과

| 우선순위 | 관점 | 근거 | 조치 | 재검토 |
|---|---|---|---|---|
| P0/P1 없음 | Performance | 설계의 트랜잭션 우선·round-trip 보존·benchmark 경계가 현재 `benchmark/appointment-messaging-benchmark/src/main/kotlin/io/bluetape4k/clinic/appointment/benchmark/PostgreSqlBenchmarkFixture.kt`와 맞는다. 저장소에 실제 존재하는 `mainSmokeBenchmark`, `mainBenchmark`, report collector/validator를 수용 기준에 명시했다. | 새로운 threshold/SLO를 추론하지 않고 기존 task 성공과 validator 결과만 구현 단계 증거로 사용한다. | PASS |
| P0/P1 없음 | Stability | `exec`의 enclosing transaction, PostgreSQL lock/query-plan, Testcontainers singleton과 Hikari/DataSource 종료·반납을 실패 모드·구현 순서·수용 기준에 연결했다. `appointment-api/src/test/.../AbstractApiIntegrationTest.kt`의 JVM shutdown/singleton lifecycle 설명과도 일치한다. | bounded Gradle command와 종료 로그/회귀 검증을 구현 단계에 고정했다. | PASS |
| P0/P1 없음 | Security | 값은 바인딩하고, `EXPLAIN` wrapper·테이블/스키마·정렬 키 같은 SQL fragment는 고정 상수 또는 검증 allowlist만 사용하도록 명시했다. 이는 `PatientCancellationHistoryQueryPlanTest.kt`의 SQL/parameter 경계와 `AppointmentOutboxQueryPlanTest.kt`의 고정 PostgreSQL query 패턴을 보존한다. | 사용자/request 값을 SQL identifier로 전달하지 않는 negative/static 검증을 수용 기준에 추가했다. | PASS |
| P0/P1 없음 | Operator/Ops | raw JDBC 유지 allowlist에 파일·심볼·책임·대체하지 않은 이유·검증 명령을 요구하고, benchmark report 경로와 rollback을 명시했다. 외부 deployment/SLO 증거는 이 예제 범위가 아니며 요구하지 않는다. | allowlist와 fresh command output을 구현/PR 단계의 운영 증거로 고정했다. | PASS |
| P0/P1 없음 | Developer/API | Exposed `transaction {}` 안의 DSL/`TransactionManager.current().exec(...)` 우선 순서, 기존 Bluetape helper 재사용 조건, public API와 Issue #309 repository/transaction 책임 보존이 명확하다. | 새 공통 abstraction·dependency를 만들지 않고 파일/호출 묶음 단위로 전환한다. | PASS |
| P0/P1 없음 | User/Caller | README/KDoc/lesson에 MIGRATE·ADAPTER·ALLOWED-BOUNDARY 규칙과 unsupported boundary를 기록하고, H2는 unit/wiring 보조, PostgreSQL은 production-schema Testcontainers 기준으로 구분한다. | 호출자가 raw JDBC를 새로 추가할 때 allowlist 근거와 검증 명령을 함께 요구한다. | PASS |

## 통합 검토

| 통합 항목 | 판정 | 확인 내용 |
|---|---|---|
| 경계·대안 | PASS | raw JDBC를 기계적으로 제거하지 않고 Exposed transaction 내부와 framework/benchmark 경계를 분리했다. PostgreSQL 전용 lock/CTE/EXPLAIN은 의미 보존을 위해 `exec`를 허용한다. |
| 실패 모드 | PASS | transaction context 누락, 문자열 보간, dialect drift, lifecycle leak, H2-only pass, Issue #309 scope creep를 명시하고 대응을 연결했다. |
| 검증 가능성 | PASS | 38파일/334 marker inventory, allowlist 정적 검사, 영향 모듈 build/test, PostgreSQL Testcontainers, benchmark smoke/full 및 기존 collector/validator, `git diff --check`를 수용 기준에 포함했다. |
| 문서·언어 | PASS | repository-local Korean artifact policy를 따르고 코드·명령·식별자·URL은 보존했다. 설계 writer gate SPW-01..05를 read-back으로 재확인했다. |
| 호환·롤백 | PASS | public API, repository signature, schema/migration 순서와 SQL semantics를 보존하고 파일 묶음 단위 rollback을 정의했다. |
| 운영 증거 범위 | N/A/명시 | 실제 production 배포·SLO 증거는 이 예제의 범위가 아니다. Testcontainers로 PostgreSQL production-schema를 시뮬레이션하는 증거만 요구한다. |

## 최신 보강 사항

초기 fallback 검토에서 확인한 세 가지 P2 설계 공백을 다음과 같이 수정했다.

1. benchmark smoke/full task와 기존 report collector/validator를 설계·수용
   기준에 명시했다.
2. dynamic SQL fragment와 값 바인딩의 보안 경계를 고정 상수/allowlist와
   bound value로 분리했다.
3. Testcontainers singleton, Hikari/DataSource, benchmark connection의
   종료·반납 lifecycle을 bounded command와 회귀 검증으로 추가했다.

수정 후 재검토 결과 P0=0, P1=0, 미해결 P2=0, P3=0이다. 실제 Kotlin
컴파일·Gradle·PostgreSQL 실행 결과는 구현 단계에서 fresh evidence로 수집하며,
설계 승인 전에는 production source를 수정하지 않는다.

## 결론

**Step 2-R 설계 검토: PASS — P0=0, P1=0, P2=0, P3=0.**

네이티브 lane timeout은 성공 증거가 아니며 workflow receipt에 보존되어 있다.
main-session fallback과 통합 검토로 최신 설계 문서의 미해결 blocker를
제거했다. 다음 gate는 이 설계 문서에 대한 사용자의 written review/approval이며,
그 전에는 구현 계획과 production source 변경을 시작하지 않는다.
