# Issue #170 대기 목록 코어 설계 검토

검토 대상: `docs/superpowers/specs/2026-08-01-issue-170-waitlist-core-design.md`

검토 기준: Type-A Step 2-R, 6개 관점(성능·안정성·보안·운영·개발자/API·사용자/호출자)과 main-session 통합 검토

검토 기준 커밋: `5d8386b88652e2168745d51189441d4cd7425bc0`의 실제 worktree 상태

## 관점별 결과

| 관점 | 실행 경로 | 결과 | 근거 |
|---|---|---|---|
| 성능 | native `code-reviewer` | P0=0, P1=2, P2=1, P3=0 | 후보 decision N+1, keyset/index 불일치, 2-connection만 있는 부하 기준 |
| 안정성 | native `verifier` | P0=1, P1=2, P2=2, P3=1 | claim 이후 replacement 전 capacity lost update, offer 생성 경쟁, vacancy 재사용 차단 |
| 보안 | native `code-reviewer` fallback lane | P0=0, P1=2, P2=3, P3=0 | offer scope 강제와 decision scope 검증 누락, actor/PII/parameterization 경계 |
| 운영/Ops | native `architect` | P0=0, P1=2, P2=3, P3=0 | 만료 backlog 회복, FSM/V18 rollout gate, metrics/health/ownership/runbook 누락 |
| 개발자/API | main-session 독립 검토 | P0=0, P1=2, P2=2, P3=0 | `bluetape4k-states` 실제 API/artifact 미검증, offer 상태 계약 불일치 |
| 사용자/호출자 | main-session 독립 검토 | P0=0, P1=0, P2=2, P3=1 | bounded 결과의 호출 예시·stale decision 구분·재시도 설명 부족 |

## 통합 findings

| 우선순위 | 근거 | 필요한 수정 | 재검토 |
|---|---|---|---|
| P0 | §8.2, §10, §14: claim이 capacity를 재확인한 뒤 `ACCEPTED`를 commit하고 실제 replacement 생성은 후속으로 미룬다. | `OfferClaimed`를 capacity 예약/hold와 같은 transaction에서 확정하도록 바꾼다. 권장안은 `ResourceAllocationRepository`가 인식하는 durable capacity-hold를 같은 transaction에서 생성하고, replacement 생성 전까지 점유로 취급하며, 만료·철회·실패 복구와 release 규칙을 명시하는 것이다. hold를 만들 수 없다면 claim을 `ACCEPTED`가 아닌 `CLAIMED/HOLD_PENDING`으로 분리해야 한다. | 안정성 + 아키텍처 + 구현 동시성 검토 |
| P1 | §8.2/§10: `WaitlistOffers` 컬럼에는 `status`가 없지만 claim은 `status = OFFERED`를 갱신하고 current state는 entry가 보유한다고 서술한다. | offer 상태의 권위를 하나로 고정한다. 권장안은 `WaitlistOffers.status`를 명시하고 entry/offer 상태를 한 transaction에서 일치시키는 것이다. CAS 조건·history·migration·테스트를 같은 상태 정의에 맞춘다. | 개발자/API + 안정성 |
| P1 | §8.2/§10: offer에는 tenant/clinic 컬럼이 없는데 ID 기반 읽기·수정과 scope predicate를 모두 요구한다. | 모든 offer 조회/수정은 entry join + `forUpdate`로 scope를 검증하거나, offer에 tenant/clinic을 denormalize하고 복합 FK/검증을 둔다. wrong-tenant/clinic 동일 offer ID negative test를 추가한다. | 보안 + repository |
| P1 | §9.2/§10: 최신 reliability decision 조회가 정확한 tenant·clinic·member scope와 반환 stamp scope 일치를 요구하지 않는다. | provider 조회 key와 반환 검증에 `(tenantGroupId, clinicId, memberId)`를 고정하고 mismatch/stale를 fail closed로 명시한다. cross-member/cross-clinic negative test를 추가한다. | 보안 + candidate/claim |
| P1 | §9.2/§9.3: candidate page마다 최신 decision을 한 후보씩 읽을 수 있어 N+1과 unbounded page scan이 가능하다. | page 단위 batch provider 또는 SQL join/window 계약, page당 statement 상한, 100-candidate mostly-excluded acceptance를 명시한다. | 성능 |
| P1 | §8.1/§9.3/§12: `waitingSince`는 물리 컬럼으로 정의되지 않았고, 인덱스가 `(-slotFit,-priorityRank,waitingSince,entryId)` cursor를 지원하지 않는다. | `waitingSince` 저장 컬럼과 canonical keyset predicate를 명시하고 세 dialect 인덱스/EXPLAIN 기준을 추가한다. | 성능 |
| P1 | §8.2/§12: 전체 `vacancy_key` unique는 DECLINED/EXPIRED/WITHDRAWN 뒤 같은 빈자리를 재사용하지 못하게 한다. | active offer/hold에만 uniqueness를 적용하는 portable 설계 또는 명시적 active-vacancy/상태 구조를 선택하고 migration matrix로 검증한다. | 안정성 + migration |
| P1 | §9/§10: 두 matcher가 같은 WAITING entry/vacancy를 동시에 선택할 때 offer 생성 transaction, lock/CAS 순서, unique conflict retry가 없다. | `selectAndOffer` transaction을 추가하여 deterministic read → WAITING CAS/lock → offer insert → history insert → bounded retry/mapping을 고정한다. | 안정성 |
| P1 | §7/§14: `bluetape4k-states`의 실제 artifact resolution과 DSL symbol이 아직 검증되지 않았다. | 구현 전 catalog alias, dependency resolution, 실제 DSL compile probe를 plan gate로 추가하고 실패 시 local FSM 복제가 아닌 release-train block으로 종료한다. | 개발자/API |
| P1 | §12/§14: V18과 기존 FSM 전환에 대한 readiness/rollout/rollback hold가 없다. | dependency·schema readiness, feature enable 순서, FSM regression rollback, migration failure/forward-only recovery를 release gate로 명시한다. | 운영/Ops |
| P1 | §8.2/§9.1: expiry worker가 후속인데 claim 시점 외에는 EXPIRED offer를 회수할 경로가 없다. | phase-one core에서 DB-now 기반 bounded expiry sweep을 offer 생성/claim 전에 수행하거나, active uniqueness가 만료 row를 제외하도록 설계하고 backlog limit/triage를 명시한다. | 운영/Ops |
| P2 | §10: entry/offer → reliability → resource/appointment의 전역 lock order가 없다. | lock order와 non-locking read를 명시하고 opposing-order deadlock test를 추가한다. | 안정성 |
| P2 | §10/§11: stale/digest-changed decision은 거부만 있고 offer state/history 결과가 없다. | `OFFERED` 유지·review 상태·terminal 전이 중 하나를 선택하고 bounded result/history를 추가한다. | 안정성 + 사용자 |
| P2 | §10/§13: concurrency acceptance가 2개 connection만 검증한다. | bounded pool, N concurrent claims, p95, zero deadlock/unexpected SQL, stable conflict count 기준을 추가한다. | 성능 |
| P2 | §6.2/§8.3/§11/§13: `actor_ref`와 metric/exception/history redaction이 충분히 구체적이지 않다. | opaque bounded actor ID와 charset/length 검증, email/phone/JWT/raw principal 금지, metric/exception/history redaction negative test를 명시한다. | 보안 |
| P2 | §9.1/§9.3: Exposed parameterized predicate 사용이 명시되지 않았다. | 문자열 SQL 조합 금지와 typed Exposed expression/prepared parameter invariant를 추가한다. | 보안 + 개발자/API |
| P2 | §11/후속 경계: bounded 결과와 member ID handoff의 호출 예시, stale decision 구분, 재시도 오용 방지가 부족하다. | KDoc/예시에서 결과별 caller action과 후속 API idempotency 경계를 명시한다. | 사용자/호출자 |
| P2 | §12/§13/§14: metrics, health/readiness, ownership matrix, migration/expiry/recovery runbook이 없다. | bounded operational metrics, readiness check, transition/result owner, `docs/runbooks/waitlist-core.md`를 산출물과 acceptance에 포함한다. | 운영/Ops |
| P3 | §13: cross-offer same-vacancy 및 claim-vs-expiry/withdraw race acceptance가 없다. | 구현 전 test-spec에 추가한다. | 안정성/테스트 |

## 통합 판정

- 최신 통합 결과: `NEEDS REVIEW SCOPE`
- P0: `1`
- P1: `10` (중복 관점 findings를 통합한 수)
- P2: `8`
- P3: `1`
- 구현/plan gate: 차단
- 다음 조치: P0/P1을 반영한 설계 수정본을 작성하고, capacity hold·offer 상태 권위·scope·cursor/index·offer creation concurrency·rollout 경계를 다시 사용자에게 승인받은 뒤 Step 2-R 전체 관점을 재실행한다.

이번 검토에서는 저장소 구현 파일을 수정하지 않았다. 리뷰 artifact 자체와 checklist/receipt 기록만 다음 gate evidence로 사용한다.
