# Issue #39 구현 계획 리뷰 기록

## 검토 대상

- 설계서: `docs/superpowers/specs/2026-08-04-issue-39-tenant-query-isolation-design.md`
- 구현 계획: `docs/superpowers/plans/2026-08-04-issue-39-tenant-query-isolation-plan.md`
- 기준 branch: `feat/issue-39-tenant-isolation`
- 기준 base: `origin/develop` `48476dac93d0bed083b114afc1b77e92137615b1`
- 설계 커밋: `1ccf601cd2e4dda8e94406620e16a86ac54e677e`
- 계획 커밋: `f337d22` (리뷰 중 안정성·테스트 경로 보강은 후속 커밋으로 반영)

## Step 3-R 결과

| 우선순위 | 관점 | 계획 근거 | 통합 결과 |
|---|---|---|---|
| P1 | performance | slot/solver/closure query delta, direct index column order, EXPLAIN, cache max/TTL, large fixture | P0=0/P1=0. Task 2·4·6·7·9에 측정·검증 명령이 있음 |
| P1 | stability | SSE per-appointment CAS/transaction, virtual-thread handle, cancellation, direct lease recovery, V21 rollback | P0=0/P1=0. `CancellationException` 재전파와 direct lease-expiry 테스트를 계획에 명시 |
| P1 | security | shared scope, positive IDs, claimed-row tuple guard, zero/mismatch side-effect 없음, no default tenant | P0=0/P1=0. repository/event/direct/cache negative tests와 #38 제외를 확인 |
| P1 | developer/API | current symbol/file ownership, source-breaking overload migration, Exposed transaction, shared scope reuse, exact tests | P0=0/P1=0. nonexistent caller 가정을 제거하고 existing optimistic predicate 검증으로 고정 |
| P1 | operator/ops | three dialect V21, nullable rolling writer, preflight/orphan/EXPLAIN, readiness/PAUSED rollback, MySQL recovery | P0=0/P1=0. sequential real-DB checks와 Korean runbook 경로가 있음 |
| P1 | user/caller | Korean KDoc, paired README parity, named scope examples, canary bridge/YAML, bounded SSE behavior | P0=0/P1=0. public caller migration과 unsupported compatibility를 문서 task에 고정 |

## Native lane timeout 및 fallback

첫 번째 native wave(performance/stability/security)와 두 번째 wave(developer/ops/user)는 각각 5분 bounded deadline 안에 mailbox 결과를 반환하지 않았다. 각 lane은 `interrupt_agent` 후 workflow receipt에서 cancel 처리했고, production/doc write는 수행하지 않았다. 동일한 six-lens 검토를 main session이 exact spec/plan/current symbols 기준으로 재실행해 위 표의 결과를 만들었다.

이 fallback은 native review 결과를 성공으로 위장하지 않는다. timeout은 운영 증거로 남기고, 현재 run에서는 six lens를 `main-session fallback` evidence로 별도 완료한다. 다음 구현 단계의 code review에서는 native timeout을 재시도하지 않고, main verifier가 각 lane의 fresh test evidence를 요구한다.

## 통합 판단

- 모든 spec acceptance criterion이 Task 1–9와 연결된다.
- repository, scheduling, solver, event-notification, docs-delivery write ownership이 분리되어 있고 Task 9에서 main이 통합한다.
- Kotlin/Exposed transaction, cancellation propagation, virtual-thread cleanup, Testcontainers 금지, 세 dialect migration, README/KDoc language policy를 계획에 반영했다.
- `AppointmentDomainEvent`와 notification은 `TenantClinicScope`를 공통 authority로 재사용하며 기존 `TenantGroupId`/`ClinicId`는 boundary adapter로만 남긴다.
- P0=0, P1=0. 남은 gate는 이 reviewed plan에 대한 repository owner의 written approval이며, 그 전에는 production code를 수정하지 않는다.
