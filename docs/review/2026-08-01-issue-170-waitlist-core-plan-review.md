# Issue #170 대기 목록 코어 구현 계획 검토

## 검토 범위

- 검토 대상 계획: `docs/superpowers/plans/2026-08-01-issue-170-waitlist-core.md`
- 계획 검토 기준 commit: `a2e67d5317ee0b2c1af7c69aa0fc8a7c7ba8a571`
- 기준 설계 commit: `1f9388b8fcd6ac49545e66a03d031fd252657770`
- Step 2-R 설계 검토: `docs/review/2026-08-01-issue-170-waitlist-core-spec-review-iteration-2.md` (`2abd0e7f43ac2f3f7beabb0d51bbe43ef9e44d0e`)
- 운영 runbook 기준 commit: `02896e6013859b1c6c2c5958393c2d264e746b2c`

계획과 설계를 commit-qualified로 읽고, 여섯 관점의 독립 검토 결과를 통합했다.
검토는 읽기 전용이며 production source, migration, endpoint를 변경하지 않았다.

## 여섯 관점 결과

| 관점 | 판정 | P0 | P1 | P2 | P3 | 핵심 근거 |
|---|---|---:|---:|---:|---:|---|
| 성능 | PASS | 0 | 0 | 0 | 0 | candidate/hold 인덱스 방향, PostgreSQL/MySQL `EXPLAIN`, page당 batch 1회, keyset budget, 100-way contention이 Task 4/7/8/12에 고정됐다. |
| 안정성 | PASS | 0 | 0 | 0 | 0 | resource mutex, capacity validation, FK-safe offer/hold 생성 예외, CAS/rollback/reconcile과 fail-closed risk gate가 Task 6/9/10 및 위험 표에 있다. |
| 보안 | PASS | 0 | 0 | 0 | 0 | actor opaque/HMAC, correlation allowlist, raw profile 금지, parameterized runtime query, malicious-input/log capture 검증이 Task 1/11과 runbook에 있다. |
| 운영 | PASS | 0 | 0 | 0 | 0 | V1→V18 readiness, flag-off→migration-only→allowlist→fake-clock rollback, p95/p99 budget, bounded reconcile, no-delete rollback이 Task 4/12와 runbook에 있다. |
| 개발자/API | PASS | 0 | 0 | 0 | 0 | `bluetape4k-states` compile probe, facade parity, bounded result contract, caller-owned replacement idempotency bridge와 out-of-scope 경계가 Task 0/2/10에 있다. |
| 사용자/호출자 | PASS | 0 | 0 | 0 | 0 | `CandidateFound`, `OfferClaimed`, release/expiry, consume 결과별 caller action, `replacementCommandId`·`holdId` handoff matrix가 Task 10에 있다. |

## 통합 판정

- 통합 verdict: `PASS`
- P0: `0`
- P1: `0`
- P2: `0`
- P3: `0`
- A-04 계획 리뷰의 ordering/proof/ownership/hazard blocker는 없다.
- A-05 위험 예측은 계획의 `실행 위험과 중단 기준` 표에 dependency, lock/order,
  migration, CAS, PII, performance, launcher, dirty-worktree 신호·완화·중단점으로
  기록되어 있다.

## 게이트 상태

계획 문서는 저장·검토 완료됐지만, 구현은 이 계획에 대한 사용자의 명시적 승인
이후에만 시작한다. 따라서 현재 단계의 미실행 항목은 구현, migration runtime,
contention/load, full module test이며 이는 결함이 아니라 다음 실행 게이트다.
