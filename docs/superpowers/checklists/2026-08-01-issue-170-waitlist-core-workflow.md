# 이슈 #170 대기 목록 핵심 워크플로 체크리스트

상태: `PRE-PR VERIFIED` — PR/merge는 범위에 포함하지 않음

## 분류

- 작업 유형: `Type-A / Full Feature`
- 근거: 새로운 생태계 의존성, 다층 도메인/영속성 동작, 데이터베이스
  마이그레이션, 동시성/CAS 의미론, 향후 공개 통합 경계가 포함됨.
- 필수: 모든 `WF-*`, `CL-*`, `CG-01..CG-10`, `A-01..A-09` 행. 범위에
  동시성, 데이터베이스 일관성, 수명 주기 복구가 포함되므로 `A-05`와
  `Step 4-P`도 필수다.
- 조건부: `CG-11..CG-18`, `A-10..A-12`, 정리 분기 `Step 4-S`. 이 행은
  명시적인 PR/merge 권한이나 정리 범위가 트리거된 경우에만 적용된다. 이
  handoff에 대한 구체적인 no-PR 분기는 아래에 기록했다.
- N/A: `CG-X01` (승인된 범위에 tag, release, dispatch, publication,
  deletion 또는 그 밖의 되돌릴 수 없는 non-PR 작업이 없음), Type-B/C/D/E/F/P
  leaf 분기 (이 이슈는 Type-A로 분류됨).
- 복구 메모: 기존 spec 커밋 `5b48fd9` 전에 `CL-01`을 인스턴스화하지
  않았다. 등록된 복구 lane과 새 mutation check로 receipt liveness 공백을
  복구했다. 설계는 `a3cfcb2`, `0e4c786`, `1f9388b`, `b041179` 커밋으로
  수정했으며 구현 소스는 변경하지 않았다. 이제 Step 2-R은 `b041179`에서
  통과한다. Receipt 소유권은 sequence 31에서 coordinator를 통해 이전했다.
  최종 검토와 lesson은
  `docs/review/2026-08-02-issue-170-waitlist-core-step-6r-code-review.md`와
  `docs/lessons/2026-08-02-issue-170-waitlist-core.md`에서 추적한다.

## 라우터 게이트

- [x] **WF-01 — 변경 전에 작업 유형 분류**
  - **Action:** Type-A 분류와 구체적인 복잡도 신호를 기록한다.
  - **Evidence:** 이 체크리스트의 분류, 이슈 #170 범위, receipt `workflow_type: A`.
  - **Failure:** 중지하고 downstream mutation 전에 다시 분류한다.

- [x] **WF-02 — 실행 전에 순서가 정해진 실행 계획 공개**
  - **Action:** 순서가 정해진 Type-A workflow를 공개하고 각 게이트 전환 뒤 계획을 최신 상태로 유지한다.
  - **Evidence:** 사용자에게 보이는 ordered plan과 현재 `update_plan` 상태.
  - **Failure:** 중지하고 누락된 계획을 복구한 뒤 진행한다.

- [x] **WF-03 — 올바른 게이트에서 승인 획득**
  - **Action:** 승인을 승인된 이슈 #170 1단계 범위와 이후 산출물 게이트에 연결한다.
  - **Evidence:** Receipt `plan_approved` event와 정확한 사용자 승인 메시지.
  - **Failure:** 종속 게이트를 pending으로 유지하고 침묵을 승인으로 해석하지 않는다.

- [x] **WF-04 — 라우터·공통·leaf 지침 로드**
  - **Action:** 해당 게이트 전에 `bluetape-workflow`, `bluetape-full-feature`, 필수 reference, 언어/도메인 스킬을 읽는다.
  - **Evidence:** 실행 노트의 읽은 경로와 선택한 스킬 기록.
  - **Failure:** 중지하고 누락된 지침을 로드한 뒤 편집한다.

- [x] **WF-05 — receipt topology와 의존 순서 등록**
  - **Action:** 승인된 component topology와 dependency order를 현재 workflow/checklist와 동기화한다.
  - **Evidence:** Run `20260801T131046Z-09e9909d`, manifest hash, topology registration, completion checks.
  - **Failure:** downstream check를 중단하고 먼저 receipt/topology를 복구한다.

- [x] **WF-06 — liveness와 수명 주기 증거 유지**
  - **Action:** coordinator protocol로 만료된 main-lane lease/deadline을 복구하고 작업 재개 전에 새 증거를 기록한다.
  - **Evidence:** `resume-check`, recovery/heartbeat 또는 bounded fallback event, 새 liveness 결과.
  - **Failure:** run을 pending으로 유지하거나 정해진 replacement/fallback 경로로 복구한다. 오래된 liveness로 진행하지 않는다.

## 공통 게이트

- [x] **CL-01 — 변경 전에 체크리스트 생성**
  - **Action:** 추가 repository mutation 전에 이 router, common, Type-A checklist를 인스턴스화한다.
  - **Evidence:** feature worktree에 이 파일이 존재하고 ID/applicability를 다시 읽었다.
  - **Failure:** STOP; 추가 mutation 전에 재구성한다.

- [x] **CL-02 — 모든 항목 분류**
  - **Action:** 모든 행을 구체적인 범위 증거와 함께 required, conditional 또는 N/A로 표시한다.
  - **Evidence:** 분류 섹션에 미분류 행이 없다.
  - **Failure:** 미분류 행을 required이면서 unchecked인 항목으로 취급한다.

- [x] **CL-03 — 의존 순서 준수**
  - **Action:** 행을 위에서 아래로 실행하고 게이트를 복구할 때마다 downstream proof를 다시 실행한다.
  - **Evidence:** 설계 복구 커밋은 `b041179`의 최종 Step 2-R 산출물보다 앞선다. 최종 design/runbook 편집 뒤 review와 mutation-check를 실행했다.
  - **Failure:** 중지하고 영향을 받은 downstream proof를 모두 다시 실행한다.

- [x] **CL-04 — 즉시 증거 기록**
  - **Action:** 각 행을 체크할 때 command/file/result 증거를 첨부한다.
  - **Evidence:** `docs/review/2026-08-01-issue-170-waitlist-core-spec-review-iteration-2.md`, runbook, 새 `git diff --check`/`mutation-check` 증거가 feature worktree에 있다.
  - **Failure:** 행을 unchecked로 남기고 늦은 재구성을 복구로 처리한다.

- [x] **CL-05 — fail closed**
  - **Action:** pending/failed 행을 unchecked로 유지하고 종속 작업을 차단한다.
  - **Evidence:** A-04가 구현보다 앞선다. 새 증거가 있는 구현 행만 체크했고 pre-PR/PR 행은 pending으로 남겼다.
  - **Failure:** 계속하기 전에 모든 종속 항목을 다시 검증한다.

- [x] **CL-06 — 누락되었거나 순서가 바뀐 작업 복구**
  - **Action:** 누락된 checklist/liveness 게이트를 복구하고 영향을 받은 증거를 다시 실행한다.
  - **Evidence:** Receipt recovery evidence, 네 개의 repair commit, final runbook, `b041179`의 Step 2-R `PASS` review.
  - **Failure:** 최종 상태는 `BLOCKED`로 유지한다.

- [x] **CL-07 — 되돌릴 수 없는 hold 갱신**
  - **Action:** 외부 side effect 직전에 authority, target, head, merge/release hold를 다시 읽는다.
  - **Evidence:** 사용자 승인 local lesson/implementation commit만 요청된 side effect다. commit 직전에 current worktree, branch, target path, no-PR/no-push/no-merge hold를 다시 읽었다.
  - **Failure:** side effect를 실행하지 않는다.

- [x] **CL-08 — 완료 전에 개수 확인**
  - **Action:** `Required checks: X/Y; N/A: N; Blocked: N`으로 checklist를 대조하고 모든 unchecked ID를 나열한다.
  - **Evidence:** `Required checks: 47/47; N/A: 13; Blocked: 0`. 모든 행을 체크했거나 구체적인 N/A 분기를 두었고 unchecked ID가 없다.
  - **Failure:** 완료를 주장할 수 없다.

- [x] **CG-01 — authority 다시 읽기**
  - **Action:** 적용되는 `AGENTS.md`, 선택한 skill, 현재 status/diff, 승인된 이슈 #170 범위를 읽는다.
  - **Evidence:** 경로, status/diff, 분류, 정확한 authority.
  - **Failure:** 편집 전에 STOP.

- [x] **CG-02 — 과거/현재 증거 조회**
  - **Action:** 설계에 사용한 GNO/GitHub/local evidence query와 direct-source fallback을 보존한다.
  - **Evidence:** query term, collection, anchor, 결정적 결과.
  - **Failure:** 누락된 history/current state에 의존하는 결정을 중지한다.

- [x] **CG-03 — 사용자 작업과 경계 보호**
  - **Action:** 격리된 feature worktree를 유지하고 root checkout의 dirty 이슈 #176 변경을 제외한다.
  - **Evidence:** worktree, branch/base/upstream, root status, scoped path.
  - **Failure:** 안전하게 보존하거나 차단한다. 관련 없는 작업을 폐기하지 않는다.

- [x] **CG-04 — 정책과 대상 독자 경계 적용**
  - **Action:** 영문 운영 artifact, 한국어 사용자 대상 기술 문서, 저장소 로컬 Kotlin/Exposed 규칙을 적용한다.
  - **Evidence:** 적용 경로, 변경 surface, 언어/도메인 규칙.
  - **Failure:** 진행 전에 정책 또는 언어 drift를 복구한다.

- [x] **CG-05 — 생태계 패턴 재사용**
  - **Action:** 추상화를 추가하기 전에 기존 appointment FSM, Exposed transaction, migration, testing, reliability-decision 패턴을 재사용한다.
  - **Evidence:** local/sibling anchor와 명시적인 adopt/borrow/reject 근거.
  - **Failure:** 새 abstraction/dependency 작업을 중지한다.

- [x] **CG-06 — 공개 및 문서 계약 증명**
  - **Action:** 승인된 구현에 필요한 API/KDoc/README/registration surface를 갱신하고 제외된 통합은 범위 밖에 둔다.
  - **Evidence:** 새/갱신된 public·internal Kotlin surface에 한국어 KDoc이 있다. 1단계 core에는 HTTP, notification, outbox, scheduler, README 통합 surface를 의도적으로 추가하지 않았다. 제외된 adapter는 승인된 계획에 계속 기록한다.
  - **Failure:** 문서화되지 않았거나 등록되지 않은 동작을 차단한다.

- [x] **CG-07 — 동작 고정 및 targeted proof 실행**
  - **Action:** 변경된 각 동작에 RED/GREEN test와 targeted diagnostic을 사용한다.
  - **Evidence:** test-first 구현 후 targeted waitlist/state/reliability test가 통과했다. 최종 targeted command는 `CORE_TARGETED=PASS`, `CORE_TARGETED_POST_KOTLIN=PASS`를 보고했고 Kotlin safety scan은 `STATIC_SCAN_FINAL=PASS`를 보고했다.
  - **Failure:** 구현으로 돌아가 retry-only 통과 여부를 조사한다.

- [x] **CG-08 — heavyweight check 직렬화**
  - **Action:** real DB, concurrency, shared-state check를 순차적으로 실행한다.
  - **Evidence:** 순차 H2, PostgreSQL, MySQL migration command가 `H2_MIGRATION=PASS`, `POSTGRES_MIGRATION=PASS`, `MYSQL_MIGRATION=PASS`를 보고했고 같은 sequential lane에서 contention/restart test가 통과했다.
  - **Failure:** 모호한 병렬 증거를 폐기하고 안전하게 다시 실행한다.

- [x] **CG-09 — lesson 게이트 평가**
  - **Action:** PR delivery 전에 workflow 복구와 재사용 가능한 reliability/concurrency lesson을 기록한다.
  - **Evidence:** 추적 중인 한국어 lesson `docs/lessons/2026-08-02-issue-170-waitlist-core.md`에 context, decision, surprise/failure, outcome, receipt recovery, verification, future guard가 포함되어 있고 local handoff commit에 들어 있다.
  - **Failure:** pre-PR review 전에 lesson 증거를 복구한다.

- [x] **CG-10 — 최종 pre-PR proof 수렴**
  - **Action:** leaf pre-PR 행, final review, 새 check, scoped commit을 완료한다.
  - **Evidence:** 최종 review `docs/review/2026-08-02-issue-170-waitlist-core-step-6r-code-review.md`가 P0=0/P1=0으로 `PASS`다. 새 test/build/static/diff check가 통과했고 정확한 local head를 handoff receipt와 함께 보고했다.
  - **Failure:** PR 생성을 차단한 상태로 둔다.

- [x] **CG-11 — PR delivery authority 검증** *(conditional)*
  - **Action:** CG-10 이후 명시적인 PR authority, repository, base, head를 검증한다.
  - **Evidence:** N/A — 사용자는 pre-PR lesson/commit과 receipt recovery만 요청했다. PR repository/base/head 권한이 없고 `gh pr list --head feat/issue-170-waitlist-core`가 비어 있다.
  - **Failure:** PR 생성 전에 중지한다.

- [x] **CG-12 — 정확한 head branch 게시** *(conditional)*
  - **Action:** 승인된 head를 push하고 일치하는 remote SHA를 다시 읽는다.
  - **Evidence:** N/A — push를 승인하거나 실행하지 않았고 local feature worktree가 publication 경계로 남아 있다.
  - **Failure:** 중지하고 publication을 복구한다.

- [x] **CG-13 — PR 생성 및 검증** *(conditional)*
  - **Action:** metadata parity와 마지막 `## DoD Status` 섹션을 포함해 PR을 생성/갱신한다.
  - **Evidence:** N/A — PR 생성은 이 요청의 범위 밖이며 feature head에 live PR이 없다.
  - **Failure:** CI/review 전에 live PR을 복구한다.

- [x] **CG-14 — CI 및 live human review 통과** *(conditional)*
  - **Action:** exact-head CI를 기다리고 review/thread와 필수 artifact를 다시 읽는다.
  - **Evidence:** N/A — PR/remote CI/review thread를 생성하지 않았다. local final review는 `PASS`, P0/P1은 0이며 remote 증거가 없는 것은 의도된 상태다.
  - **Failure:** pending으로 유지하거나 복구로 돌아간다.

- [x] **CG-15 — merge-ready 보고** *(conditional)*
  - **Action:** 모든 행을 대조하고 pending merge ID와 함께 정확한 PR/head를 보고한다.
  - **Evidence:** N/A — 이것은 merge-ready report가 아니라 pre-PR handoff다. 최종 보고에는 대신 정확한 local head와 receipt state가 들어 있다.
  - **Failure:** 누락된 증거를 복구하고 merge approval을 요청하지 않는다.

- [x] **CG-16 — 새 merge approval 획득** *(conditional)*
  - **Action:** CG-15 이후 정확한 현재 PR/head에 연결된 approval을 받고 CL-07을 갱신한다.
  - **Evidence:** N/A — merge approval을 위해 PR/head를 제시하지 않았고 merge는 범위 밖이다.
  - **Failure:** pending으로 남기고 authority를 추론하지 않는다.

- [x] **CG-17 — merge 실행 및 검증** *(conditional)*
  - **Action:** CG-16 이후에만 merge하고 live merged state/SHA를 검증한다.
  - **Evidence:** N/A — merge를 승인하거나 실행하지 않았다.
  - **Failure:** 중지하고 진단한다.

- [x] **CG-18 — 동기화 및 정리** *(conditional)*
  - **Action:** local checkout을 sync하고 merge가 증명된 worktree/branch만 제거한다.
  - **Evidence:** N/A — publication/merge가 발생하지 않았으므로 worktree와 branch 정리는 이르며 의도적으로 미뤘다.
  - **Failure:** 모호한 상태를 보존하고 pending으로 보고한다.

- [x] **CG-X01 — 다른 되돌릴 수 없는 작업 승인** *(N/A)*
  - **Action:** tag/release/dispatch/publication/deletion/non-PR 되돌릴 수 없는 작업은 범위에 없다.
  - **Evidence:** 승인된 이슈 #170 1단계 범위에서 이 작업을 제외했다.
  - **Failure:** 범위가 바뀌면 실행 전에 이 분기를 인스턴스화하고 완료한다.

## Type-A 게이트

- [x] **A-01 — 요구사항 격리 및 확인**
  - **Action:** feature worktree, 보존된 root 변경, 이슈 #170 범위, 제외 항목, 호환성, side effect, stop condition을 확인한다.
  - **Evidence:** worktree/branch/base/cwd와 승인된 요구사항.
  - **Failure:** research나 artifact 전에 중지한다.

- [x] **A-02 — 현재 증거에 설계 근거 고정**
  - **Action:** local pattern/history, GNO/GitHub evidence, dependency/catalog authority를 조사한다.
  - **Evidence:** anchor, source evidence, adopt/borrow/reject 근거.
  - **Failure:** 기억에 의존해 설계하지 않는다.

- [x] **A-03 — 설계 spec 승인 및 검토**
  - **Action:** 승인된 spec을 여섯 개 독립 관점과 main integration으로 검토하고 모든 P0/P1 finding을 수정한다.
  - **Evidence:** 승인된 spec, `docs/review/2026-08-01-issue-170-waitlist-core-spec-review-iteration-2.md`, 정확한 commit `b041179`, integrated P0=0/P1=0.
  - **Failure:** 중요한 변경은 수정/재승인하고 계획을 차단된 상태로 유지한다.

- [x] **A-04 — 구현 계획 승인 및 검토**
  - **Action:** 순서가 정해진 계획을 작성하고 여섯 개 계획 관점과 integration을 모두 실행한다.
  - **Evidence:** `a2e67d5`의 계획 `docs/superpowers/plans/2026-08-01-issue-170-waitlist-core.md`, review `docs/review/2026-08-01-issue-170-waitlist-core-plan-review.md`, integrated PASS, P0=0/P1=0/P2=0/P3=0, 명시적인 chat approval `승인, $bluetape-kotlin-patterns 을 철저히 준수하면서 구현해라`.
  - **Failure:** code 전에 ordering/proof/ownership/hazard 공백을 복구하고 승인 없이 구현을 시작하지 않는다.

- [x] **A-05 — 트리거된 위험 예측**
  - **Action:** concurrency, DB migration, CAS, privacy, dependency, recovery risk를 signal, mitigation, rollback/rerun 지점과 함께 기록한다.
  - **Evidence:** plan commit `a2e67d5`, `실행 위험과 중단 기준` 섹션, `docs/review/2026-08-01-issue-170-waitlist-core-plan-review.md`의 integrated A-04 review.
  - **Failure:** 구현 전에 risk prediction을 완료한다.

- [x] **A-06 — test-first proof로 구현**
  - **Action:** 분리된 write scope에서 TDD와 Kotlin/Exposed/domain skill을 사용한다.
  - **Evidence:** 격리된 feature worktree에서 승인된 task scope를 구현했다. targeted waitlist/reliability/state test, contention/restart check, Kotlin safety scan, `git diff --check`가 green이다.
  - **Failure:** 실패한 동작이나 위반된 경계로 돌아간다.

- [x] **A-07 — test·spec·plan·repository hazard 검증**
  - **Action:** targeted validation 후 비례적인 broader validation을 실행하고 결과를 정확한 승인 artifact와 비교한다.
  - **Evidence:** 전체 `:appointment-core:test :appointment-api:test`와 `:appointment-core:build :appointment-api:build`가 모두 exit 0이다. 세 migration dialect와 승인된 1단계 제외 항목을 계획과 대조했다.
  - **Failure:** 구현으로 돌아가거나 artifact를 다시 연다.

- [x] **A-08 — 최종 pre-PR review 수렴**
  - **Action:** final checklist, 여섯 개 code-review 관점, main integration을 실행하고 blocker를 수정한 뒤 영향을 받은 proof를 다시 실행한다.
  - **Evidence:** 최종 review `docs/review/2026-08-02-issue-170-waitlist-core-step-6r-code-review.md`가 P0=0/P1=0으로 `PASS`다. claim/recovery/schema finding을 해결한 뒤 새 test/build/static check와 `git diff --check`가 통과했다.
  - **Failure:** PR 생성을 차단한 상태로 둔다.

- [x] **A-09 — 지속 가능한 학습 내용 commit**
  - **Action:** PR 생성 전에 checklist/liveness 누락과 향후 guard를 다루는 lesson을 commit한다.
  - **Evidence:** `docs/lessons/2026-08-02-issue-170-waitlist-core.md`가 local Lore handoff commit에 추적되고 context, decision, outcome, proof, checklist/liveness 누락, receipt recovery, future guard를 포함한다.
  - **Failure:** 추적되지 않았거나 증거만 담은 lesson은 delivery를 unblock하지 않는다.

- [x] **A-10 — live CI와 review를 통한 승인된 PR delivery 완료** *(conditional)*
  - **Action:** 정확한 head에서 CG-11부터 CG-14까지 완료하거나 구체적인 no-PR 분기를 기록한다.
  - **Evidence:** N/A — 구체적인 no-PR 분기: 요청은 local pre-PR lesson/commit과 receipt recovery 후 중지한다. remote publication이나 PR authority가 없다.
  - **Failure:** delivery를 pending 또는 failed로 유지한다.

- [x] **A-11 — 지식 기록 및 merge readiness 보고** *(conditional)*
  - **Action:** 지속 가능한 지식을 기록하고 정확한 PR/head state와 함께 phase-aware count를 산출하거나 no-PR DoD를 산출한다.
  - **Evidence:** merge readiness에는 N/A다. lesson에 지속 가능한 지식을 기록했고 no-PR DoD가 정확한 local head와 receipt completion proof를 포함해 `Required checks: 47/47; N/A: 13; Blocked: 0`을 보고한다.
  - **Failure:** 차단된 행과 복구 작업을 드러낸다.

- [x] **A-12 — 새 merge approval 이후에만 종료** *(conditional)*
  - **Action:** 새 approval 후 CG-16부터 CG-18까지 완료하거나 구체적인 no-PR 분기를 기록한다.
  - **Evidence:** N/A — no-PR 분기다. merge approval, merge SHA, sync, cleanup은 이후 별도로 승인된 PR workflow까지 미룬다.
  - **Failure:** pending/blocked로 유지하고 state를 보존한다.

## 트리거된 구현 하위 게이트

- [x] **Step 4-S — 정리 분기** *(conditional)*
  - **Action:** 구현으로 substantial duplication, verbosity, generated residue 또는 broad refactor noise가 생긴 경우에만 트리거한다.
  - **Evidence:** N/A — final review에서 별도 cleanup pass가 필요할 정도의 substantial duplication, generated residue, broad refactor noise를 찾지 못했다. 지금 구조를 바꾸면 승인된 범위를 넓히게 된다.
  - **Failure:** 계획에 없는 cleanup을 수행하지 않는다.

- [x] **Step 4-P — 성능 및 안정성**
  - **Action:** DB contention, CAS retry, transaction duration, lifecycle cleanup, multi-backend 동작을 검토한다.
  - **Evidence:** `WaitlistContentionLoadTest`가 `pool=16 attempts=100 p50=31ms p95=172ms p99=247ms`로 통과했다. restart recovery는 terminal transition/event 하나와 duplicate work 0건으로 통과했고 H2/PostgreSQL/MySQL migration check도 순차적으로 통과했다.
  - **Failure:** P0/P1을 수정하고 영향을 받은 test를 다시 실행한다.

## 현재 중지 조건

작성한 계획이 A-04를 통과하고 명시적인 사용자 승인을 받은 뒤 구현을
검증했다. Step 2-R은 더 이상 blocker가 아니다. Kotlin/Exposed 구현,
targeted/full test, migration matrix, contention/restart proof, static
diagnostic, final pre-PR review, 지속 가능한 lesson commit을 local에서
완료했다. receipt는 owner transfer를 통해 복구했으며 failed-main history를
보존한다. PR/merge/push는 의도적으로 이 handoff 범위 밖이다.

Step 2-R 증거는 다음에 기록되어 있다.
`docs/review/2026-08-01-issue-170-waitlist-core-spec-review-iteration-2.md`.
통합 verdict는 P0=0, P1=0인 `PASS`이며 P2 항목은 명시적인 plan/adapter
후속 작업이다.

A-04 review 증거는 다음에 기록되어 있다.
`docs/review/2026-08-01-issue-170-waitlist-core-plan-review.md`. 통합
verdict는 P0=0, P1=0, P2=0, P3=0인 `PASS`이며 구현 승인은 현재 chat
message에 기록되어 있다.

Pre-PR handoff 필수 check: `47/47` checked, N/A: `13`, Blocked: `0`.
Unchecked ID: 없음. 조건부 PR/merge 행은 구체적인 no-PR 분기를 사용하며
remote delivery의 증거가 아니다.

## 구현 증거 (2026-08-02)

- `./gradlew :appointment-core:test :appointment-api:test --console=plain -q` → exit 0. 최신 XML 합계는 core `590/0/0/0`, API `593/0/0/2`다 (tests/failures/errors/skipped).
- `./gradlew --no-daemon :appointment-core:build :appointment-api:build --console=plain -q` → `FULL_BUILDS_POST_LOG=PASS`.
- 최종 invariant/KDoc cleanup compile lane → `FINAL_CORE_BUILD_API_COMPILE=PASS`; repository exception assertion → `REPOSITORY_ASSERTION=PASS`.
- 순차 targeted/migration lane → `CORE_TARGETED=PASS`, `CORE_TARGETED_POST_KOTLIN=PASS`, `H2_MIGRATION=PASS`, `POSTGRES_MIGRATION=PASS`, `MYSQL_MIGRATION=PASS`.
- Kotlin safety scan (production `!!`, operational print, suspend `runCatching`, blocking/raw SQL pattern) → `STATIC_SCAN=PASS`; `git diff --check` → `DIFF_CHECK=PASS`.
- PR, push, merge, branch deletion, release, tag action은 실행하지 않았다. 현재 feature worktree가 reviewable handoff 경계로 남아 있다.
