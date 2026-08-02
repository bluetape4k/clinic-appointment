# Issue #170 Waitlist Core Workflow Checklist

Status: `PRE-PR VERIFIED` — PR/merge not in scope

## Classification

- Work type: `Type-A / Full Feature`
- Basis: new ecosystem dependency, multi-layer domain/persistence behavior,
  database migrations, concurrency/CAS semantics, and future public integration
  boundaries.
- Required: all `WF-*`, `CL-*`, `CG-01..CG-10`, and `A-01..A-09` rows;
  `A-05` and `Step 4-P` are required because the scope includes concurrency,
  database consistency, and lifecycle recovery.
- Conditional: `CG-11..CG-18`, `A-10..A-12`, and the cleanup branch `Step 4-S`;
  they require explicit PR/merge authority or a triggered cleanup scope. The
  concrete no-PR branch is recorded below for this handoff.
- N/A: `CG-X01` (no tag, release, dispatch, publication, deletion, or other
  irreversible non-PR action is in the approved scope); Type-B/C/D/E/F/P leaf
  branches (the issue is classified as Type-A).
- Repair note: `CL-01` was not instantiated before the existing spec commit
  `5b48fd9`. The receipt liveness gap was repaired through the registered
  recovery lane and fresh mutation checks. The design was revised through
  commits `a3cfcb2`, `0e4c786`, `1f9388b`, and `b041179`; no implementation
  source has been changed. Step 2-R now passes at `b041179`. Receipt ownership
  was transferred through the coordinator at sequence 31; the final review and
  lesson are tracked in `docs/review/2026-08-02-issue-170-waitlist-core-step-6r-code-review.md`
  and `docs/lessons/2026-08-02-issue-170-waitlist-core.md`.

## Router gates

- [x] **WF-01 — Classify the work type before mutation**
  - **Action:** Record the Type-A classification and its concrete complexity signals.
  - **Evidence:** This checklist classification, Issue #170 scope, and receipt `workflow_type: A`.
  - **Failure:** Stop and reclassify before any downstream mutation.

- [x] **WF-02 — Publish the ordered execution plan before execution**
  - **Action:** Publish the ordered Type-A workflow and keep the plan current after each gate transition.
  - **Evidence:** User-visible ordered plan and current `update_plan` state.
  - **Failure:** Stop; restore the missing plan before proceeding.

- [x] **WF-03 — Obtain approval at the correct gate**
  - **Action:** Bind approval to the approved Issue #170 phase-one scope and later artifact gates.
  - **Evidence:** Receipt `plan_approved` event and exact user approval messages.
  - **Failure:** Keep the dependent gate pending; never infer approval from silence.

- [x] **WF-04 — Load router, common, and leaf instructions**
  - **Action:** Read `bluetape-workflow`, `bluetape-full-feature`, required references, and language/domain skills before their gates.
  - **Evidence:** Read paths and selected-skill record in the execution notes.
  - **Failure:** Stop and load the missing instruction surface before editing.

- [x] **WF-05 — Register receipt topology and dependency order**
  - **Action:** Keep the approved component topology and dependency order synchronized with the current workflow/checklist.
  - **Evidence:** Run `20260801T131046Z-09e9909d`, manifest hash, topology registration, and completion checks.
  - **Failure:** Stop downstream checks and repair the receipt/topology first.

- [x] **WF-06 — Maintain liveness and lifecycle evidence**
  - **Action:** Repair the expired main-lane lease/deadline through the coordinator protocol and record fresh evidence before resuming work.
  - **Evidence:** `resume-check`, recovery/heartbeat or bounded fallback events, and a fresh liveness result.
  - **Failure:** Keep the run pending or recover it through the prescribed replacement/fallback path; do not advance on stale liveness.

## Common gates

- [x] **CL-01 — Create before mutation**
  - **Action:** Instantiate this router, common, and Type-A checklist before any further repository mutation.
  - **Evidence:** This file exists in the feature worktree and its IDs/applicability have been read back.
  - **Failure:** STOP; reconstruct before any further mutation.

- [x] **CL-02 — Classify every item**
  - **Action:** Mark every row required, conditional, or N/A with concrete scope evidence.
  - **Evidence:** Classification section has no unclassified row.
  - **Failure:** Treat an unclassified row as required and unchecked.

- [x] **CL-03 — Respect dependency order**
  - **Action:** Execute rows top to bottom and rerun downstream proof after every repaired gate.
  - **Evidence:** Design repair commits precede the final Step 2-R artifact at `b041179`; review and mutation-check ran after the final design/runbook edits.
  - **Failure:** Stop and rerun all affected downstream proof.

- [x] **CL-04 — Record evidence immediately**
  - **Action:** Attach command/file/result evidence when each row is checked.
  - **Evidence:** `docs/review/2026-08-01-issue-170-waitlist-core-spec-review-iteration-2.md`, the runbook, and fresh `git diff --check`/`mutation-check` evidence are in the feature worktree.
  - **Failure:** Leave the row unchecked and treat late reconstruction as repair.

- [x] **CL-05 — Fail closed**
  - **Action:** Keep pending/failed rows unchecked and block dependent work.
  - **Evidence:** A-04 preceded implementation; only implementation rows with fresh proof are checked, while pre-PR and PR rows remain pending.
  - **Failure:** Reverify every dependent item before continuing.

- [x] **CL-06 — Repair skipped or reordered work**
  - **Action:** Repair the missed checklist/liveness gates and rerun affected evidence.
  - **Evidence:** Receipt recovery evidence, four repair commits, final runbook, and Step 2-R `PASS` review at `b041179`.
  - **Failure:** Final status remains `BLOCKED`.

- [x] **CL-07 — Refresh irreversible holds**
  - **Action:** Reread authority, target, head, and merge/release holds immediately before any external side effect.
  - **Evidence:** User-authorized local lesson/implementation commit is the only requested side effect; current worktree, branch, target paths, and no-PR/no-push/no-merge hold were reread immediately before commit.
  - **Failure:** Do not execute the side effect.

- [x] **CL-08 — Count before completion**
  - **Action:** Reconcile the checklist using `Required checks: X/Y; N/A: N; Blocked: N` and list every unchecked ID.
  - **Evidence:** `Required checks: 47/47; N/A: 13; Blocked: 0`; every row is checked or has a concrete N/A branch, and no unchecked ID remains.
  - **Failure:** Completion claim is forbidden.

- [x] **CG-01 — Re-read authority**
  - **Action:** Read the governing `AGENTS.md`, selected skills, current status/diff, and approved Issue #170 scope.
  - **Evidence:** Paths, status/diff, classification, and exact authority.
  - **Failure:** STOP before editing.

- [x] **CG-02 — Query historical/current evidence**
  - **Action:** Preserve the GNO/GitHub/local evidence queries and direct-source fallback used for the design.
  - **Evidence:** Query terms, collections, anchors, and decisive results.
  - **Failure:** Stop decisions that depend on missing history/current state.

- [x] **CG-03 — Protect user work and boundaries**
  - **Action:** Keep the isolated feature worktree and exclude root checkout dirty Issue #176 changes.
  - **Evidence:** Worktree, branch/base/upstream, root status, and scoped paths.
  - **Failure:** Preserve safely or block; never discard unrelated work.

- [x] **CG-04 — Apply policy and audience boundaries**
  - **Action:** Enforce English operating artifacts, Korean user-facing technical docs, and repo-local Kotlin/Exposed rules.
  - **Evidence:** Governing paths, touched surfaces, and language/domain rules.
  - **Failure:** Repair policy or language drift before proceeding.

- [x] **CG-05 — Reuse ecosystem patterns**
  - **Action:** Reuse existing appointment FSM, Exposed transaction, migration, testing, and reliability-decision patterns before adding abstractions.
  - **Evidence:** Local/sibling anchors and explicit adopt/borrow/reject rationale.
  - **Failure:** Stop new abstraction/dependency work.

- [x] **CG-06 — Prove public and documentation contracts**
  - **Action:** Update API/KDoc/README/registration surfaces required by the approved implementation; keep excluded integrations out of scope.
  - **Evidence:** New/updated public and internal Kotlin surfaces carry Korean KDoc; the phase-one core intentionally adds no HTTP, notification, outbox, scheduler, or README integration surface. Excluded adapters remain documented in the approved plan.
  - **Failure:** Block undocumented or unregistered behavior.

- [x] **CG-07 — Lock behavior and run targeted proof**
  - **Action:** Use RED/GREEN tests and targeted diagnostics for every changed behavior.
  - **Evidence:** Targeted waitlist/state/reliability tests passed after the test-first implementation; the final targeted commands reported `CORE_TARGETED=PASS` and `CORE_TARGETED_POST_KOTLIN=PASS`, and the Kotlin safety scan reported `STATIC_SCAN_FINAL=PASS`.
  - **Failure:** Return to implementation and investigate retry-only passes.

- [x] **CG-08 — Serialize heavyweight checks**
  - **Action:** Run real DB, concurrency, and shared-state checks sequentially.
  - **Evidence:** Sequential H2, PostgreSQL, and MySQL migration commands reported `H2_MIGRATION=PASS`, `POSTGRES_MIGRATION=PASS`, and `MYSQL_MIGRATION=PASS`; contention/restart tests passed in the same sequential lane.
  - **Failure:** Discard ambiguous parallel evidence and rerun safely.

- [x] **CG-09 — Evaluate the lesson gate**
  - **Action:** Record the workflow repair and any reusable reliability/concurrency lesson before PR delivery.
  - **Evidence:** Tracked Korean lesson `docs/lessons/2026-08-02-issue-170-waitlist-core.md` covers context, decision, surprise/failure, outcome, receipt recovery, verification, and future guards; it is included in the local handoff commit.
  - **Failure:** Repair lesson evidence before pre-PR review.

- [x] **CG-10 — Converge the final pre-PR proof**
  - **Action:** Complete the leaf pre-PR rows, final review, fresh checks, and scoped commit.
  - **Evidence:** Final review `docs/review/2026-08-02-issue-170-waitlist-core-step-6r-code-review.md` is `PASS` with P0=0/P1=0; fresh tests/build/static/diff checks pass; exact local head is reported with the handoff receipt.
  - **Failure:** Keep PR creation blocked.

- [x] **CG-11 — Verify PR delivery authority** *(conditional)*
  - **Action:** Verify explicit PR authority, repository, base, and head after CG-10.
  - **Evidence:** N/A — the user requested pre-PR lesson/commit and receipt recovery only; no PR repository/base/head authority was granted and `gh pr list --head feat/issue-170-waitlist-core` is empty.
  - **Failure:** Stop before PR creation.

- [x] **CG-12 — Publish the exact head branch** *(conditional)*
  - **Action:** Push the authorized head and read back the matching remote SHA.
  - **Evidence:** N/A — no push was authorized or executed; the local feature worktree remains the publication boundary.
  - **Failure:** Stop and repair publication.

- [x] **CG-13 — Create and verify the PR** *(conditional)*
  - **Action:** Create/update the PR with metadata parity and a final `## DoD Status` section.
  - **Evidence:** N/A — PR creation is outside this request; no live PR exists for the feature head.
  - **Failure:** Repair the live PR before CI/review.

- [x] **CG-14 — Pass CI and live human review** *(conditional)*
  - **Action:** Wait for exact-head CI and reread reviews/threads and required artifacts.
  - **Evidence:** N/A — no PR/remote CI/review thread was created; local final review is `PASS`, P0/P1 are zero, and the absence of remote evidence is intentional.
  - **Failure:** Keep pending or return to repair.

- [x] **CG-15 — Report merge-ready** *(conditional)*
  - **Action:** Reconcile all rows and report exact PR/head with pending merge IDs.
  - **Evidence:** N/A — this is a pre-PR handoff, not a merge-ready report; the final report contains the exact local head and receipt state instead.
  - **Failure:** Repair missing evidence; do not request merge approval.

- [x] **CG-16 — Obtain fresh merge approval** *(conditional)*
  - **Action:** Obtain approval tied to the exact current PR/head after CG-15 and refresh CL-07.
  - **Evidence:** N/A — no PR/head was presented for merge approval and merge is not in scope.
  - **Failure:** Remain pending; never infer authority.

- [x] **CG-17 — Execute and verify the merge** *(conditional)*
  - **Action:** Merge only after CG-16 and verify live merged state/SHA.
  - **Evidence:** N/A — no merge was authorized or executed.
  - **Failure:** Stop and diagnose.

- [x] **CG-18 — Synchronize and clean up** *(conditional)*
  - **Action:** Sync local checkout and remove only proven merged worktrees/branches.
  - **Evidence:** N/A — publication/merge did not occur, so worktree and branch cleanup would be premature and is intentionally deferred.
  - **Failure:** Preserve ambiguous state and report pending.

- [x] **CG-X01 — Authorize another irreversible action** *(N/A)*
  - **Action:** No tag/release/dispatch/publication/deletion/non-PR irreversible action is in scope.
  - **Evidence:** Approved Issue #170 phase-one scope excludes these actions.
  - **Failure:** If scope changes, instantiate and complete this branch before acting.

## Type-A gates

- [x] **A-01 — Isolate and confirm requirements**
  - **Action:** Confirm the feature worktree, preserved root changes, Issue #170 scope, exclusions, compatibility, side effects, and stop condition.
  - **Evidence:** Worktree/branch/base/cwd and approved requirements.
  - **Failure:** Stop before research or artifacts.

- [x] **A-02 — Ground the design in current evidence**
  - **Action:** Inspect local patterns/history, GNO/GitHub evidence, and dependency/catalog authority.
  - **Evidence:** Anchors, source evidence, and adopt/borrow/reject rationale.
  - **Failure:** Do not design from recall.

- [x] **A-03 — Approve and review the design spec**
  - **Action:** Review the approved spec with six independent perspectives plus main integration and fix all P0/P1 findings.
  - **Evidence:** Approved spec, `docs/review/2026-08-01-issue-170-waitlist-core-spec-review-iteration-2.md`, exact commit `b041179`, integrated P0=0/P1=0.
  - **Failure:** Revise/reapprove material changes and keep planning blocked.

- [x] **A-04 — Approve and review the implementation plan**
  - **Action:** Write the ordered plan and run all six plan perspectives plus integration.
  - **Evidence:** Plan `docs/superpowers/plans/2026-08-01-issue-170-waitlist-core.md` at `a2e67d5`, review `docs/review/2026-08-01-issue-170-waitlist-core-plan-review.md`, integrated PASS, P0=0/P1=0/P2=0/P3=0, and explicit chat approval `승인, $bluetape-kotlin-patterns 을 철저히 준수하면서 구현해라`.
  - **Failure:** Repair ordering/proof/ownership/hazard gaps before code; do not start implementation without approval.

- [x] **A-05 — Predict triggered risks**
  - **Action:** Record concurrency, DB migration, CAS, privacy, dependency, and recovery risks with signals, mitigations, and rollback/rerun points.
  - **Evidence:** Plan commit `a2e67d5`, section `실행 위험과 중단 기준`, and the integrated A-04 review at `docs/review/2026-08-01-issue-170-waitlist-core-plan-review.md`.
  - **Failure:** Complete risk prediction before implementation.

- [x] **A-06 — Implement with test-first proof**
  - **Action:** Use TDD and Kotlin/Exposed/domain skills with disjoint write scopes.
  - **Evidence:** Approved task scopes are implemented in the isolated feature worktree; targeted waitlist/reliability/state tests, contention/restart checks, Kotlin safety scans, and `git diff --check` are green.
  - **Failure:** Return to the failing behavior or violated boundary.

- [x] **A-07 — Verify tests, spec, plan, and repository hazards**
  - **Action:** Run targeted then proportional broader validation and compare the result with the exact approved artifacts.
  - **Evidence:** Full `:appointment-core:test :appointment-api:test` and `:appointment-core:build :appointment-api:build` both exited 0; all three migration dialects and the approved phase-one exclusions were checked against the plan.
  - **Failure:** Return to implementation or reopen the artifact.

- [x] **A-08 — Converge the final pre-PR review**
  - **Action:** Run the final checklist, six code-review perspectives, and main integration; fix blockers and rerun affected proof.
  - **Evidence:** Final review `docs/review/2026-08-02-issue-170-waitlist-core-step-6r-code-review.md` is `PASS` with P0=0/P1=0; fresh tests/build/static checks and `git diff --check` pass after the resolved claim/recovery/schema findings.
  - **Failure:** Keep PR creation blocked.

- [x] **A-09 — Commit durable learning**
  - **Action:** Commit a lesson covering the checklist/liveness miss and future guard before PR creation.
  - **Evidence:** `docs/lessons/2026-08-02-issue-170-waitlist-core.md` is tracked in the local Lore handoff commit and contains context, decision, outcome, proof, the checklist/liveness miss, receipt recovery, and future guards.
  - **Failure:** Untracked or evidence-only lesson does not unblock delivery.

- [x] **A-10 — Complete authorized PR delivery through live CI and review** *(conditional)*
  - **Action:** Complete CG-11 through CG-14 on the exact head, or record the concrete no-PR branch.
  - **Evidence:** N/A — concrete no-PR branch: the request stops after local pre-PR lesson/commit and receipt recovery; no remote publication or PR authority exists.
  - **Failure:** Keep delivery pending or failed.

- [x] **A-11 — Capture knowledge and report merge readiness** *(conditional)*
  - **Action:** Capture durable knowledge and render phase-aware counts with exact PR/head state, or the no-PR DoD.
  - **Evidence:** N/A for merge readiness; durable knowledge is captured in the lesson, and the no-PR DoD reports `Required checks: 47/47; N/A: 13; Blocked: 0` with exact local head and receipt completion proof.
  - **Failure:** Expose the blocking row and repair action.

- [x] **A-12 — Close out only after fresh merge approval** *(conditional)*
  - **Action:** After fresh approval, complete CG-16 through CG-18, or record the concrete no-PR branch.
  - **Evidence:** N/A — no-PR branch; merge approval, merge SHA, sync, and cleanup are deferred until a future separately authorized PR workflow.
  - **Failure:** Keep pending/blocked and preserve state.

## Triggered implementation sub-gates

- [x] **Step 4-S — Cleanup branch** *(conditional)*
  - **Action:** Trigger only if the implementation introduces substantial duplication, verbosity, generated residue, or broad refactor noise.
  - **Evidence:** N/A — final review found no substantial duplication, generated residue, or broad refactor noise requiring a separate cleanup pass; changing structure now would expand the approved scope.
  - **Failure:** Do not perform unplanned cleanup.

- [x] **Step 4-P — Performance and stability**
  - **Action:** Review DB contention, CAS retries, transaction duration, lifecycle cleanup, and multi-backend behavior.
  - **Evidence:** `WaitlistContentionLoadTest` passed with `pool=16 attempts=100 p50=31ms p95=172ms p99=247ms`; restart recovery passed with one terminal transition/event and zero duplicate work; H2/PostgreSQL/MySQL migration checks passed sequentially.
  - **Failure:** Fix P0/P1 and rerun affected tests.

## Current stop condition

Implementation is verified after the written plan passed A-04 and received
explicit user approval. Step 2-R is no longer a blocker. Kotlin/Exposed
implementation, targeted/full tests, migration matrix, contention/restart
proof, static diagnostics, final pre-PR review, and durable lesson commit are
complete locally. The receipt was recovered through owner transfer and its
failed-main history remains preserved. PR/merge/push are intentionally outside
this handoff.

Step 2-R evidence is recorded at
`docs/review/2026-08-01-issue-170-waitlist-core-spec-review-iteration-2.md`.
Its integrated verdict is `PASS` with P0=0 and P1=0; P2 items are explicit
plan/adapter follow-ups.

A-04 review evidence is recorded at
`docs/review/2026-08-01-issue-170-waitlist-core-plan-review.md`. Its integrated
verdict is `PASS` with P0=0, P1=0, P2=0, and P3=0; implementation approval is
recorded in the current chat message.

Pre-PR handoff required checks: `47/47` checked; N/A: `13`; Blocked: `0`.
Unchecked IDs: none. Conditional PR/merge rows use the concrete no-PR branch
and are not evidence of remote delivery.

## Implementation evidence (2026-08-02)

- `./gradlew :appointment-core:test :appointment-api:test --console=plain -q` → exit 0; latest XML totals are core `590/0/0/0`, API `593/0/0/2` (tests/failures/errors/skipped).
- `./gradlew --no-daemon :appointment-core:build :appointment-api:build --console=plain -q` → `FULL_BUILDS_POST_LOG=PASS`.
- Final invariant/KDoc cleanup compile lane → `FINAL_CORE_BUILD_API_COMPILE=PASS`; repository exception assertion → `REPOSITORY_ASSERTION=PASS`.
- Sequential targeted/migration lane → `CORE_TARGETED=PASS`, `CORE_TARGETED_POST_KOTLIN=PASS`, `H2_MIGRATION=PASS`, `POSTGRES_MIGRATION=PASS`, `MYSQL_MIGRATION=PASS`.
- Kotlin safety scan (production `!!`, operational print, suspend `runCatching`, blocking/raw SQL patterns) → `STATIC_SCAN=PASS`; `git diff --check` → `DIFF_CHECK=PASS`.
- No PR, push, merge, branch deletion, release, or tag action was executed; the current feature worktree remains the reviewable handoff boundary.
