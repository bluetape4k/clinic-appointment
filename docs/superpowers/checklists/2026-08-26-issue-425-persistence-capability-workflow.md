# Issue #425 Type-A Workflow Checklist

상태 기준은 `bluetape-workflow`의 checklist contract를 따른다. `[ ]`는 아직
증명하지 않은 항목이며, `N/A`는 이 문서의 범위 근거를 함께 기록해야 한다.
구현·PR·merge는 선행 항목이 PASS가 된 뒤에만 진행한다.

## 승인된 범위

- Issue: [#425](https://github.com/bluetape4k/clinic-appointment/issues/425)
- 유형: Type-A full feature (기존 notification public API 경계의 다중 모듈 리팩터링)
- 기준 ref: `origin/develop` / `5399ff63649f1cc78ae73f00d121c37195817fb8`
- 작업 branch: `refactor/issue-425-persistence-capability`
- worktree: `/Users/debop/work/bluetape4k/clinic-appointment/.worktrees/issue-425-persistence-capability`
- PR 권한: 승인 계획에 `bluetape4k/clinic-appointment`, base `develop`, head
  `refactor/issue-425-persistence-capability`, PR 생성이 명시됨. merge는 별도
  fresh approval이 필요하다.
- 목표: `JdbcNotificationOutboxRepository`와 waitlist concrete persistence가
  notification worker/store/fixture의 public 생성자 경계를 침범하지 않도록
  capability port를 도입하고, 동작·ABI·source contract를 재검증한다.
- 제외: 새 모듈·dependency·schema/migration, production route 변경, frontend,
  publish/release, root `develop`의 기존 dirty 파일.

## Router WF-00..WF-06

- [x] **WF-00 — AGENTS.md 계층 읽기**
  - **Action:** user/workspace/repository guidance를 계층 순서로 읽는다.
  - **Evidence:** `/Users/debop/.codex/AGENTS.md`, workspace `AGENTS.md`,
    repository `AGENTS.md`를 현재 실행 전에 read-back했다.
  - **Failure:** 기준 누락 시 mutation을 중지한다.
- [x] **WF-01 — 작업 유형 분류**
  - **Action:** Issue #425와 현재 source를 Type-A로 분류한다.
  - **Evidence:** 기존 공개 persistence 생성자·fixture·ABI·문서·다중 테스트 경계를
    다루는 public API refactor이므로 `bluetape-full-feature`를 선택했다.
  - **Failure:** 범위가 축소되면 fast-track으로 재분류하고 해당 gate를 다시 연다.
- [x] **WF-02 — 첫 실행 계획 작성**
  - **Action:** worktree, spec/plan, TDD, review, PR/merge hold를 포함한 ordered plan을 제시한다.
  - **Evidence:** 현재 thread에서 capability ports, concrete constructor 제거,
    fixture/source guard, targeted verification, PR target을 승인 계획으로 제시했다.
  - **Failure:** 승인 전 source mutation을 하지 않는다.
- [x] **WF-03 — 첫 계획 승인**
  - **Action:** 사용자 승인을 기록한다.
  - **Evidence:** 현재 thread의 최신 `승인` 응답.
  - **Failure:** 승인 증거가 없으면 다음 단계로 진행하지 않는다.
- [x] **WF-04 — 실행 계약 로드**
  - **Action:** Type-A, Kotlin, writer, common-gates, topology/manifest, worktree 계약을 읽는다.
  - **Evidence:** `bluetape-full-feature`, `bluetape-kotlin-patterns`,
    `bluetape-writer`, `using-git-worktrees`, `common-gates.md`,
    `checklist-contract.md`, `workflow-manifest.json`, `topology-contract.md`를
    현재 단계에서 read-back했다.
  - **Failure:** 누락된 계약을 보충하기 전에는 설계·코드 변경을 중지한다.
- [ ] **WF-04A — machine-readable run 초기화**
  - **Action:** `bluetape-flow.py`로 Type-A run을 초기화하고 topology/lane을 등록한다.
  - **Evidence:** run id, owner epoch, topology receipt, lane receipt, state root.
  - **Failure:** helper 오류를 기록하고 receipt 없는 mutation을 금지한다.
- [ ] **WF-05 — 의존 순서 게이트 실행**
  - **Action:** checklist를 위에서 아래로 실행하고 dependent gate를 선행 증거 뒤에 연다.
  - **Evidence:** receipt와 이 문서의 command ledger.
  - **Failure:** 순서가 어긋나면 영향을 받은 proof를 재실행한다.
- [ ] **WF-06 — 누락·실패 gate 복구**
  - **Action:** 실패·stale·누락 증거를 원인 수정 후 재검증한다.
  - **Evidence:** repair command/result와 refreshed dependent checks.
  - **Failure:** 안전한 복구가 없으면 `BLOCKED`로 유지한다.

## Common gates CL-01..CL-08

- [x] **CL-01 — 변경 전에 checklist 생성**
  - **Action:** router/common/leaf rows를 source mutation 전에 인스턴스화한다.
  - **Evidence:** 이 파일을 implementation mutation 전에 생성했다.
  - **Failure:** checklist를 복구하기 전에는 구현하지 않는다.
- [x] **CL-02 — 모든 row 분류**
  - **Action:** required/conditional/N/A를 각 row에 결정한다.
  - **Evidence:** 아래 `Scope N/A`와 각 conditional row가 적용 범위를 고정한다.
  - **Failure:** 분류되지 않은 row는 required unchecked로 취급한다.
- [ ] **CL-03 — 의존 순서 준수**
  - **Action:** worktree → evidence/spec → plan → risk → RED/GREEN → review → PR 순서를 지킨다.
  - **Evidence:** receipt sequence, commit order, command ledger.
  - **Failure:** affected downstream proof를 새로 수집한다.
- [ ] **CL-04 — 증거 즉시 기록**
  - **Action:** gate 확인 직후 command/file/URL/result를 기록한다.
  - **Evidence:** 이 checklist, `.bluetape` receipt, review/lesson/PR artifacts.
  - **Failure:** 재구성하지 말고 unchecked로 남긴다.
- [ ] **CL-05 — fail closed**
  - **Action:** PENDING/FAIL row의 dependent branch를 차단한다.
  - **Evidence:** 실패·대기 원인과 재개 조건.
  - **Failure:** dependent가 실행됐다면 영향을 받은 검증을 rerun한다.
- [ ] **CL-06 — skip/reorder 복구**
  - **Action:** 누락·순서 오류를 repair하고 dependent proof를 갱신한다.
  - **Evidence:** repair receipt와 fresh command output.
  - **Failure:** 최종 상태를 `BLOCKED`로 남긴다.
- [ ] **CL-07 — irreversible hold refresh**
  - **Action:** PR 생성 및 merge 직전에 authority·target·exact head를 다시 읽는다.
  - **Evidence:** current issue/PR metadata, CI, review, head SHA, fresh approval.
  - **Failure:** stale hold에서는 side effect를 실행하지 않는다.
- [ ] **CL-08 — 완료 count 산출**
  - **Action:** `Required checks: X/Y; N/A: N; Blocked: N`과 unchecked IDs를 계산한다.
  - **Evidence:** final DoD와 live PR body.
  - **Failure:** count가 맞지 않으면 완료를 주장하지 않는다.

## Type-A A-01..A-12

- [ ] **A-01 — 요구사항 격리·확정**
  - **Action:** worktree/base/dirty boundary와 Issue #425 outcome·stop condition을 확정한다.
  - **Evidence:** branch/worktree/base/current issue read-back.
  - **Failure:** material ambiguity를 해소하기 전에는 research를 진행하지 않는다.
- [ ] **A-02 — 현재 evidence 기반 설계**
  - **Action:** local source/history, GNO fallback, sibling/catalog pattern을 조사한다.
  - **Evidence:** concrete class/constructor/test/doc anchors와 reuse/reject rationale.
  - **Failure:** recall 기반 설계를 폐기하고 evidence를 보충한다.
- [ ] **A-03 — 설계 spec 승인·review**
  - **Action:** brainstorming/spec와 6-perspective + integration review를 수행한다.
  - **Evidence:** spec path, `SPW-01..05`, review table, P0=0/P1=0.
  - **Failure:** material change 시 approval과 affected review를 다시 수행한다.
- [ ] **A-04 — 구현 plan 승인·review**
  - **Action:** ordered file/task/test/docs/hazard/rollback plan과 6-perspective review를 수행한다.
  - **Evidence:** plan path, traceability map, `SPW-01..05`, P0=0/P1=0.
  - **Failure:** 누락 task·proof·rollback을 보충한다.
- [ ] **A-05 — triggered risk prediction**
  - **Action:** public API/ABI, DB transaction/lease/retry/readiness 위험을 기록한다.
  - **Evidence:** risk/signal/mitigation/rerun/rollback table.
  - **Failure:** generic skip은 허용하지 않는다.
- [ ] **A-06 — TDD 구현**
  - **Action:** constructor/source guard RED → 최소 capability-port GREEN → refactor를 수행한다.
  - **Evidence:** RED/GREEN output, scoped diff, diagnostics, integration review.
  - **Failure:** failing behavior나 boundary violation으로 돌아간다.
- [ ] **A-07 — spec/plan/hazard 검증**
  - **Action:** targeted 및 proportional validation과 verifier checklist를 수행한다.
  - **Evidence:** module compile/test, source/jar/ABI checks, acceptance mapping, verifier PASS.
  - **Failure:** implementation 또는 artifact review를 다시 연다.
- [ ] **A-08 — pre-PR review 수렴**
  - **Action:** final checklist, 6 perspectives + integration, writer gate를 완료한다.
  - **Evidence:** tracked review artifact, diff check, P0=0/P1=0, exact local head.
  - **Failure:** PR 생성은 수렴 전 차단한다.
- [ ] **A-09 — lesson commit**
  - **Action:** Korean lesson을 writer gate와 Lore commit으로 남긴다.
  - **Evidence:** tracked lesson, `SPW-01..05`, commit SHA.
  - **Failure:** untracked/evidence-only lesson은 인정하지 않는다.
- [ ] **A-10 — PR delivery와 CI/review**
  - **Action:** CG-11..14를 exact head에 대해 수행한다.
  - **Evidence:** remote head, live PR metadata/body, review/thread, required CI.
  - **Failure:** stale/missing evidence면 delivery를 PENDING/FAIL로 둔다.
- [ ] **A-11 — knowledge capture·merge-ready**
  - **Action:** durable lesson/index와 CG-15 merge-ready report를 작성한다.
  - **Evidence:** PR/head, CI/review, risks, counts, unchecked CG-16..18.
  - **Failure:** fresh merge approval 없이 DONE을 선언하지 않는다.
- [ ] **A-12 — fresh approval 후 merge closeout**
  - **Action:** CG-16..18, merge SHA, integration sync, worktree cleanup을 수행한다.
  - **Evidence:** fresh approval, merged state, local/upstream parity, cleanup list.
  - **Failure:** 승인 전 merge하지 않고 PENDING으로 유지한다.

## Kotlin / domain / test gates

- [ ] **KT-01 — Kotlin pattern surface**
  - **Action:** `bluetape-kotlin-patterns`와 triggered testing/Spring/Exposed guidance를 적용한다.
  - **Evidence:** reference read-back, idiomatic API/KDoc review.
  - **Failure:** unclassified Kotlin boundary를 구현하지 않는다.
- [ ] **KT-02 — ecosystem reuse**
  - **Action:** 기존 `NotificationOutboxWorkStore`, observation/writer ports, assertions,
    `Base58.randomString(8)`, Exposed transaction, singleton launcher를 우선 재사용한다.
  - **Evidence:** source anchors와 no-new-dependency proof.
  - **Failure:** 새 abstraction/dependency는 근거 없이는 추가하지 않는다.
- [ ] **KT-03 — assertions와 persistence contract**
  - **Action:** `bluetape4k-assertions`, null-safety/immutability, transaction 경계를 점검한다.
  - **Evidence:** changed tests/source scan, transaction/lease/retry/readiness assertions.
  - **Failure:** raw fallback 또는 transaction leak는 repair한다.
- [ ] **KT-04 — targeted Kotlin validation**
  - **Action:** notification compile/test, consumer fixture, source/jar/ABI guard를 순차 실행한다.
  - **Evidence:** fresh Gradle output, XML failures/errors `0`, source/jar checks PASS.
  - **Failure:** stale/partial output은 PASS로 취급하지 않는다.
- [ ] **KT-05 — final Kotlin checklist**
  - **Action:** public KDoc, API compatibility, test lifecycle, static diagnostics를 최종 점검한다.
  - **Evidence:** final checklist와 review finding table.
  - **Failure:** P0/P1 또는 unchecked row를 남긴 채 진행하지 않는다.

## Writer SPW-01..SPW-05

- [ ] **SPW-01 — audience·purpose·evidence 고정**
  - **Action:** spec/plan/review/lesson의 독자, 한국어 범위, source, identifiers, unknowns를 기록한다.
  - **Evidence:** 각 artifact의 source ledger.
  - **Failure:** 근거 없는 문장을 제거·한정한다.
- [ ] **SPW-02 — artifact contract 충족**
  - **Action:** spec/plan/review/lesson 필수 구조와 acceptance/DoD를 채운다.
  - **Evidence:** artifact headings와 traceability.
  - **Failure:** 누락 구조를 보충할 때까지 dependent gate를 막는다.
- [ ] **SPW-03 — 한국어 technical register**
  - **Action:** Korean naturalness checklist와 terminology audit를 수행한다.
  - **Evidence:** audit output와 보존된 code/command/API token.
  - **Failure:** translationese·marketing·의미 변경을 repair한다.
- [ ] **SPW-04 — technical meaning traceability**
  - **Action:** source/spec/plan/review/lesson과 finished prose를 대조한다.
  - **Evidence:** claim-to-source 및 acceptance-to-plan mapping.
  - **Failure:** drift가 있으면 dependent artifact도 다시 연다.
- [ ] **SPW-05 — rendered read-back**
  - **Action:** Markdown heading/table/code fence/link와 최종 흐름을 read-back한다.
  - **Evidence:** final path와 checklist count.
  - **Failure:** unchecked writer row로 둔다.

## Common / Type-A 조건부 N/A

- `N/A` — 새 모듈·dependency·schema/migration: Issue #425와 승인 계획에서 명시적으로 제외한다.
- `N/A` — frontend, publish/release, diagram/visual QA, live credential/traffic: 변경 scope에 없다.
- `N/A` — Testcontainers: DB schema/launcher 동작은 변경하지 않으며 기존 singleton 정책을 재사용한다.
- `N/A` — Step 4-S cleanup: implementation diff와 별도 cleanup trigger를 검토한 뒤 판단한다.
- `N/A` — external human reviewer: repository가 1인 개발자 lane이면 exact CI와 independent local review를
  수행하고 human-review subgate만 구체적 근거와 함께 N/A로 기록한다.
- `PENDING` — CG-07/CG-08/CG-09/CG-10/CG-11..CG-18 및 A-06 이후 항목은 선행 증거 수집 뒤 갱신한다.

## Fresh evidence ledger

| 시점 | 명령/산출물 | 결과 |
|---|---|---|
| 2026-08-26 | `git status`, `git worktree list`, `git rev-parse` | root dirty state 보존, isolated worktree와 `origin/develop@5399ff63` 확인 |
| 2026-08-26 | Issue #425 live read-back | OPEN, assignee `debop`, labels documentation/maintenance/refactor, milestone 없음 |
| 추후 | spec/plan/review/lesson/Gradle/gh 명령 | 실행 직후 기록 |

## Current DoD

`Required checks: 미산출; N/A: 조건부 항목만 위에 기록; Blocked: 0`.
최종 단계에서 X/Y 규칙으로 재계산하고 모든 unchecked ID를 명시한다.
