# 이슈 #41 PostgreSQL `kotlinx-benchmark` 구현 계획

> **에이전트 작업자 참고:** 이 계획을 task 단위로 구현하려면 `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans` 하위 스킬을 반드시 사용한다. 단계 추적에는 checkbox(`- [ ]`) 구문을 사용한다.

**목표:** PostgreSQL production-schema outbox claim 성능을 `kotlinx-benchmark`로 측정하고 문서·chart·CI artifact로 재현한다.

**구조:** `benchmark/appointment-messaging-benchmark`가 bluetape4k PostgreSQL singleton과 Hikari `DataSource`를 소유한다. Flyway production migration 후 Exposed `Database.connect(DataSource)`와 실제 `JdbcAppointmentOutboxStore`를 호출하고, JSON validator와 chart generator가 결과를 소비한다.

**기술 스택:** Kotlin 2.4/Kotlin compiler API 2.3, Java 21 toolchain, Gradle version catalog, `org.jetbrains.kotlinx.benchmark` 0.4.17, PostgreSQL/Testcontainers singleton, HikariCP, Flyway, Exposed JDBC, Node.js SVG generator, CairoSVG.

---

## 작업 1: Gradle 경계와 공개 benchmark 진입점

**파일:**
- 수정: `gradle/libs.versions.toml`
- 수정: `build.gradle.kts`
- 수정: `settings.gradle.kts`
- 수정: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxStore.kt`
- 생성: `benchmark/appointment-messaging-benchmark/build.gradle.kts`

- [x] 버전 `kotlinx-benchmark = "0.4.17"`, plugin alias
  `org.jetbrains.kotlinx.benchmark`, runtime library alias를 추가한다.
- [x] `settings.gradle.kts`에 `includeBenchmarkModules()`를 추가하고
  `:appointment-messaging-benchmark`를 nested directory에 명시적으로 매핑한다.
  production module 자동 탐색은 그대로 둔다.
- [x] `JdbcAppointmentOutboxStore` 생성자 visibility만 `internal`에서 public으로
  변경한다. 기본값과 validation은 모두 유지한다.
- [x] benchmark module을 `kotlin("plugin.allopen")`, benchmark plugin, project
  dependency, Flyway/PostgreSQL/Hikari/Testcontainers runtime,
  `benchmark { targets { register("main") } }`로 구성한다.
- [x] 공개 store 생성자에 대해 benchmark 진입점을 compile한다. benchmark
  module 자체가 source-level API contract다.

실행:

```bash
./gradlew projects --no-daemon --console=plain
./gradlew :appointment-messaging-benchmark:tasks --all --no-daemon --console=plain
```

예상 결과: benchmark project가 목록에 표시되고 구성이 끝나면
`mainBenchmark`/`mainSmokeBenchmark` 이름을 확인할 수 있다.

## 작업 2: RED test와 PostgreSQL benchmark 상태

**파일:**
- 생성: `benchmark/appointment-messaging-benchmark/src/test/kotlin/io/bluetape4k/clinic/appointment/benchmark/BenchmarkReportContractTest.kt`
- 생성: `benchmark/appointment-messaging-benchmark/src/main/kotlin/io/bluetape4k/clinic/appointment/benchmark/BenchmarkReportContract.kt`
- 생성: `benchmark/appointment-messaging-benchmark/src/main/kotlin/io/bluetape4k/clinic/appointment/benchmark/PostgreSqlAppointmentOutboxBenchmark.kt`
- 생성: `benchmark/appointment-messaging-benchmark/src/main/kotlin/io/bluetape4k/clinic/appointment/benchmark/PostgreSqlBenchmarkFixture.kt`

- [x] benchmark name, `postgresql` marker, row count, score, p50/p95/p99가
  없으면 거부하는 report contract test를 먼저 작성한다. parser/fixture를
  구현하기 전에 실행해 예상 실패를 확인한다.
- [x] lazy singleton Postgres, Hikari pool, Flyway migration,
  `Database.connect(dataSource)`, deterministic tenant/clinic seed와 20,000개
  혼합 row를 사용하는 `@State(Scope.Benchmark)` fixture를 구현한다. setup/reset에는
  JDBC만 사용하고 모든 Exposed operation은 `transaction {}` 안에서 실행한다.
- [x] `JdbcAppointmentOutboxStore(maxClinicBatch = 4).claim("benchmark", 32,
  Duration.ofSeconds(30))`를 호출하고 반환 list를 소비하는 bounded benchmark
  method 하나를 구현한다. fork마다 새 isolated schema를 seed하고 측정 호출
  안에서 20,000개 row를 reset하지 않는다.
- [x] Gradle benchmark configuration에 smoke/full iteration과 JSON output을
  설정한다. production SLO threshold는 추가하지 않는다.

순차 실행:

```bash
./gradlew :appointment-messaging-benchmark:compileKotlin --no-daemon --console=plain
./gradlew :appointment-messaging-benchmark:test --tests '*BenchmarkReportContractTest' --no-daemon --console=plain
```

예상 결과: compilation이 성공하고 validator 구현이 있어야 contract test가
통과한다. Docker는 benchmark 실행에는 필요하지만 unit contract parsing에는
필요하지 않다.

## 작업 3: benchmark JSON 실행·수집·검증

**파일:**
- 생성: `scripts/collect-appointment-messaging-benchmark.mjs`
- 생성: `scripts/validate-appointment-messaging-benchmark.mjs`
- 수정: `benchmark/appointment-messaging-benchmark/build.gradle.kts`
- 생성: `docs/benchmarks/appointment-messaging-postgresql-baseline.json`

- [x] 검증된 smoke task를 한 번 실행하고 실제 plugin output path를 조사한다.
- [x] collector가 요청한 `main`/`smoke` directory에서 claim result를 정확히
  하나 선택하고 plugin metadata를 보존하게 한다. 실제 timestamp가 포함된
  `sourceFile`과 안정적인 `sourceFilePattern`을 report metadata에 기록한다.
- [x] score/percentile이 없거나 양수가 아니면 validator가 fail closed하고
  H2 marker나 누락된 PostgreSQL metadata를 거부하게 한다.
- [x] Docker에서 full task를 한 번 실행하고 그 출력을 commit할 baseline으로
  사용한다. 측정값을 수동으로 편집하지 않는다.

실행:

```bash
./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark --no-daemon --console=plain
node scripts/collect-appointment-messaging-benchmark.mjs --input-dir benchmark/appointment-messaging-benchmark/build/reports/benchmarks --output build/reports/appointment-messaging-postgresql.json --config smoke
node scripts/validate-appointment-messaging-benchmark.mjs --input build/reports/appointment-messaging-postgresql.json
```

예상 결과: collector와 validator가 exit 0으로 종료하고 선택한 benchmark와
percentile 값을 출력한다. Docker가 없거나 JSON이 malformed이면 remediation
message와 함께 non-zero로 종료한다.

## 작업 4: 이중 언어 chart/documentation 생성 및 검증

**파일:**
- 생성: `scripts/generate-appointment-messaging-benchmark-chart.mjs`
- 생성: `docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-en.svg`
- 생성: `docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-en.png`
- 생성: `docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-ko.svg`
- 생성: `docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-ko.png`
- 수정: `README.md`
- 수정: `README.ko.md`
- 수정: `appointment-messaging/README.md`
- 수정: `appointment-messaging/README.ko.md`
- 수정: `AGENTS.md`

- [x] 같은 baseline JSON에서 명시적 단위, p50/p95/p99 label,
  PostgreSQL/20,000/seed metadata, non-SLO caveat를 포함한 두 locale SVG를
  모두 생성한다.
- [x] CairoSVG로 PNG를 렌더링하고 표준 Python library로 XML을 parsing한다.
  Pillow로 PNG를 decode/size-check하고 chart geometry/endpoint audit를 실행한
  뒤 각 PNG를 원본 해상도로 검사한다.
- [x] 동등한 영문/한글 module row, command snippet, metric table, chart link를
  추가한다. locale 간 code/identifier/URL text는 정확히 보존한다.
- [x] 관련 없는 운영 규칙을 바꾸지 않고 benchmark module을 repository module
  guidance에 추가한다.

## 작업 5: CI 및 nightly artifact lane

**파일:**
- 수정: `.github/workflows/ci.yml`
- 수정: `.github/workflows/nightly.yml`

- [x] messaging change detector에 benchmark/docs/chart path를 추가한다.
- [x] 검증된 Gradle smoke task, collector/validator, chart generator를 실행하고
  JSON/SVG/PNG artifact를 upload하는 serialized PR smoke job을 추가한다.
- [x] 같은 validation/upload step을 수행하는 serialized nightly full job을
  추가한다. nightly status job에 연결하되 Kover coverage 요구사항에서는
  제외한다.
- [x] actionlint(사용할 수 없으면 repository YAML checker)를 실행하고 실제
  Gradle task name과 job dependency path가 일치하는지 검증한다.

## 작업 6: review·lesson·완료 증거

**파일:**
- 생성: `docs/reviews/2026-08-06-issue-41-postgresql-kotlinx-benchmark-review.ko.md`
- 생성: `docs/lessons/2026-08-06-issue-41-postgresql-kotlinx-benchmark.md`

- [x] targeted messaging test, benchmark contract test, compile/build, JSON, chart,
  YAML, docs parity, hosted PR smoke, `git diff --check` validation을 순차적으로
  실행한다.
- [x] independent review lens(correctness, security/data, performance,
  operability/CI, docs/API, integration)별 P0/P1/P2/P3 count를 기록한다.
- [x] 학습 내용, 정확한 command, evidence path, 남은 deployment-gate 제한을
  한국어로 기록한다.
- [x] 각 coherent batch를 repository Lore commit trailer와 함께 commit한다.

## 작업 7: PR 및 merge handoff

- [ ] `bluetape4k/clinic-appointment`에 base `develop`, head
  `feat/issue-41-postgresql-kotlinx-benchmark`로 PR을 생성하고, `## DoD Status`와
  `Related to #41`로 끝나는 영문 본문을 작성한다.
- [ ] PR metadata parity, live check, review thread, 정확한 head를 검증한다.
- [ ] 새 approval이 검증된 PR head를 명시할 때까지 merge-ready에서 중지한다.
  그 뒤 root `develop`을 sync하면서 사용자 소유 `README.ko.md`를 보존하고,
  feature worktree/branch를 제거한 뒤 branch parity를 검증한다.
