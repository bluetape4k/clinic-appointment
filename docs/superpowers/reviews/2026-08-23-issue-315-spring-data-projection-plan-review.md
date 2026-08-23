# Issue #315 구현 계획 통합 검토

## 검토 범위와 기준

승인된 test-only pilot 계획과 위험 원장을 여섯 관점으로 재검토했다.
검토 대상은 PartTree repository 계약, adapter 호출 경계, 전역 Exposed
transaction/registry lifecycle, H2·PostgreSQL fixture와 timeout, 대칭 benchmark와
chart, raw evidence redaction, runtime artifact 경계다. 구현 전 gate의 판정은
P0/P1 결함이 없어야 하며, 운영 검토 대기 조건은 계획과 결과 문서에 명시되어야 한다.

## 관점별 결과

| 관점 | 확인한 핵심 | 결과 | 후속 조건 |
|---|---|---|---|
| Developer/API | `@ExposedEntity`, `ExposedJdbcRepository`, `table=Clinics`, nullable `extractId`, `EntityID` predicate, 전용 package scan, `transactionManagerRef`와 adapter의 `Long` 경계를 확인했다. 상속 CRUD는 adapter에서 호출하지 않는다. | PASS | 구현에서 upstream contract compile/read-back을 통과해야 한다. |
| User/Caller | 기존 `ClinicRepository`와 API를 바꾸지 않고 `ClinicRecord` 결과·ID 오름차순·tenant A/B/unknown/0/negative 입력 계약을 고정한다. tenant predicate는 authz 대체가 아니며 pagination은 범위 밖이다. | PASS | production adoption 시 `TenantClinicAccessChecker`와 authenticated principal 검증을 별도 수행한다. |
| Performance | 4/32/128 cardinality, 5 warm-up/30 sample, legacy/candidate 순서 교대, 동일 total timing, median/p95, representative statement count, PostgreSQL `EXPLAIN`, mandatory chart와 process deadline을 확인했다. | PASS | `poolConcurrency = NOT_TESTED`이면 운영 채택을 보류한다. |
| Security | typed `EntityID` binding, unique PostgreSQL schema, public schema 비변경, sanitization 후 atomic artifact 이동, `gitleaks --no-git --config .gitleaks.toml`, exact fresh bootJar/runtimeClasspath 검사를 확인했다. | PASS | pilot은 인증 경계를 의도적으로 포함하지 않는다. |
| Stability/Operability | resource lock, manager/DataSource 단일성, test-owned handle tracker와 synthetic 이중 등록, worker cancel/join, schema owner/admin DROP, pool active 0, `pg_namespace` residual read-back, suppressed cleanup 예외를 확인했다. | PASS | worker가 deadline 안에 끝나지 않거나 late connect가 발생하면 gate를 실패시킨다. |
| Reviewer/Verifier | spec acceptance와 계획 traceability, rollback/stop conditions, sanitized raw evidence, Korean artifact audit, `git diff --check`, module/runtime validation 명령을 확인했다. | PASS | 구현 후 step-5 verifier checklist와 step-6r six-lane diff review를 다시 실행한다. |

## 결함 집계

| 심각도 | 건수 | 판정 |
|---|---:|---|
| P0 | 0 | 차단 결함 없음 |
| P1 | 0 | 구현 gate 진입 가능 |
| P2 | 0 | 계획상 운영 공백 없음 |
| P3 | 0 | 비차단 잔여 결함 없음 |

이전 라운드에서 발견된 결함은 다음과 같이 계획에 반영했다.

- 순서 편향: measured sample과 warm-up에 `LEGACY_FIRST`/`CANDIDATE_FIRST`
  교대를 적용했다.
- evidence fail-open: `PIPESTATUS`, process deadline, `mktemp` 원본,
  sanitization/secret scan 성공 후 `mv`, `mv || exit 1`, `test -s`를 적용했다.
- shared PostgreSQL 오염: pilot 고유 schema와 schema owner/admin cleanup으로
  제한하고 `public` schema와 residual state를 read-back한다.
- registry 누수: 단일 manager/DataSource invariant, test-owned handle tracker,
  synthetic 이중 등록, worker cancel/join과 late-connect fail-closed를 고정했다.
- chart/운영 판단: chart 산출물을 필수로 만들고 PostgreSQL 또는 pool 동시성
  증거가 없으면 결과를 `PENDING`/`Not-tested`로 유지한다.

## 추적성

| acceptance | 계획 위치 |
|---|---|
| 동일 결과·정렬·tenant 입력 | Task 2, Task 3, 요구사항 추적표 |
| transaction/connection/manager | Task 3 Step 2–4 |
| 단일 SELECT/N+1 방지 | Task 3 Step 5 |
| H2/PostgreSQL capability/cleanup | Task 2 Step 3, Task 3 Step 3, Task 4 Step 3 |
| 대칭 성능·chart | Task 4 Step 1, Step 4 |
| runtime boundary | Task 5 Step 1 |
| adoption 보류/rollback | Task 4 Step 5, Rollback과 중단 조건 |

## Gate verdict

**PASS — 구현을 시작한다.**

이 검토는 test-only pilot의 구현 진입을 승인한다. 이는 production adoption
승인이 아니다. 구현 결과에서 PostgreSQL `EXPLAIN`, sanitized raw evidence,
chart, runtime artifact 경계, pool 동시성 `NOT_TESTED` 표기 중 하나라도 누락되면
기존 Table DSL을 유지하고 Issue #315를 보류한다.
