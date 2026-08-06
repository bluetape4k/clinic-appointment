# Issue #41 PostgreSQL `kotlinx-benchmark` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PostgreSQL production-schema outbox claim 성능을 `kotlinx-benchmark`로 측정하고 문서·chart·CI artifact로 재현한다.

**Architecture:** `benchmark/appointment-messaging-benchmark`가 bluetape4k PostgreSQL singleton과 Hikari `DataSource`를 소유한다. Flyway production migration 후 Exposed `Database.connect(DataSource)`와 실제 `JdbcAppointmentOutboxStore`를 호출하고, JSON validator와 chart generator가 결과를 소비한다.

**Tech Stack:** Kotlin 2.4/Kotlin compiler API 2.3, Java 21 toolchain, Gradle version catalog, `org.jetbrains.kotlinx.benchmark` 0.4.17, PostgreSQL/Testcontainers singleton, HikariCP, Flyway, Exposed JDBC, Node.js SVG generator, CairoSVG.

---

## Task 1: Gradle boundary and public benchmark entry point

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxStore.kt`
- Create: `benchmark/appointment-messaging-benchmark/build.gradle.kts`

- [x] Add version `kotlinx-benchmark = "0.4.17"`, plugin alias
  `org.jetbrains.kotlinx.benchmark`, and runtime library alias.
- [x] Add `includeBenchmarkModules()` to `settings.gradle.kts` and explicitly map
  `:appointment-messaging-benchmark` to the nested directory; keep automatic
  production module discovery unchanged.
- [x] Change only `JdbcAppointmentOutboxStore` constructor visibility from
  `internal` to public. Keep all defaults and validation unchanged.
- [x] Configure the benchmark module with `kotlin("plugin.allopen")`, the benchmark
  plugin, project dependencies, Flyway/PostgreSQL/Hikari/Testcontainers runtime,
  and `benchmark { targets { register("main") } }`.
- [x] Compile the benchmark entry point against the public store constructor; the
  benchmark module itself is the source-level API contract.

Run:

```bash
./gradlew projects --no-daemon --console=plain
./gradlew :appointment-messaging-benchmark:tasks --all --no-daemon --console=plain
```

Expected: benchmark project is listed and `mainBenchmark`/`mainSmokeBenchmark`
names are visible after the configuration is complete.

## Task 2: Red test and PostgreSQL benchmark state

**Files:**
- Create: `benchmark/appointment-messaging-benchmark/src/test/kotlin/io/bluetape4k/clinic/appointment/benchmark/BenchmarkReportContractTest.kt`
- Create: `benchmark/appointment-messaging-benchmark/src/main/kotlin/io/bluetape4k/clinic/appointment/benchmark/BenchmarkReportContract.kt`
- Create: `benchmark/appointment-messaging-benchmark/src/main/kotlin/io/bluetape4k/clinic/appointment/benchmark/PostgreSqlAppointmentOutboxBenchmark.kt`
- Create: `benchmark/appointment-messaging-benchmark/src/main/kotlin/io/bluetape4k/clinic/appointment/benchmark/PostgreSqlBenchmarkFixture.kt`

- [x] First write a report contract test that rejects missing benchmark name,
  `postgresql` marker, row count, score, or p50/p95/p99. Run it and observe the
  expected failure before implementing the parser/fixture.
- [x] Implement `@State(Scope.Benchmark)` fixture with lazy singleton Postgres,
  Hikari pool, Flyway migration, `Database.connect(dataSource)`, deterministic
  tenant/clinic seed and 20,000 mixed rows. Use JDBC only for setup/reset; every
  Exposed operation remains inside `transaction {}`.
- [x] Implement one bounded benchmark method that invokes
  `JdbcAppointmentOutboxStore(maxClinicBatch = 4).claim("benchmark", 32,
  Duration.ofSeconds(30))` and consumes the returned list. Seed a fresh isolated
  schema per fork; do not reset 20,000 rows inside measured invocations.
- [x] Configure smoke/full iterations and JSON output in the Gradle benchmark
  configuration. Do not add production SLO thresholds.

Run sequentially:

```bash
./gradlew :appointment-messaging-benchmark:compileKotlin --no-daemon --console=plain
./gradlew :appointment-messaging-benchmark:test --tests '*BenchmarkReportContractTest' --no-daemon --console=plain
```

Expected: compilation succeeds; the contract test passes only after the validator
implementation is present; Docker is required for benchmark execution, not unit
contract parsing.

## Task 3: Execute, collect, and validate benchmark JSON

**Files:**
- Create: `scripts/collect-appointment-messaging-benchmark.mjs`
- Create: `scripts/validate-appointment-messaging-benchmark.mjs`
- Modify: `benchmark/appointment-messaging-benchmark/build.gradle.kts`
- Create: `docs/benchmarks/appointment-messaging-postgresql-baseline.json`

- [x] Run the verified smoke task once and inspect the actual plugin output path.
- [x] Make the collector select exactly one claim result, preserve plugin metadata,
  and write a stable report with image/JVM/seed/row-count/config metadata.
- [x] Make the validator fail closed on absent or non-positive score/percentiles and
  reject H2 markers or missing PostgreSQL metadata.
- [x] Run the full task once against Docker and use that output as the committed
  baseline; do not hand-edit measured values.

Run:

```bash
./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark --no-daemon --console=plain
node scripts/collect-appointment-messaging-benchmark.mjs --input-dir benchmark/appointment-messaging-benchmark/build/benchmarks --output build/reports/appointment-messaging-postgresql.json
node scripts/validate-appointment-messaging-benchmark.mjs --input build/reports/appointment-messaging-postgresql.json
```

Expected: collector and validator exit 0 and print the selected benchmark plus
percentile values; missing Docker or malformed JSON exits non-zero with a remediation
message.

## Task 4: Generate and verify bilingual chart/documentation

**Files:**
- Create: `scripts/generate-appointment-messaging-benchmark-chart.mjs`
- Create: `docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-en.svg`
- Create: `docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-en.png`
- Create: `docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-ko.svg`
- Create: `docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-ko.png`
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `appointment-messaging/README.md`
- Modify: `appointment-messaging/README.ko.md`
- Modify: `AGENTS.md`

- [x] Generate both locale SVGs from the same baseline JSON with explicit units,
  p50/p95/p99 labels, PostgreSQL/20,000/seed metadata, and the non-SLO caveat.
- [x] Render PNGs with CairoSVG, run `xmllint`, `identify`, chart geometry/endpoint
  audits, and inspect each PNG at original resolution.
- [x] Add equivalent English/Korean module rows, command snippets, metric table and
  chart links; preserve code/identifier/URL text exactly across locales.
- [x] Add the benchmark module to the repository module guidance without changing
  unrelated operating rules.

## Task 5: CI and nightly artifact lanes

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/nightly.yml`

- [x] Add benchmark/docs/chart paths to the messaging change detector.
- [x] Add a serialized PR smoke job that runs the verified Gradle smoke task, the
  collector/validator, chart generator, and uploads JSON/SVG/PNG artifacts.
- [x] Add a serialized nightly full job with the same validation and upload steps;
  wire it into the nightly status job, but keep it out of Kover coverage needs.
- [x] Run actionlint (or the repository YAML checker if actionlint is unavailable)
  and verify job dependency paths against the actual Gradle task names.

## Task 6: Review, lesson, and completion evidence

**Files:**
- Create: `docs/reviews/2026-08-06-issue-41-postgresql-kotlinx-benchmark-review.ko.md`
- Create: `docs/lessons/2026-08-06-issue-41-postgresql-kotlinx-benchmark.md`

- [ ] Run targeted messaging tests, benchmark contract tests, compile/build, JSON,
  chart, YAML, docs parity, and `git diff --check` validations sequentially.
- [ ] Record independent review lenses (correctness, security/data, performance,
  operability/CI, docs/API, integration) with P0/P1/P2/P3 counts.
- [ ] Record what was learned, exact commands, evidence paths, and remaining
  deployment-gate limitations in Korean.
- [ ] Commit each coherent batch with the repository Lore commit trailers.

## Task 7: PR and merge handoff

- [ ] Create PR in `bluetape4k/clinic-appointment`, base `develop`, head
  `feat/issue-41-postgresql-kotlinx-benchmark`, with English body ending in
  `## DoD Status` and `Related to #41`.
- [ ] Verify PR metadata parity, live checks, review threads, and exact head.
- [ ] Stop at merge-ready until a fresh approval names the verified PR head; then
  merge, sync root `develop` while preserving the user-owned `README.ko.md`, remove
  the feature worktree/branch, and verify branch parity.
