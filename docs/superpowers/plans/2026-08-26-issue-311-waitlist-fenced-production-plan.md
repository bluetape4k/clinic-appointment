# Issue #311 waitlist fenced production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `LettuceFencedLock`의 fencing token을 waitlist scheduler의 실제 production 경로와 Exposed vacancy claim/terminal write까지 전달해 lease expiry 뒤 stale worker의 DB mutation을 차단한다.

**Architecture:** `appointment-core`는 `(fence_epoch, fence_sequence)`를 저장하고 typed `claimFenced`와 exact-match terminal fence를 제공한다. `appointment-api`는 이미 존재하는 `bluetape4k-lettuce:1.12.1`의 `LettuceFencedLock`을 얇은 typed adapter로 감싸고, 명시적으로 제공된 dispatcher/recovery port가 있을 때만 fail-closed conditional configuration으로 scheduler를 조립한다. 기존 Boolean scheduler/legacy claim 경로는 호환성을 위해 유지하되 production fenced path에서는 nullable token API를 호출하지 않는다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed, Flyway V31 additive migrations, `bluetape4k-lettuce:1.12.1`, `bluetape4k-codec` `Base58`, Micrometer, JUnit 5, MockK/Kluent, bluetape4k singleton Redis/PostgreSQL launchers.

---

## 실행 규칙과 acceptance traceability

- 모든 Kotlin 검증은 `$bluetape-kotlin-patterns`와 `references/checklist.md`를 적용한다.
- 모든 새 테스트 assertion은 `bluetape4k-assertions`를 사용한다. 값의 의미를 검증하지 않는 단순 `assertTrue`/`assertEquals`는 추가하지 않는다.
- Exposed public repository 호출은 caller-owned `transaction {}` 안에서 수행하고, 기존 `WaitlistDeliveryService.process`의 transaction 소유권을 바꾸지 않는다.
- Redis fixture는 `Containers.Redis` singleton을 사용하며 `@Testcontainers`를 도입하지 않는다.
- Issue #311 수용 기준 추적:
  - AC-01 Redis adapter/production wiring trace → Task 4, Task 5, Task 7
  - AC-02 owner/request/fixed lease/watchdog/close/ambiguous reconcile → Task 4, Task 6, Task 7
  - AC-03 strict-greater DB fence와 exact terminal fence → Task 1, Task 2, Task 3
  - AC-04 typed port와 nullable legacy 경계 → Task 4, Task 5
  - AC-05 Redis expiry/failover/stale worker/new owner → Task 2, Task 7
  - AC-06 raw identity/key/token redaction → Task 6, Task 7
  - AC-07 migration/docs/rollback boundary → Task 3, Task 8
  - AC-08 required Gradle tests와 diff/static verification → Task 9

## 변경 파일 지도

- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistFencingToken.kt` — immutable token value and tuple comparison.
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistVacancyJobs.kt` — V31 fence columns in Exposed table.
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt` — typed fenced claim, token mapping, exact terminal predicates.
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryRepositoryTest.kt` — H2 strict-greater/stale terminal tests.
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryPostgreSqlContentionTest.kt` — expired takeover and stale owner regression.
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryTableSchemaTest.kt` and `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt` — latest Exposed columns.
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedLeaderLease.kt` — typed lock operation boundary, Base58 opaque owner, lease-handle state machine, Lettuce adapter.
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedDeliveryScheduling.kt` — typed runner, dispatcher port, conditional scheduler configuration and readiness probe.
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryMetrics.kt` — allowlisted lease/tick/ownership-loss observations.
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryProperties.kt` — fenced lease/namespace/epoch/enable settings with bounded validation.
- Create: `appointment-api/src/main/resources/db/migration/h2/V31__add_waitlist_fencing_token.sql`, `.../postgresql/...`, `.../mysql/...` — additive non-null default columns.
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedLeaderLeaseTest.kt` — fake lock outcome/lifecycle tests.
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedDeliverySchedulingTest.kt` — typed runner and fail-closed tests.
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedRedisIntegrationTest.kt` — singleton Redis acquire/expiry/reconcile/redaction test.
- Create/modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/WaitlistFencingMigrationContractTest.kt` and support helper — V31 column/default/readiness checks without changing V19 contract.
- Create/modify: `docs/api/waitlist-delivery.md`, `docs/runbooks/waitlist-delivery.md` — Korean public API/operation and rollback/readiness instructions.
- Create: `docs/lessons/2026-08-26-issue-311-waitlist-fenced-production.md` — Korean Lore lesson after implementation evidence exists.

### Task 1: Token/table contract을 RED로 고정

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistFencingToken.kt`
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistVacancyJobs.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryTableSchemaTest.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`

- [ ] **Step 1: Write the failing token and schema tests.**

```kotlin
@Test
fun `token comparison is lexicographic and rejects negative values`() {
    val previous = WaitlistFencingToken(epoch = 4, sequence = 9)
    WaitlistFencingToken(epoch = 4, sequence = 10).isStrictlyGreaterThan(previous) shouldBeEqualTo true
    WaitlistFencingToken(epoch = 5, sequence = 0).isStrictlyGreaterThan(previous) shouldBeEqualTo true
    WaitlistFencingToken(epoch = 4, sequence = 9).isStrictlyGreaterThan(previous) shouldBeEqualTo false
    shouldThrow<IllegalArgumentException> { WaitlistFencingToken(epoch = -1, sequence = 0) }
}

@Test
fun `waitlist table exposes non-null fence defaults`() {
    WaitlistVacancyJobs.columns.map { it.name }.toSet() shouldContainAll setOf("fence_epoch", "fence_sequence")
    WaitlistVacancyJobs.fenceEpoch.defaultValue shouldBeEqualTo 0L
    WaitlistVacancyJobs.fenceSequence.defaultValue shouldBeEqualTo 0L
}
```

Use the repository's `shouldBeEqualTo`, `shouldContainAll`, and `shouldThrow` helpers from `bluetape4k-assertions`; run table setup through the existing `SchemaUtils.createMissingTablesAndColumns` fixture.

- [ ] **Step 2: Run the focused tests and capture RED evidence.**

Run:

```bash
./gradlew :appointment-core:test --tests 'io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryTableSchemaTest' --tests 'io.bluetape4k.clinic.appointment.model.tables.TableSchemaTest' --no-build-cache --no-daemon --console=plain
```

Expected: compilation or assertion failure because `WaitlistFencingToken`, `fenceEpoch`, and `fenceSequence` do not yet exist. Do not proceed without recording the actual failure in the checklist.

- [ ] **Step 3: Add the minimal immutable token and Exposed columns.**

```kotlin
data class WaitlistFencingToken(
    val epoch: Long,
    val sequence: Long,
) : Serializable {
    init {
        require(epoch >= 0L) { "epoch must be zero or positive" }
        require(sequence >= 0L) { "sequence must be zero or positive" }
    }

    fun isStrictlyGreaterThan(previous: WaitlistFencingToken): Boolean =
        epoch > previous.epoch || epoch == previous.epoch && sequence > previous.sequence
}
```

Add `long("fence_epoch").default(0L)` and `long("fence_sequence").default(0L)` next to the existing lease/version columns. Do not rename `scheduling_waitlist_vacancy_jobs` or edit the V19 migration.

- [ ] **Step 4: Run the focused tests and record GREEN.**

Expected: both schema/token tests pass and `git diff --check` is clean.

- [ ] **Step 5: Commit the isolated contract.**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistFencingToken.kt appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/WaitlistVacancyJobs.kt appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryTableSchemaTest.kt appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt
git commit -m "대기목록 DB fence 토큰 계약을 고정"
```

Use the Lore trailers from the execution protocol and report the exact test command in `Tested:`.

### Task 2: Repository strict-greater와 exact terminal fence 구현

**Files:**
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryRepositoryTest.kt`
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryPostgreSqlContentionTest.kt`

- [ ] **Step 1: Add RED tests for the public fenced signature and stale writes.**

```kotlin
val first = repository.claimFenced(job.id.value, "owner-a", now, now.plusSeconds(5), WaitlistFencingToken(1, 1))
first shouldNotBeEqualTo null
repository.claimFenced(job.id.value, "owner-b", now.plusSeconds(6), now.plusSeconds(11), WaitlistFencingToken(1, 1)) shouldBeEqualTo null
repository.claimFenced(job.id.value, "owner-b", now.plusSeconds(6), now.plusSeconds(11), WaitlistFencingToken(1, 2)) shouldNotBeEqualTo null
repository.markCompleted(first!!, now.plusSeconds(7)) shouldBeEqualTo 0
repository.markCompleted(second!!, now.plusSeconds(7)) shouldBeEqualTo 1
```

Cover lower epoch, same epoch/lower sequence, wrong owner, wrong token, expired lease, duplicate terminal update, and legacy `claim` retaining nullable-token behavior. Keep test setup in `transaction {}` and clear tables in `@BeforeEach`.

- [ ] **Step 2: Run H2 RED and PostgreSQL contention RED.**

```bash
./gradlew :appointment-core:test --tests 'io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryRepositoryTest' --tests 'io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryPostgreSqlContentionTest' --no-build-cache --no-daemon --console=plain
```

Expected: missing `claimFenced`/token propagation or incorrect row counts. Record the failure before implementation.

- [ ] **Step 3: Implement token-aware claim without widening legacy APIs.**

Add `fencingToken: WaitlistFencingToken? = null` to `VacancyClaim` and a dedicated non-null method:

```kotlin
fun claimFenced(jobId: Long, owner: String, now: Instant, leaseUntil: Instant, token: WaitlistFencingToken): VacancyClaim? =
    claimWithStrategy(jobId, owner, now, leaseUntil, token, ClaimStrategy.FENCED)
```

In the locked candidate predicate, require READY or expired PROCESSING and, when `token` is non-null, require:

```kotlin
(WaitlistVacancyJobs.fenceEpoch less token.epoch) or
((WaitlistVacancyJobs.fenceEpoch eq token.epoch) and
 (WaitlistVacancyJobs.fenceSequence less token.sequence))
```

Update owner/version/lease and both fence columns in one row update; map the stored pair back to `WaitlistFencingToken`. `claim(...)` continues to call the legacy strategy without a token.

- [ ] **Step 4: Implement exact token matching on every fenced terminal path.**

Extend `VacancyJobRecord` and `matchesFence` so a non-null claim requires `fenceEpoch == claim.token.epoch` and `fenceSequence == claim.token.sequence`. Add the same equality to the update predicate used by `completeOffer`, `completeNoCandidate`, `markExpired`, and `markFailed`; preserve existing owner/version/lease expiry predicates. Keep `terminalizeForProgress` token-preserving and make no token-only mutation.

- [ ] **Step 5: Run H2 and PostgreSQL tests to GREEN.**

Expected: strict-greater accepts only a higher tuple, expired takeover returns a higher-token claim, and stale terminal writes affect zero rows. If PostgreSQL launcher is unavailable, diagnose the launcher/context and leave the test blocked rather than treating a skipped container as success.

- [ ] **Step 6: Commit the repository fence.**

```bash
git add appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryRepositoryTest.kt appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryPostgreSqlContentionTest.kt
git commit -m "대기목록 claim에 엄격한 DB fence를 적용"
```

### Task 3: V31 migration과 readiness contract 구현

**Files:**
- Create: `appointment-api/src/main/resources/db/migration/h2/V31__add_waitlist_fencing_token.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V31__add_waitlist_fencing_token.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V31__add_waitlist_fencing_token.sql`
- Create/modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/WaitlistFencingMigrationContractTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/WaitlistDeliveryMigrationTestSupport.kt`

- [ ] **Step 1: Write RED migration/readiness assertions.**

```kotlin
verifyV31FenceColumns(dialect, jdbcTemplate)
columns shouldContainAll setOf("fence_epoch", "fence_sequence")
defaults["fence_epoch"] shouldBeEqualTo "0"
defaults["fence_sequence"] shouldBeEqualTo "0"
```

Test H2, PostgreSQL, and MySQL using the existing Flyway fixtures. Preserve V19 exact-column expectations and add a separate latest/V31 contract.

- [ ] **Step 2: Run the migration tests to RED.**

```bash
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.migration.WaitlistFencingMigrationContractTest' --no-build-cache --no-daemon --console=plain
```

Expected: V31 script or helper is missing.

- [ ] **Step 3: Add additive V31 scripts.**

```sql
ALTER TABLE scheduling_waitlist_vacancy_jobs
    ADD COLUMN fence_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE scheduling_waitlist_vacancy_jobs
    ADD COLUMN fence_sequence BIGINT NOT NULL DEFAULT 0;
```

Use the same SQL for all three dialect directories unless the existing dialect syntax requires a documented type spelling. Do not modify V19–V30.

- [ ] **Step 4: Add a readiness probe that checks both columns.**

The probe must execute a metadata/zero-row query against the existing `DataSource` and return a typed failure; it must not silently catch an absent-column error or register a fake ready state. The production configuration will invoke it before creating the fenced scheduler.

- [ ] **Step 5: Run all migration tests to GREEN and commit.**

```bash
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.migration.*' --no-build-cache --no-daemon --console=plain
git add appointment-api/src/main/resources/db/migration appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration
git commit -m "대기목록 fencing 컬럼의 V31 호환성 경계를 추가"
```

### Task 4: Typed Lettuce adapter와 lease-handle 상태 머신 구현

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedLeaderLease.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedLeaderLeaseTest.kt`

- [ ] **Step 1: Write fake-lock RED tests for every outcome.**

The fake implements this exact internal boundary:

```kotlin
internal interface WaitlistFencedLockOperations : AutoCloseable {
    fun bootstrap(): FencedBootstrapResult
    fun tryAcquire(owner: LockOwnerId, request: LockRequestId, policy: LeasePolicy): LockAcquireResult<FencedLockHandle>
    fun reconcile(owner: LockOwnerId, request: LockRequestId): LockReconcileResult<FencedLockHandle>
    fun release(handle: FencedLockHandle): LockMutationResult<FencedLockHandle>
    override fun close()
}
```

The adapter exposes this dispatcher-safe handle and keeps native identities internal:

```kotlin
data class WaitlistLeaseHandle internal constructor(
    val owner: String,
    val token: WaitlistFencingToken,
    val leaseUntil: Instant,
    internal val nativeHandle: FencedLockHandle,
)
```

`owner` is the server-generated `Base58.randomString(8)` DB reference. `LockOwnerId`,
`LockRequestId`, and the native handle are never serialized, logged, or added to metric tags;
the adapter retains them for exact reconcile/release only. The public handle and
`WaitlistFencingToken` string forms are fully redacted.

Assert `Acquired`/`Reentered` produce a handle, `Contended`/`TimedOut`/backend failure produce no business handle, `Ambiguous` reconciles with the identical owner/request, ownership loss does not release another handle, duplicate release is idempotent, and close prevents new acquire. Use `Base58.randomString(8)` in the adapter's opaque DB owner reference; do not introduce UUID.

- [ ] **Step 2: Run the lease tests to RED.**

```bash
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistFencedLeaderLeaseTest' --no-build-cache --no-daemon --console=plain
```

- [ ] **Step 3: Implement the adapter with fixed lease and redacted identity.**

Construct the final `LettuceFencedLock` through its factory with `FencedLockConfig(lock = LockConfig(namespace = "bt4k:coord:v1"), epoch = properties.fenceEpoch)`. Call `bootstrap()` once before traffic. Generate `ownerRef = Base58.randomString(8)`, pass `LockOwnerId.from(ownerRef)` to Lettuce, and expose only the opaque owner string in `WaitlistLeaseHandle`; never use the native ID's raw value in logs/metrics. Store a single pending owner/request pair for ambiguous reconcile and transition local state only once:

```text
ACQUIRED -> RELEASED | UNKNOWN | LOST
UNKNOWN  -> RELEASED | UNKNOWN | LOST (one bounded retry)
RELEASED/LOST -> terminal (no second release)
```

Use `LeasePolicy.Fixed(properties.jobLease)` by default. Bound lease duration, require a
positive `fenceEpoch`, require `tickBudget < jobLease`, reject non-positive/overlong values
with `require`, and map library outcomes to a closed `WaitlistLeaseAttempt`/`WaitlistLeaseFailure`
enum. The runner checks monotonic elapsed time before every mutating port and stops the next
port when `tickBudget` is exhausted.

- [ ] **Step 4: Run tests to GREEN and commit.**

Expected: all fake outcomes and lifecycle transitions pass with no raw identity in captured observations; an unknown release remains retryable exactly once.

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedLeaderLease.kt appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedLeaderLeaseTest.kt
git commit -m "Lettuce fenced lease를 typed waitlist adapter로 연결"
```

### Task 5: Typed runner와 metrics 구현

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedDeliveryScheduling.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryMetrics.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryProperties.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedDeliverySchedulingTest.kt`

- [ ] **Step 1: Write RED runner tests.**

Use these production-facing signatures:

```kotlin
fun interface WaitlistFencedVacancyDispatcher {
    fun dispatch(limit: Int, now: Instant, lease: WaitlistLeaseHandle): Int
}

class WaitlistFencedDeliverySchedulingRunner(
    private val lease: FencedWaitlistLeaderLease,
    private val dispatcher: WaitlistFencedVacancyDispatcher,
    private val expiry: WaitlistOfferExpiryRunner,
    private val suppression: WaitlistNotificationSuppressionRunner,
    private val holdReconciler: WaitlistHoldReconciler,
    private val properties: WaitlistDeliveryProperties,
    private val metrics: WaitlistDeliveryMetrics,
)
```

Assert only acquired/reentered runs expiry → suppression → hold reconcile → typed dispatch; every non-handle outcome skips all DB-mutating ports; ambiguous runs exactly one reconcile; release runs at most once; cancellation/close cannot start a second tick; dispatcher receives a non-null `WaitlistFencingToken`.

- [ ] **Step 2: Run the runner tests to RED.**

```bash
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistFencedDeliverySchedulingTest' --no-build-cache --no-daemon --console=plain
```

- [ ] **Step 3: Implement the typed runner and closed metrics.**

Preserve the legacy `WaitlistDeliverySchedulingRunner` unchanged. The typed runner must return a result containing mode, lease outcome, counts, and bounded duration; it must never log owner/request/token/key. Add only allowlisted tags:

```text
lease_acquire_total{outcome=acquired|contended|timeout|ambiguous|failed}
lease_acquire_seconds{outcome=...}
scheduler_tick_seconds{mode=active|clinic_disabled|global_off}
ownership_loss_total{source=redis|db}
```

Record `OwnershipLost` from the adapter or a zero-row fenced DB terminal write as `source=redis|db`; do not attach identifiers. Validate `limit`, `pollInterval`, `jobLease`, positive `fenceEpoch`, and `tickBudget < jobLease` with bluetape4k precondition helpers or Kotlin `require` consistent with the local pattern.

- [ ] **Step 4: Run tests and commit.**

Expected: all outcome/lifecycle/metric allowlist tests pass and the metric registry contains no unapproved tags or raw values.

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedDeliveryScheduling.kt appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryMetrics.kt appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryProperties.kt appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedDeliverySchedulingTest.kt
git commit -m "대기목록 fenced scheduler의 typed 실행과 관측을 추가"
```

### Task 6: Fail-closed Spring production wiring

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedDeliveryScheduling.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt` only if connection lifecycle needs an explicit bean boundary.
- Create/modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedSchedulingConfigurationTest.kt`

- [ ] **Step 1: Write RED context tests for disabled and incomplete wiring.**

```kotlin
contextRunner.withPropertyValues("appointment.waitlist.delivery.enabled=false").run { context ->
    context.containsBean(WaitlistFencedDeliverySchedulingRunner::class.java) shouldBeEqualTo false
}
contextRunner.withPropertyValues("appointment.waitlist.delivery.enabled=true").withUserConfiguration(MissingDispatcher::class.java).run { context ->
    context.containsBean(WaitlistFencedDeliverySchedulingRunner::class.java) shouldBeEqualTo false
}
```

Add an enabled/full-port case that verifies V31 readiness is called before the scheduler bean is usable and a readiness failure produces a typed startup failure, not a no-op dispatcher.

- [ ] **Step 2: Run context tests to RED.**

```bash
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistFencedSchedulingConfigurationTest' --no-build-cache --no-daemon --console=plain
```

- [ ] **Step 3: Add conditional configuration with explicit ownership.**

Require `appointment.waitlist.delivery.enabled=true`, `RedisClient`, `DataSource`, `WaitlistFencedVacancyDispatcher`, expiry/suppression/reconcile ports, and readiness success. Create a stateful Redis connection bean with `destroyMethod = "close"`; let `RedisClient` remain owned by `CacheConfig`. Create `LettuceFencedLock`/adapter/typed runner/scheduler only after bootstrap and readiness. Do not create a fake or no-op dispatcher when any port is absent. `close()` must stop local scheduling and lock tasks but must not close the shared `RedisClient`.

- [ ] **Step 4: Run context tests and commit.**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedDeliveryScheduling.kt appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedSchedulingConfigurationTest.kt
git commit -m "대기목록 fenced scheduler를 fail-closed production wiring으로 제한"
```

### Task 7: Redis singleton 통합과 expiry/close/redaction 회귀

**Files:**
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistFencedRedisIntegrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/Containers.kt` only if an existing singleton launcher cannot be reused.

- [ ] **Step 1: Write the sequential Redis RED scenarios.**

Use `Containers.Redis` and the existing launcher URI. Run scenarios in order with a unique test namespace suffix generated by `Base58.randomString(8)` only when isolation requires it, and clear owned keys through the lock API/fixture cleanup. Verify acquire → fixed lease expiry → new owner gets a strictly newer token → old handle terminal/release is rejected; ambiguous acquire reconciles with the same owner/request; close leaves no scheduler task; captured Micrometer observations contain only allowlisted values and no raw key/owner/request/token.

- [ ] **Step 2: Run the integration test and diagnose container failures.**

```bash
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistFencedRedisIntegrationTest' --no-build-cache --no-daemon --console=plain
```

Use the managed `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` only when the non-interactive process lacks it and verify Colima/docker context before applying it. A skipped Redis test is not passing evidence.

- [ ] **Step 3: Fix only adapter/configuration defects revealed by the integration test and rerun.**

The final output must include the Redis image/launcher evidence, expiry takeover result, close/task count, and metric redaction assertion. Commit only after a fresh GREEN result.

### Task 8: Public API/runbook synchronization and lesson draft

**Files:**
- Modify: `docs/api/waitlist-delivery.md`
- Modify: `docs/runbooks/waitlist-delivery.md`
- Create: `docs/lessons/2026-08-26-issue-311-waitlist-fenced-production.md`

- [ ] **Step 1: Update Korean docs from the final API.**

Document V31 additive rollout, `LockConfig(namespace = "bt4k:coord:v1")` with the
library-safe resource `waitlist-delivery` and derived key
`bt4k:coord:v1:{waitlist-delivery}:lock:waitlist-delivery`, fixed lease and bounded
watchdog boundary, typed dispatcher signature, strict-greater/exact-match semantics,
ambiguous reconcile, close ownership, fail-closed readiness, metric allowlist,
`enabled=false` rollback, and the fact that Redis is scheduler authority while DB
remains business authority. Keep identifiers/commands/API names exact and prose Korean.

- [ ] **Step 2: Write the lesson only from evidence.**

Include context, decision, outcome, proof commands, the missed docs-only hold, and future guard. Do not claim Redis/CI success before Task 9 evidence exists.

- [ ] **Step 3: Run documentation audits.**

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/api/waitlist-delivery.md docs/runbooks/waitlist-delivery.md docs/lessons/2026-08-26-issue-311-waitlist-fenced-production.md
```

Commit the docs/lesson with Korean Lore trailers after the implementation and verification evidence is final.

### Task 9: Full verification, six-lens 7-Tier review, and scope convergence

**Files:** final diff and all changed files.

- [ ] **Step 1: Run targeted and module verification.**

```bash
./gradlew :appointment-core:test --no-build-cache --no-daemon --console=plain
./gradlew :appointment-api:test --no-build-cache --no-daemon --console=plain
./gradlew :appointment-core:build :appointment-api:build --no-build-cache --no-daemon --console=plain
git diff --check
```

Read every result. Preserve any container failure as a concrete blocker with the exact diagnostic and do not downgrade it to skipped/pass.

- [ ] **Step 2: Apply the Kotlin final checklist.**

Record KT-FIN-01..11 evidence: touched callers/docs, bluetape4k validation/assertion usage, no new production `!!`, no suspend `runCatching`, resource/lifecycle ownership, Exposed transaction/operator correctness, triggered Spring/testing/Testcontainers references, named behavior tests, Korean KDoc/docs, clean diagnostics/fallback, fresh validation, and final scope.

- [ ] **Step 3: Perform independent 7-Tier lenses and integrate one verdict.**

Review reliability, performance, security, operations, developer/API, user/caller, and maintainability/architecture against the final diff. Findings use P0–P3 only with concrete path/symbol/test evidence. P0/P1 must be zero before PR; P2/P3 must be fixed, explicitly accepted in the issue, or registered as a duplicate-checked follow-up issue.

- [ ] **Step 4: Update the execution checklist and commit the final lesson.**

Check A-04 through A-09 only after plan review, risk evidence, implementation, verification, and lesson are read back. Include exact commit SHAs, test counts, migration/readiness evidence, redaction results, and any remaining conditional gates.

### Task 10: Authorized PR delivery and merge-ready handoff

**Files:** GitHub PR metadata/body and checklist only; no unrelated source changes.

- [ ] **Step 1: Create the PR only after the feature branch is complete.**

Use Korean title/body, link Issue #311, include `## DoD Status`, acceptance mapping, test commands/results, migration/rollback, risk review, and changed-file scope. Do not merge or enable auto-merge.

- [ ] **Step 2: Re-read live PR metadata, exact head, checks, reviews, and threads.**

```bash
gh pr view <number> --repo bluetape4k/clinic-appointment --json number,title,body,state,headRefName,baseRefName,commits,statusCheckRollup,reviews,comments
```

Require exact head CI green, no unresolved P0/P1, and live issue/PR metadata parity. Record CG-11..CG-15 evidence and leave CG-16 as PENDING until a fresh user `승인` names this exact head.

- [ ] **Step 3: Report merge-ready DoD, then stop before merge.**

The final report must state `Required checks: X/Y; N/A: N; Blocked: N`, changed files, commits, CI/review evidence, known gaps, and the exact PR/head awaiting the separate merge approval. After fresh approval only, perform merge, sync develop, verify branch/worktree parity, and clean the isolated worktree.

## Plan self-review record

- Spec coverage: AC-01..AC-08 map to Tasks 1–10 above; no acceptance item is left without a file, test, command, or rollback action.
- Placeholder scan: no `TBD`, `TODO`, “implement later”, or unnamed test step is used; every code step includes a signature or concrete SQL/predicate.
- Type consistency: `WaitlistFencingToken`, `claimFenced`, `WaitlistLeaseHandle`, `WaitlistFencedVacancyDispatcher`, and `WaitlistFencedDeliverySchedulingRunner` are introduced once and reused with the same names/signatures in later tasks.
- Scope guard: no default candidate/offer policy or reminder recovery replacement is introduced; production activation remains conditional on explicit ports and V31 readiness.

## SPW writer DoD

- SPW-01: Korean plan states goal, audience, current source boundaries, exact files, commands, and stop conditions.
- SPW-02: Tasks are bite-sized RED → GREEN → commit units with acceptance traceability and rollback boundaries.
- SPW-03: `korean-naturalness-checklist.md` KO-01~KO-07 is applied; technical identifiers remain exact.
- SPW-04: Every design review finding is tied to a concrete task: typed dispatcher, handle state machine, migration/readiness, performance/operations, and docs.
- SPW-05: Plan read-back confirms headings, paths, signatures, predicates, commands, and expected evidence are internally consistent.
