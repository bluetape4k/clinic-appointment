# PR #202 clinic permit registry 수명주기 명세 2-R 검토

검토일: 2026-08-07
대상 PR: [#202](https://github.com/bluetape4k/clinic-appointment/pull/202)
대상 exact head: `1baad5cfeb092792c7ae92eac79d51f465972fad`
검토 단계: Type A Step 2-R (명세)

## 근거

- 명세: `docs/superpowers/specs/2026-07-31-issue-201-clinic-permit-registry-lifecycle-design.md`
- 관련 계획: `docs/superpowers/plans/2026-07-31-issue-201-clinic-permit-registry-lifecycle-plan.md`
- lesson: `docs/lessons/2026-07-31-coroutine-keyed-resource-lifecycle.md`

`gh pr view 202 --json headRefOid,mergeCommit`로 live metadata를 재확인했다. `headRefOid`는
`1baad5cfeb092792c7ae92eac79d51f465972fad`이고, 이슈 댓글의 `f10e2c2...`는 merge commit이다.
따라서 이 artifact는 merge SHA가 아니라 PR head SHA를 exact-head 기준으로 사용한다.

| 관점 | 판단 | P0/P1 |
|---|---|---:|
| 성능 | active holder/waiter 수에 비례하는 registry와 process-local 상한 | 0/0 |
| 안정성 | `compute` 원자성, `finally` release, cancellation 경로가 명시됨 | 0/0 |
| 보안·개인정보 | key는 tenant/clinic scope이며 환자·예약 식별자를 사용하지 않음 | 0/0 |
| 운영 | TTL/sweeper 없이 즉시 수렴, low-cardinality metrics, 분산 제한은 비목표 | 0/0 |
| 개발자/API | 기존 dispatcher contract를 유지하고 registry boundary만 교체 | 0/0 |
| 사용자·호출자 | 병원별 concurrency 상한과 cross-clinic fairness를 보존 | 0/0 |

## 통합

holder와 waiter를 모두 참조 수에 포함하지 않으면 서로 다른 semaphore가 겹칠 수 있다는 위험과, `compute` 내 생성·증가·감소·제거라는 해결책이 일관된다. 명세 변경은 필요하지 않다.

## 판정

**Retrospective Step 2-R assessment: PASS — P0=0, P1=0; historical independent gate: NOT PROVEN.**
