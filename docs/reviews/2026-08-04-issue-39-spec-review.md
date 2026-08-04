# Issue #39 설계 리뷰 기록

## 검토 대상

- 설계서: `docs/superpowers/specs/2026-08-04-issue-39-tenant-query-isolation-design.md`
- 기준 branch: `feat/issue-39-tenant-isolation`
- 기준 base: `origin/develop` `48476dac93d0bed083b114afc1b77e92137615b1`
- 검토 범위: performance, stability, security, operator/ops, developer/API, user/caller

## 초기 결과와 반영

| 우선순위 | 관점 | 근거 | 반영 결과 |
|---|---|---|---|
| P1 | performance | slot/closure hot loop와 solver load에 tenant 전용 query budget이 없음 | slot·solver query 증분 0, closure top-level 최대 1로 고정하고 loop 내부 tenant query 금지 |
| P1 | performance | direct claim index가 실제 predicate/order column과 불일치 | tenant 선두 신규 rolling-safe index의 정확한 column 순서와 dialect `EXPLAIN` 고정 |
| P1 | performance | V21 backfill/index lock 전략 부재 | maintenance preflight, row/orphan count, `EXPLAIN`, dispatch hold와 recovery runbook 추가 |
| P1 | security | direct claim이 clinic-only이며 claimed row scope 재검증이 없음 | tenant predicate, claimed-row guard, typed route/eligibility, zero/mismatch side-effect 없음 고정 |
| P1 | security | missing/zero legacy event 처리 불명확 | in-process `ApplicationEvent` 비전송 계약과 양수 scope 생성 검증, zero 보정 금지 명시 |
| P1 | stability | single V21 `NOT NULL`이 구버전 writer를 깨뜨림 | V21 nullable additive/backfill/FK/new index, old index 유지, post-drain 재backfill과 별도 hardening hold로 변경 |
| P1 | stability | SSE disconnect/concurrency가 partial PENDING·중복 candidate를 만들 수 있음 | 예약 단위 CAS+history+candidate 원자 transaction, bounded thread cancellation, 재호출 semantics 추가 |
| P2 | stability | event log 실패가 commit 완료 API를 실패처럼 보이게 함 | event log를 best-effort audit로 분류하고 bounded metric, durable outbox 책임 분리 |
| P2 | stability | solver fact와 original map이 다른 snapshot | 한 transaction snapshot과 result version recheck 계약 추가 |
| P2 | developer/API | candidate read/confirm/auto와 rollout config가 clinic-only | 모든 reschedule public API scope화, typed canary config와 DB tuple eligibility 추가 |
| P2 | developer/API | cache key grammar와 solver caller 경계 불명확 | `${tenantGroupId}:${clinicId}` grammar와 collision test, standalone solver 계약 명시 |
| P2 | operator/ops | readiness, rollback, metric, MySQL partial-DDL recovery가 불명확 | V21 readiness, schema-down 없는 pause rollback, low-cardinality metric, 한국어 runbook 추가 |
| P2 | user/caller | source migration·KDoc·README/YAML 예제가 구체적이지 않음 | 모듈별 문서 matrix와 named scope 예제, dual-config migration 내용을 고정 |

## 독립성 및 timeout 처리

- performance, security, stability는 독립 native review 결과를 사용했다.
- developer/API lane은 5분 deadline 전에 핵심 근거를 반환했고, deadline 후 main-session fallback이 caller inventory를 완결했다.
- operator/ops lane은 deadline을 넘겨 중단했으며, main-session fallback이 live readiness/index/runbook 근거로 검토했다.
- automated user/caller lane은 두 번 deadline을 넘겨 중단했다. main-session fallback으로 문서 matrix를 보강했으며, 실제 사용자인 repository owner의 written-spec 승인을 최종 caller gate로 사용한다.

## 재검증

| 관점 | P0 | P1 | 상태 |
|---|---:|---:|---|
| performance | 0 | 0 | query delta·정확한 index·migration 실행 계약 재검증 완료 |
| security | 0 | 0 | tenant claim guard·zero/mismatch event 거부 계약 재검증 완료 |
| stability | 0 | 0 | rolling-safe V21·SSE lifecycle/concurrency 계약 재검증 완료 |
| operator/ops | 0 | 0 | main-session fallback 완료 |
| developer/API | 0 | 0 | bounded findings + main-session fallback 완료 |
| user/caller | 0 | pending | 문서 matrix 검토 완료, repository owner written-spec 승인 대기 |

## 현재 결론

performance/security/stability의 최신 spec rerun은 모두 `P0=0, P1=0`으로 종료했다. 남은 구현 시작 조건은 repository owner가 최신 written spec을 승인하는 것이다. 그 전에는 implementation plan이나 production code를 작성하지 않는다.
