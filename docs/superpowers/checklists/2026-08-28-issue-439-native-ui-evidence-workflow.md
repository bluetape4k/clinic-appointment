# Issue #439 Type-E Workflow Checklist

상태 기준은 `bluetape-workflow`의 checklist contract를 따른다. `[ ]`는 아직
증명하지 않은 항목이며, `N/A`는 이 문서의 범위 근거를 함께 기록한다.

## 승인된 범위

- Issue: [#439](https://github.com/bluetape4k/clinic-appointment/issues/439)
- 유형: Type-E maintenance (native UI harness·workflow·report·문서 변경)
- 기준 ref: `origin/develop@e6937e02a202106f3927ccf71c47f8f0c38ce952`
- 작업 branch: `feat/issue-439-native-ui-evidence`
- worktree: `/Users/debop/work/bluetape4k/clinic-appointment/.worktrees/issue-439-native-ui-evidence`
- PR 권한: 승인된 구현 계획의 대상 저장소는 `bluetape4k/clinic-appointment`, base는
  `develop`, head는 `feat/issue-439-native-ui-evidence`다. merge는 fresh approval이
  필요한 별도 hold다.
- 목표: 실제 Android Emulator/iOS Simulator에서 bottom tab, Safe Area,
  focus/keyboard, portrait·landscape 경계를 검증하고 exact-head report/artifact를
  native workflow에 연결한다.
- 제외: production API/auth/token 변경, native UI 재작성, 새 production dependency,
  root `develop`의 기존 dirty 파일, Kotlin 소스 변경.

## Router WF-00..WF-06

- [x] **WF-00 — AGENTS.md 계층 읽기**
  - **Action:** 사용자·workspace·repository guidance를 계층 순서로 읽는다.
  - **Evidence:** `/Users/debop/.codex/AGENTS.md`,
    `/Users/debop/work/bluetape4k/.github/docs/workspace/AGENTS.md`,
    `clinic-appointment/AGENTS.md`를 구현 전에 read-back했다.
  - **Failure:** 기준 누락 시 mutation을 중지한다.
- [x] **WF-01 — 작업 유형 분류**
  - **Action:** Issue #439를 Type-E로 분류한다.
  - **Evidence:** workflow·CI·native harness·문서만 변경하고 production 동작은
    유지하므로 `bluetape-maintenance`를 선택했다.
  - **Failure:** production behavior가 추가되면 Type-A/B로 재분류한다.
- [x] **WF-02 — 첫 실행 계획 작성**
  - **Action:** worktree, report, Android/iOS test, workflow, docs, review, PR hold를
    포함한 ordered plan을 제시한다.
  - **Evidence:** 현재 thread에서 설계와 구현 순서를 제시하고 사용자가 승인했다.
  - **Failure:** 승인 전 source mutation을 하지 않는다.
- [x] **WF-03 — 첫 계획 승인**
  - **Action:** 사용자의 명시적 승인을 기록한다.
  - **Evidence:** 현재 thread의 `승인, $bluetape-kotlin-patterns 지침을 잘 지켜라`.
  - **Failure:** 승인 증거가 없으면 실행을 중지한다.
- [x] **WF-04 — 실행 계약 로드**
  - **Action:** Type-E·writer·Kotlin pattern·common-gates·manifest/topology·TDD 계약을 읽는다.
  - **Evidence:** `bluetape-maintenance`, `bluetape-writer`, `bluetape-kotlin-patterns`,
    `common-gates.md`, `checklist-contract.md`, `workflow-manifest.json`,
    `topology-contract.md`, `test-driven-development`를 read-back했다.
  - **Failure:** 누락 계약을 보충하기 전에는 구현하지 않는다.
- [x] **WF-04A — machine-readable run 초기화**
  - **Action:** `bluetape-flow.py`로 Type-E run·lane·topology를 초기화한다.
  - **Evidence:** run `20260828T044953Z-82cd4ad3`, state root
    `.bluetape`, topology sequence `7`, main-session lane이 running이다.
  - **Failure:** receipt 없는 mutation을 허용하지 않는다.
- [ ] **WF-05 — 의존 순서 게이트 실행**
  - **Action:** 이 checklist의 물리적 순서대로 preflight→RED/GREEN→native/CI→review를 실행한다.
  - **Evidence:** 최종 receipt, command ledger, artifact와 각 row의 fresh 결과.
  - **Failure:** 순서 오류 시 영향을 받은 downstream proof를 재실행한다.
- [ ] **WF-06 — 누락·실패 gate 복구**
  - **Action:** 실패·stale·누락 증거를 수리하고 affected proof를 다시 실행한다.
  - **Evidence:** repair 기록 또는 `N/A` scope 근거.
  - **Failure:** 안전한 복구가 없으면 `BLOCKED`로 유지한다.

## Common gates CL-01..CL-08

- [ ] **CL-01 — 변경 전에 checklist 생성**
  - **Action:** router/common/leaf rows를 source mutation 전에 인스턴스화한다.
  - **Evidence:** 이 파일을 구현 source 변경 전에 생성했다. runtime bootstrap input은
    `.bluetape` receipt 초기화에만 사용했고, 누락 순서를 이 row에서 복구한다.
  - **Failure:** checklist 누락 시 구현을 중지한다.
- [ ] **CL-02 — 모든 row 분류**
  - **Action:** required/conditional/N/A를 모든 row에 결정한다.
  - **Evidence:** 아래 N/A 표와 conditional PR/merge rows가 범위를 고정한다.
  - **Failure:** 미분류 row는 required unchecked로 둔다.
- [ ] **CL-03 — 의존 순서 준수**
  - **Action:** contract RED→report GREEN→platform harness→CI→docs/review 순서를 지킨다.
  - **Evidence:** command ledger와 git history가 순서를 증명한다.
  - **Failure:** 영향받은 proof를 새로 수집한다.
- [ ] **CL-04 — 증거 즉시 기록**
  - **Action:** gate 확인 직후 command/file/URL/result를 기록한다.
  - **Evidence:** 이 checklist와 workflow receipt, spec/plan/review/lesson.
  - **Failure:** late reconstruction은 repair로 기록한다.
- [ ] **CL-05 — fail closed**
  - **Action:** PENDING/FAIL row의 dependent branch를 차단한다.
  - **Evidence:** local Xcode/Android SDK 부재는 native CI PENDING으로 유지하고
    원격 exact-head 결과 전에는 PASS로 바꾸지 않는다.
  - **Failure:** dependent proof를 보류·재실행한다.
- [ ] **CL-06 — skip/reorder 복구**
  - **Action:** 누락·순서 오류를 repair하고 affected downstream을 갱신한다.
  - **Evidence:** CL-01 bootstrap/checklist 순서 복구와 fresh rerun 기록.
  - **Failure:** 최종 상태를 `BLOCKED`로 남긴다.
- [ ] **CL-07 — irreversible hold refresh**
  - **Action:** PR 생성·merge 직전에 authority·target·exact head를 다시 읽는다.
  - **Evidence:** CG-11/12A/15/16의 live read-back.
  - **Failure:** stale hold에서는 side effect를 실행하지 않는다.
- [ ] **CL-08 — 완료 count 산출**
  - **Action:** `Required checks: X/Y; N/A: N; Blocked: N`과 unchecked IDs를 계산한다.
  - **Evidence:** 최종 checklist와 PR body의 동일 count.
  - **Failure:** count가 맞지 않으면 완료를 주장하지 않는다.

## Type-E E-01..E-08

- [ ] **E-01 — Route support skills**
  - **Action:** workflow·writer·Kotlin pattern·TDD와 triggered native/document surface를 로드한다.
  - **Evidence:** loaded skill 목록과 Kotlin N/A 근거.
  - **Failure:** missing route는 STOP.
- [ ] **E-02 — Discover current guidance**
  - **Action:** current source, issue, GNO, sibling native harness와 existing report를 조사한다.
  - **Evidence:** issue #439 live metadata, #24 design, workflow/report/test anchors.
  - **Failure:** history/current state가 없으면 read-only로 남긴다.
- [ ] **E-03 — Preserve behavior and ownership**
  - **Action:** production behavior·root dirty state·managed/global surfaces를 보존한다.
  - **Evidence:** scoped diff, no production API/auth change, root status unchanged.
  - **Failure:** 범위 이탈 시 되돌리거나 재분류한다.
- [ ] **E-04 — Apply and prove parity**
  - **Action:** 해당 없음 여부를 판정하고 CI/workflow source를 live validation으로 검증한다.
  - **Evidence:** chezmoi 대상 없음; workflow validator/actionlint와 exact-head CI read-back.
  - **Failure:** live-only success는 인정하지 않는다.
- [ ] **E-05 — Run maintenance verification**
  - **Action:** diff check, references, Node tests, actionlint, native CI, docs/term audit를 실행한다.
  - **Evidence:** command output와 artifact paths.
  - **Failure:** 실패한 check를 repair한다.
- [ ] **E-06 — Complete durable pre-PR proof**
  - **Action:** duplicate/metadata/language/capability/pruning과 7-Tier review를 완료한다.
  - **Evidence:** final diff, P0/P1 count, issue/PR parity, lesson.
  - **Failure:** 수렴 전 PR을 만들지 않는다.
- [ ] **E-07 — Deliver through common PR gates**
  - **Action:** CG-11..15를 exact head에 적용한다.
  - **Evidence:** PR body/metadata/CI/review live read-back.
  - **Failure:** common gate 상태를 유지한다.
- [ ] **E-08 — Close out after fresh merge approval**
  - **Action:** CG-16..18을 fresh approval 후에만 수행한다.
  - **Evidence:** merge SHA, develop parity, cleanup.
  - **Failure:** 승인 전에는 `PENDING`.

## Superpowers SPW-01..SPW-05

- [ ] **SPW-01 — 독자·목적·근거 고정**
  - **Action:** spec/plan/review/lesson의 독자·목적·source·unknowns를 기록한다.
  - **Evidence:** 각 artifact의 source ledger.
  - **Failure:** 근거 없는 문장을 삭제·한정한다.
- [ ] **SPW-02 — artifact contract 충족**
  - **Action:** acceptance, task, risks, review, lesson 구조를 채운다.
  - **Evidence:** 문서 heading/table과 Issue #439 mapping.
  - **Failure:** 누락 구조를 보충한다.
- [ ] **SPW-03 — 한국어 technical register**
  - **Action:** Korean naturalness checklist와 terminology audit를 실행한다.
  - **Evidence:** changed Korean files findings=0 또는 의도적 예외.
  - **Failure:** translationese·marketing·용어 충돌을 repair한다.
- [ ] **SPW-04 — technical meaning traceability**
  - **Action:** issue→spec→plan→code/test→review→lesson을 대조한다.
  - **Evidence:** acceptance-to-test mapping.
  - **Failure:** drift가 있으면 artifact를 다시 연다.
- [ ] **SPW-05 — rendered read-back**
  - **Action:** Markdown heading/table/code/link를 최종 read-back한다.
  - **Evidence:** `git diff --check`와 렌더 구조 점검.
  - **Failure:** unchecked writer row로 둔다.

## 7-Tier / Kotlin scope

- [ ] **7T-01 — 요구사항·설계**
  - **Action:** Issue #439 완료 조건과 existing #24 boundary를 대조한다.
  - **Evidence:** spec review.
  - **Failure:** 요구사항 drift repair.
- [ ] **7T-02 — 아키텍처·재사용**
  - **Action:** existing Capacitor app, report script, workflow와 test-only APIs를 재사용한다.
  - **Evidence:** dependency/diff inventory.
  - **Failure:** 새 abstraction/dependency 근거 보강.
- [ ] **7T-03 — 보안**
  - **Action:** report에서 token/password/raw output을 차단하고 auth model을 변경하지 않는다.
  - **Evidence:** forbidden-term tests와 diff scan.
  - **Failure:** P0/P1 repair.
- [ ] **7T-04 — 운영·CI**
  - **Action:** exact SHA, device profile, viewport/orientation, artifact를 남긴다.
  - **Evidence:** workflow/report artifact.
  - **Failure:** report 유실이면 CI를 fail한다.
- [ ] **7T-05 — 테스트**
  - **Action:** RED/GREEN report·workflow contract 및 native UI harness를 검증한다.
  - **Evidence:** local tests와 exact-head native CI.
  - **Failure:** flaky/부분 증거는 PASS가 아니다.
- [ ] **7T-06 — 문서·사용성**
  - **Action:** 한국어 README/spec/plan/review/lesson과 issue metadata를 갱신한다.
  - **Evidence:** links/terms/read-back.
  - **Failure:** 문서 drift repair.
- [ ] **7T-07 — 통합·회귀**
  - **Action:** browser contract와 native UI evidence의 분리를 검증한다.
  - **Evidence:** workflow validator, full frontend tests, CI result.
  - **Failure:** 회귀 발생 시 affected proof 재실행.
- **Kotlin pattern / bluetape4k-assertions: N/A**
  - **Scope evidence:** #439 변경 대상은 `.github/workflows`, Angular/Node scripts,
    Android Java instrumentation, iOS Swift XCTest와 한국어 문서다. Kotlin 파일과
    `bluetape4k-assertions` 호출은 변경하지 않으며, 이를 이유로 raw assertion을 새로
    추가하지 않는다. 기존 Kotlin 모듈은 범위 밖이다.

## 조건부 N/A 및 외부 hold

- `N/A` — chezmoi source/live parity: Codex/skill/config surface를 변경하지 않는다.
- `N/A` — production dependency/API/schema/migration: test-only Android dependency와
  built-in XCTest만 사용하고 production runtime graph는 변경하지 않는다.
- `N/A` — Testcontainers/DB/Redis: native UI harness scope에 없다.
- `N/A` — diagram/visual asset: screenshot은 CI evidence artifact이며 README visual asset이 아니다.
- `N/A (single-developer lane)` — 추가 maintainer reviewer가 없는 개인 저장소 작업으로,
  CG-14의 human-review subgate만 N/A로 기록한다. independent local 7-Tier review와
  exact-head CI는 여전히 필수다.
- `PENDING` — CG-11..CG-18, E-07..E-08: final exact head push/PR/CI 후 fresh merge
  approval이 필요하다.

## Evidence ledger

| 시점 | 명령/산출물 | 결과 |
|---|---|---|
| 2026-08-28 | `git status`, `git worktree list`, `git rev-parse` | root dirty state 보존, isolated worktree와 `origin/develop@e6937e02` 확인 |
| 2026-08-28 | Issue #439/GNO/native #24 read-back | 기존 smoke/browser contract와 #439 native UI gap 확인 |
| 2026-08-28 | `bluetape-flow.py init/run-start/topology-register/mutation-check` | run `20260828T044953Z-82cd4ad3`, sequence `7`, target scope 확인 |
| pending | RED/GREEN report/workflow tests | 구현 후 기록 |
| pending | Android/iOS native UI CI | exact-head remote evidence 필요 |

## Current DoD

`Required checks: 0/??; N/A: 0; Blocked: 0`.
현재 모든 구현·검증 row는 unchecked이며, PR/merge rows는 fresh evidence 전까지
`PENDING`이다. 최종 count는 구현과 live read-back 후 갱신한다.
