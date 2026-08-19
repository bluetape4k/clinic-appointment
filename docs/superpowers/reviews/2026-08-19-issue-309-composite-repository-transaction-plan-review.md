# Issue #309 구현 계획 6면 검토

## 결론

- 대상: `docs/superpowers/plans/2026-08-19-issue-309-composite-repository-transaction-plan.md`
- 기준 설계: Issue #309 승인 설계안 2와 설계 검토 CLEAR
- 결과: **CLEAR — 구현 시작 가능**
- P0: 0건
- P1: 0건
- P2: 2건(실행 중 fixture 명칭·실제 Gradle task output을 기록해야 함)

## 독립 관점 결과

| 관점 | 점검 결과 | 판정 |
| --- | --- | --- |
| 요구사항·수용 기준 | 세 `LONG-JDBC`, Composite/append 보존, Spring annotation, H2/PG, 문서·CI DoD가 task와 매핑됨 | 통과 |
| 아키텍처·의존성 | DAO 전환을 피하고 실제 `LongJdbcRepository` generic 계약을 적용하며, repository가 transaction을 열지 않음 | 통과 |
| 보안·무결성 | tenant scope/idempotency/rollback/replay와 외부 IO 분리, append API의 ID 의미 보존 | 통과 |
| 성능·동시성 | custom batch/lock/`SKIP LOCKED` 보존, singleton PG 직렬화, 거대 transaction 금지 | 통과 |
| 운영·검증 | RED→GREEN, core→api 순서, H2 wiring과 PG lock suite 분리, diff/static/liveness 증적 | 통과 |
| 사용자 API·문서 | public method/bean 호환, KDoc·lesson·Korean artifact 정책, Lore commit·PR read-back 포함 | 통과 |

## P2 정밀화 항목

1. PostgreSQL singleton launcher와 `withTables`의 실제 helper 이름은 Task 6 실행 직전에
   소스에서 확인하고 review artifact에 명시한다. 계획은 helper를 추측해 고정하지 않는다.
2. Spring Boot 4 proxy의 suspend method 지원 여부는 Task 4 targeted test에서 fresh
   output으로 확인한다. 실패하면 non-suspend `create`와 metadata 계약으로 범위를
   축소하고 status/cancel annotation은 추가하지 않는다.

## 단계·의존성 검토

- Task 1 기준선 → Task 2 RED → Task 3 repository GREEN → Task 4 transaction
  GREEN → Task 5 inventory/doc → Task 6 full verification → Task 7 delivery 순서가
  단방향이며, 각 단계의 rollback 지점이 있다.
- Task 4가 Task 3에 의존하지만 repository mapper compile이 먼저 필요하므로
  core→api 모듈 검증 순서가 올바르다.
- Task 7은 fresh CI와 explicit merge approval 전에는 merge하지 않는 stop condition을
  갖는다.

## 검토 gate

- 3R plan review: requirements traceability 통과, risk/rollback 통과, test-first
  sequencing 통과
- SPW-01..05: 통과(목적·근거·결정·검증·독립 검토)
- KO-01..07: 통과(한국어 문서, 식별자·명령 보존, 표 병렬성, diff check)
- Type-A A-01..A-12: 계획에 baseline, design/plan review, implementation,
  verification, documentation, delivery evidence가 모두 존재함

최종 판정: **CLEAR**.

