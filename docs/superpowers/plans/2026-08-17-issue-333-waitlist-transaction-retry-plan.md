# Issue #333 waitlist transaction retry 구현 계획

> **For agentic workers:** 이 계획은 현재 main session이 task-by-task로 실행한다. 각 단계는 checkbox로 추적하고, RED 증거 없이 production code를 작성하지 않는다.

**Goal:** waitlist contention retry가 abort된 Exposed transaction을 재사용하지 않고, 전체 delivery 작업을 fresh PostgreSQL transaction으로 재실행하도록 고정한다.

**Architecture:** `WaitlistDeliveryRepository.claim`은 단일 attempt만 수행한다. 기존 `withContentionRetry`는 transaction 밖의 caller가 소유하며, callback은 `inTopLevelTransaction(database)` 안에서 claim부터 `WaitlistDeliveryService.process`의 offer/hold, notification enqueue, terminal fence까지 실행한다. PostgreSQL strategy는 `55P03` lock timeout을 retryable contention으로 분류한다.

**Tech Stack:** Kotlin 2.3, Java 25, Exposed 1.4.0 JDBC, PostgreSQL `PostgreSQLServer.Launcher.postgres`, JUnit 5, bluetape4k assertions, Gradle.

---

## 파일 구조와 책임

| 구분 | 파일 | 책임 |
|---|---|---|
| production | `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt` | claim 단일 attempt, retryable SQLSTATE 판정, retry coordinator KDoc |
| production docs | `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistDeliveryService.kt` | caller-owned 전체 transaction 및 retry 호출 경계 KDoc |
| unit regression | `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryRepositoryTest.kt` | 55P03 dialect 판정과 non-retryable/기존 retry policy 계약 |
| PostgreSQL regression | `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryPostgreSqlContentionTest.kt` | 실제 lock timeout abort/fresh retry와 serializable `40001` fresh retry |
| design | `docs/superpowers/specs/2026-08-17-issue-333-waitlist-transaction-retry-design.md` | 선택 경계와 수용 기준 |
| plan | `docs/superpowers/plans/2026-08-17-issue-333-waitlist-transaction-retry-plan.md` | 실행 순서와 검증 명령 |
| lesson | `docs/lessons/2026-08-17-issue-333-waitlist-transaction-retry.md` | 재사용 가능한 transaction/retry 교훈 |

## Spec-to-plan traceability

| Spec acceptance | Plan task / proof |
|---|---|
| `claim` 단일 attempt | Task 4 production diff + Task 2 RED/GREEN |
| fresh top-level transaction per attempt | Task 2/3 PostgreSQL tests, transaction identity set size 2 |
| enqueue/terminal atomicity | Task 5 existing `WaitlistDeliveryServiceTest` outbox rollback + source KDoc review |
| actual `55P03` retry | Task 2 sequential Testcontainers test |
| actual serializable `40001` retry | Task 3 sequential Testcontainers test |
| non-retryable/exhaustion/interruption | Task 1 existing repository test matrix |
| targeted + full module validation | Task 6 commands |

## Task 1: RED unit contract for PostgreSQL lock timeout classification

**Files:**

- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryRepositoryTest.kt`

- [ ] **Step 1: Add the failing test.**

  Add a test that constructs a repository with `VacancyClaimStrategies.forDialectName("PostgreSQL")`, throws `SQLException("lock timeout", "55P03")` on the first callback, and succeeds on the second callback. Assert two calls and one configured delay. Add a paired H2 assertion that the same SQLSTATE is not retried. Use `assertFailsWith`, `shouldBeEqualTo`, and the existing `ContentionRetryPolicy` fixture.

- [ ] **Step 2: Run the unit test and confirm RED.**

  Run:

  ```bash
  ./gradlew :appointment-core:test \
    --tests "io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryRepositoryTest.postgreSQL lock timeout retries only for the PostgreSQL strategy" \
    --no-build-cache
  ```

  Expected: FAIL because current `isRetryableContention` recognizes only `40001`, `40P01`, and MySQL error code `1205`.

- [ ] **Step 3: Commit the RED test.**

  Commit only the test with a Korean Lore message. Do not modify production code before the failing output is captured.

## Task 2: RED PostgreSQL lock-timeout boundary regression

**Files:**

- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryPostgreSqlContentionTest.kt`
- Reuse: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/Containers.kt`, `TestDB.kt`, `WithTables.kt`

- [ ] **Step 1: Create a real PostgreSQL fixture.**

  Use `withTables(TestDB.POSTGRESQL, Clinics, WaitlistVacancyJobs)` and the existing
  `PostgreSQLServer.Launcher.postgres` path. Insert one READY vacancy, commit before worker
  threads start, and never instantiate `GenericContainer` or `@Testcontainers`.

- [ ] **Step 2: Add the bounded lock-timeout test.**

  Hold the vacancy row with a blocker `inTopLevelTransaction(database)` and `FOR UPDATE`. In a
  separate executor task, call `repository.withContentionRetry` outside any transaction; each
  callback runs `inTopLevelTransaction(database)` with `maxAttempts = 1` and invokes `claim`.
  Release the blocker from the retry sleeper after the first `55P03`. Record
  `System.identityHashCode(TransactionManager.current())` for each callback and assert:

  - first attempt receives PostgreSQL lock timeout and is rolled back;
  - second attempt succeeds after the blocker releases;
  - exactly two transaction identities were observed;
  - final vacancy is `PROCESSING` with the expected owner/version;
  - blocker and executor terminate within bounded time.

- [ ] **Step 3: Run the new test and confirm RED.**

  Run sequentially (no other container-backed Gradle command at the same time):

  ```bash
  ./gradlew :appointment-core:test \
    --tests "io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryPostgreSqlContentionTest.PostgreSQL lock timeout aborts one attempt then retries in a fresh transaction" \
    -PuseDB=POSTGRESQL --no-build-cache --no-daemon
  ```

  Expected: FAIL on the old implementation because `claim` retries inside the aborted transaction and `55P03` is not classified for PostgreSQL.

## Task 3: RED PostgreSQL serializable `40001` boundary regression

**Files:**

- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryPostgreSqlContentionTest.kt`

- [ ] **Step 1: Add a deterministic write-skew fixture.**

  Insert two vacancy rows and commit. Run the retry owner in a bounded executor task with
  `inTopLevelTransaction(database, transactionIsolation = TRANSACTION_SERIALIZABLE)` and
  `maxAttempts = 1`. The first attempt reads both rows, signals a latch, waits for a second
  serializable transaction to read both and update row A, then updates row B. The conflicting
  transaction commits row A. PostgreSQL must abort one snapshot with SQLSTATE `40001`.

- [ ] **Step 2: Assert fresh retry and lifecycle cleanup.**

  Configure a zero-delay retry policy, assert the first callback fails with `40001`, the second
  fresh transaction succeeds, transaction identities are distinct, both executor futures finish
  within a timeout, and the final rows contain one update from each transaction.

- [ ] **Step 3: Run the serializable test and confirm RED.**

  Run:

  ```bash
  ./gradlew :appointment-core:test \
    --tests "io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryPostgreSqlContentionTest.PostgreSQL serializable contention retries in a fresh transaction" \
    -PuseDB=POSTGRESQL --no-build-cache --no-daemon
  ```

  Expected: FAIL because the old `claim`/retry boundary cannot safely re-enter an aborted transaction; if the database scheduling makes the conflict nondeterministic, keep the latches and `maxAttempts = 1`, diagnose the exact SQLSTATE, and repair the fixture before production edits.

## Task 4: Implement the smallest boundary repair

**Files:**

- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistDeliveryService.kt`

- [ ] **Step 1: Remove repository-internal retry from `claim`.**

  Keep validation, lock timeout setup/cleanup, claim strategy selection, and terminal result logic
  unchanged, but execute exactly one claim block. Do not move notification or service work into
  the repository.

- [ ] **Step 2: Extend PostgreSQL retry classification.**

  Add a strategy-gated `55P03` predicate for `VacancyClaimDialect.POSTGRESQL`. Preserve global
  `40001`/`40P01`, MySQL `1205` plus its two-second timeout guard, non-retryable exception identity,
  `WaitlistContention` wrapping, and interrupt restoration.

- [ ] **Step 3: Clarify caller-owned retry KDoc.**

  Document in Korean that `withContentionRetry` must be called outside the caller-owned
  transaction, and each callback must open a fresh top-level transaction around the complete
  `claim + WaitlistDeliveryService.process` unit. Update the service KDoc only; do not change
  public parameters or return types.

- [ ] **Step 4: Run the focused GREEN tests.**

  Run Task 1 unit tests and both Task 2/3 PostgreSQL tests sequentially. Expected: all PASS, with
  no retry sleep while an aborted transaction remains open and two distinct top-level attempts in
  each integration test.

## Task 5: Preserve delivery atomicity and existing contracts

**Files:**

- Existing: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryServiceTest.kt`
- Existing: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryRepositoryTest.kt`

- [ ] **Step 1: Run existing rollback and retry contract tests.**

  Verify outbox failure still rolls back offer, hold, history, and vacancy completion; non-
  retryable SQLSTATE, exhaustion, interruption, MySQL, and H2 tests preserve their existing
  assertions.

- [ ] **Step 2: Inspect public compatibility.**

  Confirm `claim`, `process`, `withContentionRetry`, `ContentionRetryPolicy`, and exception types
  retain their signatures and caller validation/identity contracts. Confirm no new dependency,
  module, schema, migration, README, or workflow registration is required.

- [ ] **Step 3: Write the Korean lesson.**

  Create `docs/lessons/2026-08-17-issue-333-waitlist-transaction-retry.md` with context, decision,
  outcome, test evidence, the missed transaction-boundary assumption, and a future guard that
  retries must wrap a fresh top-level transaction. If no reusable lesson remains after review,
  record the four-category evidence-backed N/A in the checklist instead.

## Task 6: Proportional verification

- [ ] **Step 1: Run targeted H2/unit validation.**

  ```bash
  ./gradlew :appointment-core:test \
    --tests "io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryRepositoryTest" \
    --tests "io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryServiceTest" \
    --no-build-cache --no-daemon
  ```

- [ ] **Step 2: Run PostgreSQL Testcontainers validation sequentially.**

  ```bash
  ./gradlew :appointment-core:test \
    --tests "io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryPostgreSqlContentionTest" \
    -PuseDB=POSTGRESQL --no-build-cache --no-daemon
  ```

  Confirm active Colima context and inherited `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` before
  running; do not restart a healthy VM. Record Testcontainers/container lifecycle failures with
  raw output instead of treating a skip as success.

- [ ] **Step 3: Run the complete affected module.**

  ```bash
  ./gradlew :appointment-core:test --no-build-cache --no-daemon
  ```

  Record expected and actual test counts, including the PostgreSQL singleton matrix.

- [ ] **Step 4: Run static and scope checks.**

  ```bash
  git diff --check
  rg -n "@Testcontainers|GenericContainer" appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist
  rg -n "Thread\.sleep|synchronized\(|!!" appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistDeliveryService.kt
  ```

  Expected: no forbidden Testcontainers usage or new unsafe Kotlin constructs; intentional
  retry-policy sleep remains documented and existing.

## Task 7: Spec/plan verification, final review, and delivery

- [ ] **Step 1: Complete verifier traceability.**

  Map every spec acceptance criterion to source/test/lesson evidence, inspect final changed-file
  scope, and record no production-deployment evidence as an approved N/A for this example.

- [ ] **Step 2: Run six plan/code review lenses in the main session.**

  Review performance, stability, security, operator/Ops, developer/API, and user/caller concerns
  against this plan and final diff. No native subagent lanes are spawned under the current session
  policy; each lens is recorded independently in the integrated review table. P0/P1 must be zero.

- [ ] **Step 3: Commit converged branch.**

  Use Korean Lore commit messages. Keep only approved source, tests, Korean spec/plan/lesson, and
  checklist changes; preserve unrelated `.superpowers/` and `.workflow-inputs/`.

- [ ] **Step 4: Publish and verify PR.**

  After CG-10 PASS, push exact branch `fix/issue-333-waitlist-transaction-retry`, create the
  Korean PR against `bluetape4k/clinic-appointment:develop`, assign `debop`, mirror Issue #333
  labels/milestone, preserve `Closes #333`, and end the body with `## DoD Status`. Reread current
  guidance immediately before PR creation, then wait for exact-head CI and reviews.

- [ ] **Step 5: Stop at merge-ready until fresh approval.**

  Report CG-15 with exact PR/head and `Required checks: X/Y; N/A: N; Blocked: 0`. Do not merge until
  the user gives a fresh approval for that exact head; then use rebase merge, verify the merge SHA,
  sync local `develop`, preserve the remote feature branch unless explicitly authorized to delete,
  and clean only proven merged local state.

## Risk / rollback table

| Risk | Signal | Mitigation | Rerun / rollback |
|---|---|---|---|
| `55P03` is not surfaced as retryable | first PG test throws immediately | strategy-gated SQLSTATE test and source check | repair classifier, rerun Task 1/2 |
| retry still uses aborted transaction | second callback sees `25P02` or no fresh identity | `maxAttempts = 1`, `inTopLevelTransaction`, transaction identity assertions | revert only boundary diff, rerun Task 2 |
| serializable fixture is nondeterministic | no `40001` or latch timeout | two-row write-skew, bounded latches, raw SQLSTATE evidence | repair fixture before production edit |
| outbox atomicity regresses | offer/hold/history remains after enqueue failure | existing service rollback test | revert service doc-only change, rerun Task 5 |
| Testcontainers/Colima bind failure | Docker mount `operation not supported` | inspect `colima status`, `docker context show`, `docker info`; do not restart healthy VM | diagnose environment, keep code unchanged |
| broad public API drift | signature or exception identity changes | compile/source compatibility review | revert unrelated API changes |

Rollback point: before Task 4 production edits, the branch contains only RED tests and approved
artifacts. If GREEN verification fails, revert the latest production commit while retaining the
RED evidence, repair the smallest boundary, and rerun affected tests.

Constraint: retry must own the complete delivery transaction, not only claim.
Rejected: new transaction-owning public service API and raw Testcontainers | unnecessary surface and repository policy violation.
Confidence: high
Scope-risk: moderate
Directive: preserve caller-owned transaction semantics while making retry lifetime explicit in KDoc and PostgreSQL tests.
Tested: spec acceptance mapped to concrete files and commands; no implementation test has run from this plan yet.
Not-tested: actual RED/GREEN and CI evidence are pending implementation.
