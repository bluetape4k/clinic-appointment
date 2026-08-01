# Issue #170 Waitlist Core Workflow Checklist

Status: `PENDING` — procedural repair in progress

## Classification

- Work type: `Type-A / Full Feature`
- Basis: new ecosystem dependency, multi-layer domain/persistence behavior,
  database migrations, concurrency/CAS semantics, and future public integration
  boundaries.
- Required: all `WF-*`, `CL-*`, `CG-01..CG-10`, and `A-01..A-09` rows;
  `A-05` and `Step 4-P` are required because the scope includes concurrency,
  database consistency, and lifecycle recovery.
- Conditional: `CG-11..CG-18`, `A-10..A-12`, and the cleanup branch `Step 4-S`;
  they require explicit PR/merge authority or a triggered cleanup scope.
- N/A: `CG-X01` (no tag, release, dispatch, publication, deletion, or other
  irreversible non-PR action is in the approved scope); Type-B/C/D/E/F/P leaf
  branches (the issue is classified as Type-A).
- Repair note: `CL-01` was not instantiated before the existing spec commit
  `5b48fd9`. No implementation source was changed; downstream work is paused
  until this checklist is read back, the receipt liveness gap is repaired, and
  Step 2-R is rerun.

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

- [ ] **CL-03 — Respect dependency order**
  - **Action:** Execute rows top to bottom and rerun downstream proof after every repaired gate.
  - **Evidence:** Checklist order, receipt sequence, and artifact timestamps show no backward jump.
  - **Failure:** Stop and rerun all affected downstream proof.

- [ ] **CL-04 — Record evidence immediately**
  - **Action:** Attach command/file/result evidence when each row is checked.
  - **Evidence:** Evidence is beside the checked row and readable from the worktree/receipt.
  - **Failure:** Leave the row unchecked and treat late reconstruction as repair.

- [ ] **CL-05 — Fail closed**
  - **Action:** Keep pending/failed rows unchecked and block dependent work.
  - **Evidence:** Current pending/failed IDs and the stopped dependent branch are recorded.
  - **Failure:** Reverify every dependent item before continuing.

- [ ] **CL-06 — Repair skipped or reordered work**
  - **Action:** Repair the missed checklist/liveness gates and rerun affected evidence.
  - **Evidence:** Repair events, refreshed checklist rows, and fresh Step 2-R results.
  - **Failure:** Final status remains `BLOCKED`.

- [ ] **CL-07 — Refresh irreversible holds**
  - **Action:** Reread authority, target, head, and merge/release holds immediately before any external side effect.
  - **Evidence:** Fresh target/authority evidence immediately before the side effect.
  - **Failure:** Do not execute the side effect.

- [ ] **CL-08 — Count before completion**
  - **Action:** Reconcile the checklist using `Required checks: X/Y; N/A: N; Blocked: N` and list every unchecked ID.
  - **Evidence:** Totals reconcile with this file and the final report.
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

- [ ] **CG-06 — Prove public and documentation contracts**
  - **Action:** Update API/KDoc/README/registration surfaces required by the approved implementation; keep excluded integrations out of scope.
  - **Evidence:** Changed paths and parity/registration proof, or concrete scoped N/A evidence.
  - **Failure:** Block undocumented or unregistered behavior.

- [ ] **CG-07 — Lock behavior and run targeted proof**
  - **Action:** Use RED/GREEN tests and targeted diagnostics for every changed behavior.
  - **Evidence:** Fresh RED/GREEN commands and results.
  - **Failure:** Return to implementation and investigate retry-only passes.

- [ ] **CG-08 — Serialize heavyweight checks**
  - **Action:** Run real DB, concurrency, and shared-state checks sequentially.
  - **Evidence:** Command order and results.
  - **Failure:** Discard ambiguous parallel evidence and rerun safely.

- [ ] **CG-09 — Evaluate the lesson gate**
  - **Action:** Record the workflow repair and any reusable reliability/concurrency lesson before PR delivery.
  - **Evidence:** Tracked lesson path/commit, or reviewed concrete N/A rationale.
  - **Failure:** Repair lesson evidence before pre-PR review.

- [ ] **CG-10 — Converge the final pre-PR proof**
  - **Action:** Complete the leaf pre-PR rows, final review, fresh checks, and scoped commit.
  - **Evidence:** Final diff, P0/P1 counts, checks, lesson result, and exact head SHA.
  - **Failure:** Keep PR creation blocked.

- [ ] **CG-11 — Verify PR delivery authority** *(conditional)*
  - **Action:** Verify explicit PR authority, repository, base, and head after CG-10.
  - **Evidence:** Exact authority and refs, or a concrete N/A branch decision.
  - **Failure:** Stop before PR creation.

- [ ] **CG-12 — Publish the exact head branch** *(conditional)*
  - **Action:** Push the authorized head and read back the matching remote SHA.
  - **Evidence:** Local/remote SHA parity.
  - **Failure:** Stop and repair publication.

- [ ] **CG-13 — Create and verify the PR** *(conditional)*
  - **Action:** Create/update the PR with metadata parity and a final `## DoD Status` section.
  - **Evidence:** Live PR URL/number, head, metadata, and body read-back.
  - **Failure:** Repair the live PR before CI/review.

- [ ] **CG-14 — Pass CI and live human review** *(conditional)*
  - **Action:** Wait for exact-head CI and reread reviews/threads and required artifacts.
  - **Evidence:** Green checks, exact head, no unresolved blockers, P0/P1 zero.
  - **Failure:** Keep pending or return to repair.

- [ ] **CG-15 — Report merge-ready** *(conditional)*
  - **Action:** Reconcile all rows and report exact PR/head with pending merge IDs.
  - **Evidence:** User-visible report and checklist count.
  - **Failure:** Repair missing evidence; do not request merge approval.

- [ ] **CG-16 — Obtain fresh merge approval** *(conditional)*
  - **Action:** Obtain approval tied to the exact current PR/head after CG-15 and refresh CL-07.
  - **Evidence:** Fresh user approval and refreshed target/hold.
  - **Failure:** Remain pending; never infer authority.

- [ ] **CG-17 — Execute and verify the merge** *(conditional)*
  - **Action:** Merge only after CG-16 and verify live merged state/SHA.
  - **Evidence:** Merge result, strategy, and SHA.
  - **Failure:** Stop and diagnose.

- [ ] **CG-18 — Synchronize and clean up** *(conditional)*
  - **Action:** Sync local checkout and remove only proven merged worktrees/branches.
  - **Evidence:** Local/upstream parity and cleanup list.
  - **Failure:** Preserve ambiguous state and report pending.

- [ ] **CG-X01 — Authorize another irreversible action** *(N/A)*
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

- [ ] **A-03 — Approve and review the design spec**
  - **Action:** Review the approved spec with six independent perspectives plus main integration and fix all P0/P1 findings.
  - **Evidence:** Spec path, review table, and latest integrated P0=0/P1=0.
  - **Failure:** Revise/reapprove material changes and keep planning blocked.

- [ ] **A-04 — Approve and review the implementation plan**
  - **Action:** Write the ordered plan and run all six plan perspectives plus integration.
  - **Evidence:** Plan path, traceability map, review table, and P0=0/P1=0.
  - **Failure:** Repair ordering/proof/ownership/hazard gaps before code.

- [ ] **A-05 — Predict triggered risks**
  - **Action:** Record concurrency, DB migration, CAS, privacy, dependency, and recovery risks with signals, mitigations, and rollback/rerun points.
  - **Evidence:** Risk entries attached to plan tasks.
  - **Failure:** Complete risk prediction before implementation.

- [ ] **A-06 — Implement with test-first proof**
  - **Action:** Use TDD and Kotlin/Exposed/domain skills with disjoint write scopes.
  - **Evidence:** RED/GREEN sequence, scoped diff, diagnostics, and cleanup/performance results.
  - **Failure:** Return to the failing behavior or violated boundary.

- [ ] **A-07 — Verify tests, spec, plan, and repository hazards**
  - **Action:** Run targeted then proportional broader validation and compare the result with the exact approved artifacts.
  - **Evidence:** Fresh commands, verifier PASS, acceptance mapping, and hazard checks.
  - **Failure:** Return to implementation or reopen the artifact.

- [ ] **A-08 — Converge the final pre-PR review**
  - **Action:** Run the final checklist, six code-review perspectives, and main integration; fix blockers and rerun affected proof.
  - **Evidence:** Final diff, review artifact, diff check, and P0/P1 zero.
  - **Failure:** Keep PR creation blocked.

- [ ] **A-09 — Commit durable learning**
  - **Action:** Commit a lesson covering the checklist/liveness miss and future guard before PR creation.
  - **Evidence:** Tracked lesson commit with context, decision, outcome, proof, miss, and guard.
  - **Failure:** Untracked or evidence-only lesson does not unblock delivery.

- [ ] **A-10 — Complete authorized PR delivery through live CI and review** *(conditional)*
  - **Action:** Complete CG-11 through CG-14 on the exact head, or record the concrete no-PR branch.
  - **Evidence:** Authority, remote parity, live PR, review convergence, and CI.
  - **Failure:** Keep delivery pending or failed.

- [ ] **A-11 — Capture knowledge and report merge readiness** *(conditional)*
  - **Action:** Capture durable knowledge and render phase-aware counts with exact PR/head state, or the no-PR DoD.
  - **Evidence:** Knowledge result and `Required checks: X/Y; N/A: N; Blocked: 0`.
  - **Failure:** Expose the blocking row and repair action.

- [ ] **A-12 — Close out only after fresh merge approval** *(conditional)*
  - **Action:** After fresh approval, complete CG-16 through CG-18, or record the concrete no-PR branch.
  - **Evidence:** Approval, merge SHA, sync, and cleanup, or valid N/A evidence.
  - **Failure:** Keep pending/blocked and preserve state.

## Triggered implementation sub-gates

- [ ] **Step 4-S — Cleanup branch** *(conditional)*
  - **Action:** Trigger only if the implementation introduces substantial duplication, verbosity, generated residue, or broad refactor noise.
  - **Evidence:** Cleanup plan, behavior-locking tests, and rerun results, or reviewed scoped N/A evidence.
  - **Failure:** Do not perform unplanned cleanup.

- [ ] **Step 4-P — Performance and stability**
  - **Action:** Review DB contention, CAS retries, transaction duration, lifecycle cleanup, and multi-backend behavior.
  - **Evidence:** Performance/stability scan, sequential concurrency/database checks, and mitigation results.
  - **Failure:** Fix P0/P1 and rerun affected tests.

## Current stop condition

Implementation is forbidden until `CL-01..CL-06`, `WF-06`, and `A-03` are
checked with fresh evidence. The current run remains `PENDING`.

Required checks: `15/46` checked so far; N/A: `1`; Blocked: `0`.
Unchecked IDs: all executable rows above.
