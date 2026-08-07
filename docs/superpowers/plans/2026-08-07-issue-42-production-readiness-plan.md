# Issue #42 production readiness follow-up 구현 계획

> **Execution contract:** 이 계획은 승인된 Type-A 후속 범위를 실행한다. 각 단계는
> 먼저 실패하는 테스트/검증을 만들고, 영향을 받은 모듈을 순차적으로 검증한다.

## 목표

MySQL V23 migration, Schema Registry Spring wiring, 실제 Kafka 4 listener lifecycle,
consumer observability/SLO evidence, replay authorization/source/retention을 현재
Issue #42 구현에 최소 diff로 연결한다.

## 변경 파일 지도

| 영역 | 예상 파일 | 결과 |
|---|---|---|
| migration | `appointment-api/src/test/.../migration/*`, `appointment-api/src/main/resources/db/migration/mysql/V23*` | MySQL V23 metadata contract |
| registry | `appointment-messaging/src/main/kotlin/.../AppointmentSchemaRegistry*`, `AppointmentMessaging*Properties*`, auto-config | endpoint/auth/fail-closed binding |
| listener | `appointment-messaging/src/main/kotlin/.../AppointmentKafkaConsumer*`, integration tests | real container crash/rebalance proof |
| metrics | `appointment-messaging/src/main/kotlin/.../AppointmentConsumerMetrics*`, runtime/store/health | bounded SLO signals |
| replay | `AppointmentReplayService*`, new source/authorizer, API security adapter | actor/tenant authorization and production source port |
| retention | `AppointmentConsumerInboxStore*`, V23 index/migration tests, scheduled service | bounded cleanup and lock evidence |
| benchmark/docs | `benchmark/appointment-messaging-benchmark`, `docs/benchmarks`, `docs/images/readme-charts`, `appointment-messaging/README*`, runbook | PostgreSQL chart and production verification notes |

## Ordered tasks

### Task 1 — Document and review the approved contract

- [x] Write this design and plan in Korean, preserving English only for code/API/URLs.
- [x] Run six perspective review (performance, stability, security, operator, developer,
      caller) and integrate findings. P0/P1 must be zero before implementation.
- [x] Commit spec/plan with Lore trailers before source edits.

Commands: `git diff --check -- docs/superpowers/specs docs/superpowers/plans`.

### Task 2 — Prove MySQL V23 migration readiness

- [x] Add a shared V23 contract assertion for table columns, primary keys, and indexes.
- [x] Call it from Flyway H2, MySQL, and PostgreSQL tests; use `MySQLServer.Launcher` and
      `PostgreSQLServer.Launcher` singleton fixtures only.
- [x] Add a production verification command/runbook section that accepts externally supplied
      JDBC endpoint without committing credentials; record production execution as PENDING
      when no endpoint is available.
- [x] Verify readiness schema/catalog lookup against MySQL metadata without renaming
      `scheduling_*` tables.

Targeted checks: `:appointment-api:test --tests '*FlywayMySQLMigrationTest*'`,
`:appointment-api:test --tests '*AppointmentConsumerMigrationContractTest*'`.

### Task 3 — Wire Schema Registry endpoint and credentials

- [x] Add immutable binding/properties and a credential resolver port; default remains static
      local validation when registry is disabled.
- [x] Extend the JDK compatibility reader with bounded URI validation, endpoint path encoding,
      HTTPS/loopback policy, and Basic `Authorization` injection without secret logging.
- [x] Register conditional Spring beans in the correct auto-configuration phase and include
      registry readiness in startup/readiness validation.
- [x] Add tests for endpoint path, timeout, positive Basic auth, missing/invalid auth,
      compatibility mismatch, disabled fallback, and no-secret diagnostics.

Targeted checks: `:appointment-messaging:test --tests '*AppointmentSchemaRegistry*'`
and `:appointment-messaging:test --tests '*AppointmentMessagingAutoConfigurationTest*'`.

### Task 4 — Verify actual Kafka 4 listener crash/rebalance

- [x] Add the runtime listener adapter that passes manual `Acknowledgment` to
      `AppointmentConsumerRuntime` and never acknowledges before durable processing.
- [x] Strengthen the `ConcurrentMessageListenerContainer` factory with explicit lifecycle,
      group/topic allow-list, shutdown, and recovery assertions.
- [x] Extend the singleton Kafka integration test: one handler throws before ack, a second
      container joins the group, and the record is recovered exactly once after rebalance or
      bounded retry. Assert committed offsets and quarantine metadata, not only in-memory calls.

Targeted check: sequential `:appointment-messaging:test --tests '*AppointmentKafkaConsumerIntegrationTest*'`
plus existing consumer configuration/runtime tests.

### Task 5 — Add metrics, lag/lock signals, retention, and SLO evidence

- [x] Add `AppointmentConsumerMetrics` with Noop and Micrometer implementations. Register
      bounded counters/timers/gauges for outcome, retry/quarantine, lag/oldest age, inbox
      transaction latency, replay, and cleanup.
- [x] Instrument runtime, inbox store, replay service, and health details without high-cardinality
      tenant/event/payload labels.
- [x] Add bounded retention service/configuration for processed/quarantined inbox and replay
      audit rows; protect active `PROCESSING` rows and expose cleanup result metrics.
- [x] Extend the existing PostgreSQL `kotlinx-benchmark` consumer suite with lock-contention
      samples and a machine-readable SLO evidence report; do not claim deployment SLO from
      benchmark values.
- [x] Generate/update one source-backed chart (SVG→PNG) for duplicate/cleanup/lock latency and
      inspect it full-size. Keep EN/KO README values source-equivalent.

Targeted checks: `:appointment-messaging:test`,
`:appointment-messaging-benchmark:test`, `:appointment-messaging-benchmark:mainSmokeBenchmark`.

### Task 6 — Implement production replay adapter and authorization

- [x] Define `AppointmentReplayAuthorizer` and authenticated actor/tenant scope value objects;
      reject blank actor, cross-tenant scope, invalid range, and missing approval before audit
      or source calls.
- [x] Implement bounded `KafkaAppointmentReplaySource` with dedicated group, poll timeout,
      close-on-all-paths, and runtime dispatch; no operations group rewind.
- [x] Keep the replay boundary as an application adapter port; no new public route is added
      because this repository has no approved replay endpoint/claim mapping yet. Existing Spring
      Security integration remains an explicit production wiring PENDING item.
- [x] Add dry-run, approved execution, unauthorized, source failure, audit idempotency, and
      retention tests; update Korean replay runbook with production prerequisites.

Targeted checks: `:appointment-messaging:test --tests '*AppointmentReplayServiceTest*'`,
`:appointment-api:test --tests '*Security*'` for touched adapter paths.

### Task 7 — Type-A verification, PR, CI, and closeout

- [x] Run verifier traceability against every requirement and mark approved non-goals/PENDING
      production gaps explicitly.
- [x] Run per-module six-lens code review and performance/stability scan; converge P0=0/P1=0.
- [x] Run fresh targeted tests, affected module builds, `git diff --check`, and benchmark/report
      contract tests. Record production endpoints/credentials as unchecked when unavailable.
- [x] Record the full `:appointment-api:test` Context Mode timeout as a verification gap rather
      than a code failure; retain targeted migration/projection evidence and do not claim the
      complete API suite passed.
- [ ] Create English issue-linked PR with aligned metadata and final `## DoD Status` section.
- [ ] Recheck exact PR head, CI, review threads, and mergeability. Ask for fresh merge approval
      tied to that exact head; after approval merge, sync local `develop`, remove worktree,
      verify clean parity, and close the workflow receipt.

## Rollback and risk controls

- No destructive database operation or production dispatch is performed by this branch.
- Registry wiring is opt-in and fail-closed; disabling it preserves the existing static schema
  contract for local/test environments.
- Listener changes preserve manual ack and existing bounded recovery; if integration behavior
  is unstable, keep the test-only lifecycle harness and mark production crash evidence PENDING.
- Benchmark/chart artifacts are evidence, not SLO commitments; values and environment are
  recorded in the JSON source.
