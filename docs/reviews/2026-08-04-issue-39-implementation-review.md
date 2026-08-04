# Issue #39 구현 리뷰

## 리뷰 범위

`TenantClinicScope`를 기준으로 scheduling, solver, closure reschedule, event,
notification 및 V21 migration에 적용한 구현을 현재 worktree와 최신 테스트
증거로 재검토했다. 이 문서는 계획 리뷰의 후속 구현 리뷰이며, public GitHub
문서가 아니라 한국어 내부 기록이다.

## 판정 요약

| 관점 | 판정 | 근거 |
|---|---|---|
| 요구사항/계약 | PASS | Issue #39의 공통 scope, 명시적 caller contract, #38 JWT와의 경계가 유지됨 |
| 정확성/격리 | PASS | repository/API/slot/solver/closure/event/direct notification의 tenant+clinic predicate와 cross-tenant 회귀 테스트 |
| 동시성/부작용 | PASS | CAS/history/claim fence, claimed-row scope 재확인, provider 호출 전 route gate |
| 성능 | PASS (범위 한정) | tenant-leading index와 PostgreSQL/MySQL EXPLAIN 2건, query-budget 코드 검토; 독립 statement counter는 없음 |
| 운영/복구 | PASS | nullable rolling V21, readiness null/orphan/mismatch preflight, PAUSED rollback, bounded reason-code metrics |
| API/문서 | PASS | 다섯 모듈 English/Korean README pair, Korean KDoc/runbook, scoped list API와 internal recovery boundary |

P0=0, P1=0이다. P2 수준의 남은 항목은 외부 DB launcher가 필요한 전체 테스트와
독립 SQL statement counter이며 구현 실패로 분류하지 않는다.

## 주요 수정 확인

1. `TenantClinicScope`는 양수 tenant/clinic ID와 canonical cache key를 갖고,
   인증 객체나 thread-local context 대신 DB ownership authority로 전달된다.
2. doctor/equipment/treatment-type 목록 paging도 repository 내부에서
   `clinic_id`와 tenant clinic subquery를 함께 생성한다. controller의 선행
   `verifyClinic`만으로 격리를 보장하지 않는 경로를 제거했다.
3. solver snapshot, closure candidate 저장/confirm/auto, event log payload/row,
   direct claim/permit/worker/canary 및 expired lease recovery가 같은 scope를
   사용한다. recovery 후보도 route allowlist를 SQL 단계부터 적용한다.
4. `NotificationEventListener`의 생략된 route gate 기본값은 주입된
   `properties.rollout`을 사용한다. `recoverExpiredOnce`는 production route가
   아닌 module-internal diagnostic API로 닫아 unscoped public recovery를 남기지
   않는다.
5. V21 H2/PostgreSQL/MySQL migration은 nullable rolling column, clinic join
   backfill, FK/index를 추가하고 기존 index를 보존한다. readiness와 runbook은
   null, orphan, clinic/tenant mismatch를 unresolved로 취급한다.

## 최신 검증 증거

- `:appointment-core:compileKotlin :appointment-api:compileKotlin :appointment-notification:compileKotlin --no-build-cache` — BUILD SUCCESSFUL.
- `TenantGuardRepositoryTest` — H2/PostgreSQL/MySQL 3건 통과.
- `NotificationOutboxRepositoryTest` — 23건 통과.
- `NotificationOutboxDispatcherTest` — 7건 통과.
- `NotificationSchemaReadinessTest` + `NotificationAutoConfigurationTest` — 18건 통과.
- API controller/migration targeted suite — 43건 통과.
- V21 dialect/Flyway integration — H2/PostgreSQL/MySQL lifecycle 3건 + Flyway 1건 통과.
- `NotificationOutboxQueryPlanTest` — PostgreSQL/MySQL 2건 통과. 두 dialect 모두
  `idx_notification_outbox_tenant_direct_lookup`를 선택했고 full-table scan이
  없었다.
- `git diff --check` — 통과.
- Kotlin 변경 diff의 신규 `!!`, `runCatching`, `println`, `System.out/err`
  정적 스캔 — 위험 라인 없음.

## 남은 검증 한계와 후속 조치

- 기본 core/solver/event/notification 전체 실행은 외부 PostgreSQL/MySQL
  launcher unavailable로 waitlist schema connection failure가 발생했다.
- API 전체 실행은 300초 context wrapper timeout으로 완료 증거를 만들지 못했다.
- slot/solver query delta 0은 독립 SQL counter가 아니라 scoped predicate 코드
  검토와 targeted/full-available 테스트로 확인했다. 별도 counter는 후속 성능
  issue로 분리한다.
- merge 전 CI에서 외부 DB 전체 suite와 query-plan lane을 재실행하고, exact PR
  head/CI/review 상태를 다시 확인해야 한다.

## 최종 결론

현재 구현은 Issue #39 acceptance 범위에서 P0/P1 blocker 없이 PASS다. 위 환경
제약은 CI 재검증 항목으로 남기며, PR 생성 후 fresh CI evidence가 확보되기 전에는
merge 상태로 주장하지 않는다.
