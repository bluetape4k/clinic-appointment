# Spring-managed Exposed DataSource Standardization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and the matching Kotlin/Exposed/Spring pattern skills for every code task. Execute each checkbox in order and record fresh evidence.

**Goal:** Make eligible Spring runtime Exposed handles reuse the injected application `DataSource`, while preserving global default restoration and documenting intentional standalone database fixtures.

**Architecture:** Add one internal `ExposedDatabaseFactory` plus a destroy-time `ExposedDatabaseLifecycle` in `appointment-api`. The factory serializes `Database.connect(dataSource)` and restores the previous `TransactionManager.defaultDatabase`; the lifecycle unregisters factory-owned managers before Spring closes the pool. Both runtime configuration classes call it. Spring context wiring tests provide an Hikari-backed `DataSource`; standalone tests, migration/dialect fixtures, and Gatling remain independent and are covered by an explicit allowlist/audit.

**Tech Stack:** Kotlin 2.3, Spring Boot 4.1, Exposed JDBC v1, HikariCP, H2, JUnit 5, bluetape4k assertions, Gradle.

---

## File map and ownership

| File | Responsibility |
|---|---|
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactory.kt` | Shared runtime connection/lifecycle boundary |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseLifecycle.kt` | Spring destroy hook that unregisters factory-owned Exposed managers |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt` | Commitment runtime bean delegates to factory |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationConfiguration.kt` | Profile runtime bean delegates to factory |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactoryTest.kt` | RED/GREEN proof for injected pool and default restoration |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentCommitmentApplicationWiringTest.kt` | Spring commitment wiring uses `DataSource` bean |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationWiringTest.kt` | Spring profile wiring uses `DataSource` bean |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NotificationReminderRecoveryWiringTest.kt` | Notification runtime wiring uses `DataSource` bean |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/DataSourceOwnershipContractTest.kt` | Production direct-setup guard and allowlist assertions |
| `docs/runbooks/spring-managed-exposed-datasource.ko.md` | Korean lifecycle, qualifier, and allowlist contract |
| `docs/lessons/2026-08-05-issue-223-spring-managed-exposed-datasource.md` | Durable decision and future guard |
| `docs/reviews/2026-08-05-issue-223-spring-managed-exposed-datasource-review.ko.md` | Six-lens review findings and P0/P1 gate |

No module registration, dependency catalog, Flyway schema, README public API, or
tenant-isolation source change is planned. Existing migration/dialect/Gatling files
remain unchanged unless the audit finds an ownership defect.

## Task 1: Lock the factory contract with failing tests

**Files:**

- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactoryTest.kt`

- [x] **Step 1: Add the RED test**

Use a Hikari-backed H2 pool and a `marker_value` table created through that pool. Set a
sentinel Exposed default, call the not-yet-created factory, query the marker through
the returned handle, and assert that the sentinel is restored. The completed test
also covers barrier-bounded concurrent registration, repeated transactions through
an instrumented `DataSource`, factory lifecycle cleanup, and no-op behavior for an
externally registered `Database`. Each test closes only resources it created.

```kotlin
val database = ExposedDatabaseFactory.connect(injectedHikariDataSource)
transaction(database) {
    exec("SELECT marker_value FROM datasource_marker") { rows ->
        rows.next()
        rows.getInt(1)
    }
} shouldBeEqualTo 223
TransactionManager.defaultDatabase shouldBeEqualTo sentinel
```

Use `bluetape4k-assertions`; do not add JUnit `assertEquals` or `!!`.

- [x] **Step 2: Run the RED test**

Run:

```bash
./gradlew :appointment-api:test --tests '*ExposedDatabaseFactoryTest' --no-build-cache
```

Expected: compilation fails because `ExposedDatabaseFactory` is absent. If the
test fails for Hikari/Exposed API syntax instead, repair the test until the failure
is specifically the missing factory symbol.

## Task 2: Implement and green the shared runtime factory

**Files:**

- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactory.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactoryTest.kt`

- [x] **Step 1: Add the minimal factory**

The implementation keeps a single registration lock, restores the previous default
in `finally`, records handles created by the factory, and exposes a guarded release
operation used by `ExposedDatabaseLifecycle`.

Document in Korean KDoc that Spring owns `DataSource` creation/close and the factory
only creates an Exposed handle; the global default is restored after registration.
Register an `ExposedDatabaseLifecycle` bean for each eligible runtime database so
context destruction delegates to the factory-owned release guard before Spring closes
the injected pool. If a context supplies an external `Database`, the lifecycle guard
is a no-op and does not unregister that external manager.

- [x] **Step 2: Run the GREEN test**

Run the Task 1 command again. Expected: one test passes with no compilation warning
or resource-leak failure.

- [x] **Step 3: Commit the isolated factory**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactory.kt appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/ExposedDatabaseFactoryTest.kt
git commit -m "Centralize Spring-managed Exposed database creation" -m "Keep one synchronized DataSource-to-Database registration boundary before migrating Spring wiring tests.\n\nConstraint: Spring owns the injected HikariDataSource lifecycle and Exposed global default restoration must remain compatible.\nRejected: Repository-wide direct URL replacement would break standalone dialect and Gatling fixtures.\nConfidence: high\nScope-risk: moderate\nDirective: Future multi-pool runtime paths require explicit qualifiers and marker-query wiring tests.\nTested: ExposedDatabaseFactoryTest RED/GREEN and git diff --check.\nNot-tested: full multi-backend matrix."
```

## Task 3: Route both runtime configurations through the factory

**Files:**

- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt:144,536-542`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ProfileReevaluationConfiguration.kt:47-65`

- [x] **Step 1: Remove duplicate registration state**

Delete each configuration's private `ReentrantLock`, `withLock` import, and inline
`TransactionManager.defaultDatabase` save/restore block. Keep the database bean
conditions, parameter injection, feature flags, and return type unchanged. Runtime
database beans declare explicit Spring bean names so lifecycle conditions do not rely
on Kotlin `internal` method names, which are compiler-mangled.

- [x] **Step 2: Delegate to the factory**

The two methods become expression bodies with the existing injected parameter:

```kotlin
internal fun appointmentCommitmentDatabase(dataSource: DataSource): Database =
    ExposedDatabaseFactory.connect(dataSource)
```

```kotlin
fun profileReevaluationDatabase(dataSource: DataSource): Database =
    ExposedDatabaseFactory.connect(dataSource)
```

Add a lifecycle bean for each eligible runtime handle. Verify that the destroy callback
unregisters only the factory-owned handle and leaves a separately provided `Database`
untouched; production currently has one candidate `Database` per runtime context.

- [x] **Step 3: Run focused compilation/tests**

```bash
./gradlew :appointment-api:test --tests '*ExposedDatabaseFactoryTest' --tests '*AppointmentCommitmentApplicationWiringTest' --tests '*ProfileReevaluationWiringTest' --no-build-cache
```

Expected: all selected tests pass; the configuration beans remain conditional and
no unrelated tenant or transaction source changes appear.

## Task 4: Convert Spring wiring tests to injected Hikari DataSource

**Files:**

- Modify: `AppointmentCommitmentApplicationWiringTest.kt`
- Modify: `ProfileReevaluationWiringTest.kt`
- Modify: `NotificationReminderRecoveryWiringTest.kt`

- [x] **Step 1: Replace direct Database suppliers**

Remove each supplier whose body directly calls `Database.connect`.
Add a named `DataSource` supplier using `HikariDataSource(HikariConfig().apply {
jdbcUrl = "jdbc:h2:mem:wiring_<scope>_${System.nanoTime()};DB_CLOSE_DELAY=-1"
driverClassName = "org.h2.Driver"
username = "sa"
})`. The context owns the bean lifecycle; tests must not close the same instance
manually. Seed a scope-unique marker table through the retained Hikari instance and
assert that the context-created `Database` reads that marker. Retain the supplier
reference and assert `HikariDataSource.isClosed` after each context closes.

- [x] **Step 2: Assert the context-created Database**

Keep existing wiring assertions and add `context.getBean(Database::class.java)` to
ensure the conditional production bean is created from the injected DataSource.
For the profile, commitment, and notification contexts, use the injected pool's
`datasource_marker`/`marker_value` row in `transaction(database) { ... }` as the
smallest runtime proof. Use existing bluetape assertions and descriptive backtick
test names.

- [x] **Step 3: Run the wiring tests**

```bash
./gradlew :appointment-api:test --tests '*AppointmentCommitmentApplicationWiringTest' --tests '*ProfileReevaluationWiringTest' --tests '*NotificationReminderRecoveryWiringTest' --no-build-cache
```

Expected: all existing bean-count/feature-flag assertions and the new DataSource
transaction checks pass. A failure that leaves Hikari open is a lifecycle defect and
must be fixed before proceeding.

## Task 5: Add production direct-setup audit and allowlist documentation

**Files:**

- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/DataSourceOwnershipContractTest.kt`
- Create: `docs/runbooks/spring-managed-exposed-datasource.ko.md`

- [x] **Step 1: Write the production-boundary audit assertion**

The test reads every `appointment-*/src/main` Kotlin/Java source and asserts that
`Database.connect(` appears only in `ExposedDatabaseFactory.kt`, and that production
source contains no `HikariDataSource`, `SimpleDriverDataSource`, `DriverManager.getConnection`,
or `jdbc:` literals. Run it before the source cleanup to observe the expected RED
result naming the two configuration files. If the audit is added after the cleanup,
keep it as a green guard and record the pre-cleanup RED evidence in the lesson rather
than pretending the current ordering is executable.

- [x] **Step 2: Update the source and rerun audit**

After Task 3, rerun the same test. The expected result is PASS with two runtime
factory callers and no direct pool/URL creation in production.

- [x] **Step 3: Write the Korean runbook**

Include:

1. Spring owns `DataSource` creation, pooling, configuration, and shutdown.
2. Exposed code receives `Database`/`DataSource` by injection and keeps transaction
   boundaries explicit; request code never closes a pool.
3. A multi-pool runtime path must use an explicit qualifier and a marker/wiring test.
4. Allowlist rows for standalone unit tests, migration/dialect fixtures, and Gatling;
   each row names why Spring context is unavailable or undesirable and who closes the
   resource.
5. The exact repository audit commands and the expected production-source boundary.

- [x] **Step 4: Run audit and diff checks**

```bash
./gradlew :appointment-api:test --tests '*DataSourceOwnershipContractTest' --no-build-cache
git diff --check
```

## Task 6: Complete verification and durable lesson

**Files:**

- Create: `docs/lessons/2026-08-05-issue-223-spring-managed-exposed-datasource.md`
- Optional modify: no production files outside the approved map

- [x] **Step 1: Run targeted module proof sequentially**

```bash
./gradlew :appointment-api:test --tests '*ExposedDatabaseFactoryTest' --tests '*DataSourceOwnershipContractTest' --tests '*AppointmentCommitmentApplicationWiringTest' --tests '*ProfileReevaluationWiringTest' --tests '*NotificationReminderRecoveryWiringTest' --no-build-cache
./gradlew :appointment-api:compileKotlin :appointment-api:compileTestKotlin --no-build-cache
```

Run any Testcontainers/dialect task only after these commands and never in parallel
with another real-DB task. Existing standalone fixtures remain the regression scope;
do not run the whole multi-backend matrix locally unless the task runner is available.

- [x] **Step 2: Inspect final inventory**

```bash
rg -n 'Database\\.connect|HikariDataSource|SimpleDriverDataSource|DriverManager\\.getConnection|jdbc:' appointment-*/src/main appointment-*/src/test appointment-api/src/gatling
```

Record the remaining occurrences by the allowlist table; a new main-source match is
P1 and blocks delivery.

- [x] **Step 3: Write the lesson**

Record context, decision, surprising failure or absence, test evidence, review misses,
and the future audit guard. If no new lesson remains after reviewing the diff, state
the concrete N/A evidence rather than filler.

- [x] **Step 4: Run final checks**

```bash
git diff --check
git status --short --branch
git log --oneline --decorate -5
```

Expected: clean diff check, only approved files, P0=0/P1=0, and a tracked lesson.

- [x] **Step 5: Commit the lesson and verification scope**

Use a Lore commit whose `Tested` trailer lists the fresh Gradle commands and whose
`Not-tested` trailer lists any external DB or full matrix gap with evidence.

## Task 7: Close the review and workflow gates before delivery

**Files:**

- Review: all approved source, test, runbook, and lesson files in this worktree
- Evidence: workflow receipts, plan/spec commits, review-lane reports, and fresh test output

- [x] **Step 1: Apply the plan-review findings**

Add a deterministic concurrent factory test that uses a barrier and several
`connect` calls against the same injected pool. Each returned handle must answer
the marker query, each caller must observe its prior default restored, and the
test must assert bounded completion without sleeping or using a real external DB.
Add a bounded pool-reuse validation over repeated transactions with one
instrumented/injected `DataSource`; record the observed acquisition count and
state the baseline/threshold or the explicit reason the benchmark is out of scope.

- [x] **Step 2: Run the six review lenses**

Record independent findings for Performance, Stability, Security, Operator/Ops,
Developer/API, and User/caller. The main lane deduplicates findings and blocks
delivery on any P0/P1. A P2 must either be fixed in the approved file map or be
carried as a documented, bounded follow-up; P3 findings do not expand scope.

- [x] **Step 3: Re-run the Kotlin/Exposed/workflow checklists**

Verify Korean KDoc/runbook/lesson language, explicit transaction boundaries,
Spring bean lifecycle ownership, Exposed global-default restoration, no new
dependency or public API surface, workflow receipt evidence, clean diff, and the
required targeted Gradle commands. Refresh the plan/spec if an API mismatch or
review finding changes behavior before implementation continues.

## Task 8: Publish the approved change and prove CI readiness

**Files:**

- GitHub issue `#223`
- Pull request from `issue-223-datasource-standardization` to `develop`

- [ ] **Step 1: Verify delivery authority and exact head**

Confirm the standing user instruction authorizes push/PR/CI closeout for this
issue, then capture the worktree branch and exact commit SHA after all local
commits. Do not merge or enable auto-merge in this task.

- [ ] **Step 2: Push and create the English PR**

Push the feature branch, create the PR only after the remote head is verified,
link `#223`, assign `debop`, and mirror the issue's labels and milestone. The PR
body must include scope, tests, known gaps, and a final `## DoD Status` section.

- [ ] **Step 3: Verify live PR metadata and CI**

Re-read the live PR body, issue link, assignee, labels, milestone, head SHA,
review state, and status checks with `gh`. Wait for required CI and address any
actionable failure or review comment. Capture the final green-check evidence;
workflow receipts remain part of the delivery record.

## Task 9: Stop at the fresh merge-approval gate

- [ ] **Step 1: Report merge-ready DoD**

Report the exact PR head, green CI/review evidence, local/remote parity, and all
remaining risks in the final DoD. Ask for a fresh explicit approval tied to that
exact verified head before running `gh pr merge`.

- [ ] **Step 2: After fresh approval only**

Merge the PR without auto-merge, verify the merged state, synchronize the root
`develop` checkout, remove only the proven-merged feature worktree/branch, and
rerun status plus the relevant helper checks. If fresh approval is not present,
leave the PR open and the worktree intact.

## Acceptance traceability

| Spec criterion | Plan task | Proof |
|---|---|---|
| No eligible production direct setup | Tasks 3 and 5 | `DataSourceOwnershipContractTest` + `rg` inventory |
| Shared factory, default restoration, and manager cleanup | Tasks 1–3 | Hikari marker/sentinel + lifecycle unregister test |
| Concurrent registration and pool reuse are bounded | Task 7 | Barrier-based factory test + instrumented DataSource validation |
| Spring wiring uses injected pool and closes it | Task 4 | Three marker queries, context-runner tests, and `isClosed` assertions |
| Standalone fixtures documented | Task 5 | Korean runbook allowlist |
| Search and targeted tests | Task 6 | fresh `rg`, Gradle targeted/module compile, diff check |
| PR/CI metadata and delivery evidence | Tasks 8–9 | Live `gh` head/body/checks plus merge-approval gate |
| Issue #39 untouched | Tasks 3 and 6 | final diff path review and tenant test scope unchanged |

## Rollback and stop conditions

- Before commit, revert only the approved files in this feature worktree; never touch
  `develop` or discard unrelated state.
- If a partial context startup occurs, close the context first, let the lifecycle bean
  unregister its factory-owned handle, then confirm the retained Hikari reference is
  closed before retrying. Rollback removes lifecycle beans before deleting the factory,
  then restores the two original configuration blocks.
- If Hikari or Exposed API compilation differs from the plan snippets, stop at the
  failing task, inspect the actual dependency source, and revise the plan/spec before
  changing behavior.
- P0/P1 review finding, failing targeted test, dirty unrelated file, or missing
  workflow receipt evidence blocks PR delivery.
