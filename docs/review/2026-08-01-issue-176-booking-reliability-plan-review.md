# Issue #176 구현 계획 Step 3-R 검토

검토 대상:

- 계획: `docs/superpowers/plans/2026-08-01-issue-176-booking-reliability-plan.md`
- 기준 명세: `docs/superpowers/specs/2026-08-01-issue-176-booking-reliability-design.md`
- 저장소 기준: `appointment-core`, `appointment-event`, `appointment-api`의 policy/commitment/security/migration/query-plan/retention 구현

검토 방식: performance, stability, security, operator/Ops, developer/API, user/caller의 6개 독립 관점으로 두 파도 검토를 실행했다. 첫 파도에서 발견된 P0/P1을 계획에 반영한 뒤, native thread 한도 때문에 영향을 받은 lane의 재소환은 main-session read-only fallback으로 수행했다. 리뷰어는 파일을 수정하거나 커밋하지 않았고, 실제 DB/container 검증은 구현 단계로 남겼다.

## 1. 초기 독립 리뷰 결과와 계획 수정

| 우선순위 | 관점 | 근거 | 계획 수정 | 재검증 |
|---|---|---|---|---|
| P1 | Performance | 대규모 clinic의 member lookback/latest decision/audit가 H2 단위 테스트만으로는 full scan을 막지 못함 | Task 4에 PostgreSQL/MySQL `EXPLAIN`, 예상 index, `LIMIT 100/32`, full-scan 부재를 검증하는 `BookingReliabilityQueryPlanTest` 추가 | main performance fallback PASS |
| P1 | Stability | worker cursor/lease/retry가 process 재시작 뒤 보존될 durable owner가 없음 | Task 3에 `booking_reliability_reevaluation_jobs`, job repository, DB-time lease/fencing/checkpoint/dead-letter 추가; Task 7에 lease-loss/restart 테스트 추가 | main stability fallback PASS |
| P1 | Security | 기존 generic tenant matcher가 새 route-specific capability를 보장하지 않음 | Task 5에 `SecurityConfig` 선행 matcher, read/audit/write capability 분리, wrong clinic/scope integration test 추가 | main security fallback PASS |
| P1 | Operator/Ops | canary·metric·retention·readiness가 문서 설명만으로 통과할 수 있음 | Task 7에 metric contract, p95/p99, closed tags, canary readiness, schema gate, retention service/runner, worker retry/backpressure와 증거 템플릿 추가 | main Ops fallback PASS |
| P0 | Developer/API | final eligibility 재검증이 commitment transaction 밖에 구현될 수 있어 TOCTOU와 allocation 잔존 위험 | Task 6에 idempotency claim 후 allocation/commitment CAS 직전 같은 Exposed transaction 내 재검증, rollback/TOCTOU 테스트, 외부 I/O post-commit 경계 추가 | main API integration PASS |
| P1 | Developer/API | policy service 경로가 실제 `service` package와 불일치 | Task 1 파일 경로를 실제 `appointment-core/.../service`로 교정 | main API integration PASS |
| P1 | Developer/API | event dedupe key와 source version correction semantics가 모호함 | `(tenant, clinic, member, eventId, sourceVersion)` identity와 event/decision/override index 이름을 Task 3–4에 고정 | main API integration PASS |
| P1 | Developer/API | 장기 HTTP contract의 status/header/schema/OpenAPI 증거가 없음 | Task 5에 status mapping, `Idempotency-Key`, `If-Match`/digest precondition, OpenAPI/docs-as-contract 테스트 추가 | main API integration PASS |
| P1 | Developer/API | V17 dialect migration 실행 명령이 추상적임 | 기존 `FlywayMigrationTest`, `FlywayMySQLMigrationTest`, `FlywayPostgreSQLMigrationTest`와 support를 명시하고 순차 실행 | main API integration PASS |
| P1 | User/Caller | override/clear 재시도·stale 화면을 막는 precondition이 없음 | Task 5에 idempotency + strong precondition, duplicate retry/stale conflict 테스트와 API 문서 추가 | main user fallback PASS |
| P1 | User/Caller | legacy schemaVersion 1의 threshold 동작이 caller 관점에서 예측 불가 | 누락 threshold는 `thresholdsPresent=false` legacy compatibility와 `POLICY_DISABLED`, 새 write는 schemaVersion 2로 고정 | main user fallback PASS |
| P2 | Developer/API/User | bounded trigger/cursor와 audit pagination 필드·정렬·limit이 불명확 | `hasAdditionalTriggers`, `auditCursor`, opaque cursor, default 32/max 100, stable ordering/filter를 Task 2/5에 고정 | main integration PASS |
| P2 | User/Caller | README만으로는 endpoint source-equivalence가 보장되지 않음 | `docs/api/booking-reliability.md`, OpenAPI/API documentation test, locale parity command 추가 | main integration PASS |
| P2 | Stability | shared DB/container test가 worktree 간 경합할 수 있음 | singleton launcher, `@ResourceLock(API_INTEGRATION_RESOURCE, READ_WRITE)`, `AfterAll`, 순차 실행을 Task 4/9에 고정 | main stability fallback PASS |

초기 finding은 P0 1건, P1 13건, P2 8건, P3 1건이었다. 각 finding에 대한 수정 후 영향을 받은 관점은 main-session fallback으로 현재 계획을 다시 읽어 확인했다.

## 2. Main-session 통합 점검

| 점검 | 현재 계획 근거 | 결과 |
|---|---|---|
| 모든 명세 DoD/수용 기준의 task·파일·테스트 매핑 | `명세·수용 기준 추적성` 표와 Task 1–9 | PASS |
| 의존 순서와 migration 선행 조건 | Task 3 persistence → Task 4 V17/query plan → Task 5 API → Task 6 commitment → Task 7 ops → Task 8 docs → Task 9 verification | PASS |
| transaction/TOCTOU 경계 | Task 6 step 2, `BookingEligibilityTransactionBoundaryTest` | PASS |
| event/decision/override/job idempotency | Task 3 steps 1–3, Task 4 step 1, named unique indexes | PASS |
| HTTP/security/strict deserialization | Task 5 steps 4–8, `SecurityConfig`, OpenAPI/security/docs tests | PASS |
| worker/retention/readiness/rollback | Task 3 job repository, Task 7 worker/retention/schema readiness, OFF/SHADOW/ENFORCE | PASS |
| multi-dialect 및 shared-container 안정성 | Task 4 Flyway test classes, query-plan EXPLAIN, resource lock/cleanup | PASS |
| 문서·README·visual parity | Task 8 canonical docs, API examples, locale existence check, HTML+PNG/SVG+PNG requirements | PASS |
| 리뷰 증거 무결성 | 계획·명세는 feature worktree에 있고 `git diff --check`가 fresh PASS | PASS |

## 3. 최종 판정

- P0: **0**
- P1: **0**
- P2: **0 미해결** — 초기 P2는 모두 계획에 반영했다.
- P3: **0 미해결** — 초기 경로·문서 parity 지적을 반영했다.
- 재검증되지 않은 native lane: thread 한도 때문에 별도 재소환하지 않고 main-session fallback으로 동일 범위를 다시 읽었다.

**Step 3-R verdict: PASS.** 계획 승인 전에는 production code, migration, README, diagram을 작성하지 않는다. 이 계획과 승인된 명세를 커밋한 뒤, 사용자로부터 이 구현 계획에 대한 별도 승인을 받아야 Step 4 TDD mutation으로 이동한다.
