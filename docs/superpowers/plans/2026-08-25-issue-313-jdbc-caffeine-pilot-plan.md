# Issue #313 JDBC Caffeine 정책 기준 데이터 파일럿 구현 계획

> **For agentic workers:** 승인된 설계와 이 계획을 순서대로 실행한다. 각 단계는 체크박스를 갱신하고, 지정한 명령의 fresh evidence를 남긴다. 사용자 지침에 따라 독립 리뷰 lane은 만들지 않고 main session inline review로 통합한다.

**Goal:** 생산 정책 캐시를 변경하지 않고 `bluetape4k-exposed-jdbc-caffeine:1.12.1`의
JDBC transaction-aware cache 경로를 테스트 전용 파일럿으로 검증하고, commit/fence
계약과 반복 측정·chart 근거로 `ADOPT` 또는 `HOLD`를 판정한다.

**Architecture:** `appointment-api/src/test`에만 `EffectivePolicyCacheKey`와
`EffectiveSchedulingPolicy`를 detached `CacheSnapshot`으로 다루는 작은 fixture를
둔다. Exposed 최상위 `JdbcTransaction`에서 `stageSnapshot`/`stageInvalidation`을
호출하고, 권위 세대 저장 실패 때는 stage하지 않는다. 생산 `EffectivePolicyCache`,
`EffectiveSchedulingPolicyService`, Spring bean graph와 DB schema는 그대로 둔다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed 1.4 JDBC, JUnit 5,
H2, `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-caffeine:1.12.1`,
Node.js SVG generator, `bluetape-diagram` chart audit.

---

## 파일 소유권과 산출물

| 파일 | 책임 |
|---|---|
| `gradle/libs.versions.toml` | `exposed-jdbc-caffeine` catalog alias를 기존 `exposed-*` 묶음에 추가한다. |
| `appointment-api/build.gradle.kts` | dependency를 `testImplementation`으로만 추가하고 test classpath 기반 benchmark task를 등록한다. |
| `appointment-api/gradle.lockfile` | `--write-locks`로 생성한 실제 lock 범위를 보존한다. 수동으로 버전을 입력하지 않는다. |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotFixture.kt` | H2 `Database`, Caffeine 정책 기준 데이터 cache, detached policy fixture, commit/rollback/fence helper를 소유한다. 모든 선언은 `internal`로 제한한다. |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotTest.kt` | RED→GREEN 계약 테스트와 production cache regression read-back을 소유한다. |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotBenchmark.kt` | 고정 warm-up/sample, p50/p95/p99, cold-start와 thread allocation을 측정하고 JSON을 쓴다. |
| `scripts/generate-issue313-jdbc-caffeine-chart.mjs` | JSON을 검증하고 한국어 SVG와 semantic ledger를 같은 source에서 생성한다. |
| `docs/benchmarks/issue-313-jdbc-caffeine-pilot/` | 측정 summary, chart, semantic ledger와 provenance를 보존한다. build report 원자료는 재생성 경로를 기록한다. |
| `docs/superpowers/reviews/2026-08-25-issue-313-jdbc-caffeine-pilot-plan-review.md` | Step 3-R inline review의 P0/P1/P2/P3와 결정 근거를 기록한다. |
| `docs/superpowers/reviews/2026-08-25-issue-313-jdbc-caffeine-pilot-inline-review.md` | 최종 diff의 6-lens inline review와 convergence를 기록한다. |
| `docs/lessons/2026-08-25-issue-313-jdbc-caffeine-pilot.md` | 실제 측정 결과, HOLD/ADOPT 결정, 다음 guard를 기록한다. |

다음 파일은 수정하지 않는다: `appointment-core/src/main`,
`appointment-api/src/main`, `ServiceConfig.kt`, Flyway SQL, `settings.gradle.kts`,
GitHub Actions workflow, frontend. README/API 문서는 production 공개 표면이
변경되지 않으므로 source-backed N/A로 기록한다.

## Task 1: test-only dependency 경계 고정

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `appointment-api/build.gradle.kts`
- Modify: `appointment-api/gradle.lockfile` (Gradle generated)

- [x] **Step 1: catalog alias와 RED dependency 선언 추가**

  기존 `exposed-jdbc`와 같은 섹션에 다음 alias를 추가하고 `appointment-api`의
  dependencies에 정확히 한 줄을 추가한다.

  ```toml
  exposed-jdbc-caffeine = { module = "io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-caffeine" }
  ```

  ```kotlin
  testImplementation(libs.exposed.jdbc.caffeine)
  ```

  `implementation`, `api`, `runtimeOnly`로 승격하지 않는다. production source에서
  이 alias를 import하지 않는다.

- [x] **Step 2: lockfile을 실제 Gradle resolver로 갱신**

  ```bash
  ./gradlew :appointment-api:dependencies \
    --configuration testCompileClasspath \
    --write-locks --no-daemon
  ```

  Expected: `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-caffeine:1.12.1`
  와 필요한 `exposed-cache` dependency가 `testCompileClasspath`/필요한 test
  configurations에만 기록된다. 다른 dependency가 configuration scope를 넓히면
  diff를 확인하고 test-only boundary를 복구한 뒤 다시 lock한다.

- [x] **Step 3: dependency boundary를 read-back**

  ```bash
  ./gradlew :appointment-api:dependencies --configuration runtimeClasspath --no-daemon
  ./gradlew :appointment-api:dependencies --configuration productionRuntimeClasspath --no-daemon
  rg -n 'bluetape4k-exposed-jdbc-caffeine|bluetape4k-exposed-cache' appointment-api/gradle.lockfile
  ```

  Expected: 두 production classpath 출력에 JDBC Caffeine artifact가 없고,
  lockfile의 새 artifact scope가 production/runtime이 아니다. 이 단계에서
  leakage가 발견되면 다음 task로 넘어가지 않는다.

- [x] **Step 4: dependency boundary commit**

  ```bash
  git add gradle/libs.versions.toml appointment-api/build.gradle.kts appointment-api/gradle.lockfile
  git commit -m "Issue #313 파일럿 의존성을 테스트 경계에 가둔다" -m "JDBC Caffeine snapshot artifact를 catalog와 test classpath에만 연결한다.

  Constraint: production cache wiring과 bootJar에는 새 artifact가 없어야 한다.
  Rejected: implementation 승격 | 파일럿 범위와 rollback 경계를 넓힌다.
  Confidence: high
  Scope-risk: narrow
  Directive: 생산 도입 전 test-only scope를 유지한다.
  Tested: testCompileClasspath lock 및 runtime/productionRuntime boundary read-back.
  Not-tested: fixture transaction behavior는 다음 task에서 검증한다."
  ```

## Task 2: transaction/fence 계약 RED 테스트 작성

**Files:**

- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotTest.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotFixture.kt`

- [x] **Step 1: fixture 없는 RED 테스트를 먼저 작성**

  다음 테스트 이름을 유지하고, 아직 helper를 정의하지 않은 상태에서 먼저
  컴파일시켜 RED를 확인한다.

  ```kotlin
  class JdbcCaffeineEffectivePolicyPilotTest {
      @Test
      fun `commit 뒤에만 정책 기준 데이터를 게시한다`() { /* RED */ }

      @Test
      fun `rollback이면 준비한 정책 기준 데이터를 게시하지 않는다`() { /* RED */ }

      @Test
      fun `세대 저장 충돌이면 stage하지 않고 캐시를 오염시키지 않는다`() { /* RED */ }

      @Test
      fun `clinic 무효화 뒤 오래된 miss는 local fence에서 거부한다`() { /* RED */ }

      @Test
      fun `tenant와 clinic scope를 key로 격리하고 miss token을 재사용하지 않는다`() { /* RED */ }

      @Test
      fun `pilot toggle을 끄면 기존 EffectivePolicyCache 경로를 사용한다`() { /* RED */ }
  }
  ```

- [x] **Step 2: RED 명령 실행**

  ```bash
  ./gradlew :appointment-api:test --tests \
    'io.bluetape4k.clinic.appointment.api.config.JdbcCaffeineEffectivePolicyPilotTest' \
    --no-daemon
  ```

  Expected: `JdbcCaffeineEffectivePolicyPilotFixture` 또는 helper symbol 부재로
  compile 실패한다. 기존 모듈 실패가 섞이면 로그에서 pilot 오류와 기존 오류를
  분리하고, 기존 기준선 테스트를 다시 실행한다.

- [x] **Step 3: detached policy fixture와 key를 고정**

  `EffectivePolicyCacheTest`의 `SchedulingPolicyCompiler.compileCapacity` fixture를
  재사용해 `EffectiveSchedulingPolicy`와
  `EffectivePolicyCacheKey(tenantGroupId, clinicId, generation, decisionAt, serviceAt)`를
  만든다. 새 domain DTO나 production factory를 추가하지 않는다. key와 value 모두
  `Serializable` 계약을 유지하고 payload에 Entity/transaction object를 담지 않는다.

- [x] **Step 4: H2 transaction/cache fixture를 최소 구현**

  다음 공개 API 조합만 사용한다.

  ```kotlin
  private val candidate = jdbcCaffeineSnapshotCache<EffectivePolicyCacheKey, EffectiveSchedulingPolicy>(
      CaffeineSnapshotCacheConfig(
          snapshot = SnapshotCacheConfig("clinic-policy-jdbc-caffeine:v1", "effective-policy-v1"),
          maximumSize = 64,
          expireAfterWrite = Duration.ofMinutes(5),
          expireAfterAccess = Duration.ofMinutes(1),
          fenceStripes = 64,
      ),
  )

  fun commit(key: EffectivePolicyCacheKey, value: EffectiveSchedulingPolicy) {
      val lookup = requireNotNull(candidate.lookup(key).miss)
      transaction {
          stageSnapshot(candidate, lookup, CacheSnapshot(value, revision = revision(value)))
      }
  }

  fun invalidate(key: EffectivePolicyCacheKey) {
      transaction { stageInvalidation(candidate, key) }
  }
  ```

  H2 `Database.connect`는 test fixture가 소유하고 `@AfterEach`에서
  `TransactionManager.closeAndUnregister()`와 candidate 참조를 정리한다. Exposed
  transaction 안에서만 `stageSnapshot`/`stageInvalidation`을 호출한다. raw
  `Thread.sleep`, `@Testcontainers`, 새 executor는 사용하지 않는다.

- [x] **Step 5: GREEN 계약 구현 후 targeted test 실행**

  helper를 추가해 다음을 assertion한다.

  - commit 전 `lookup(key).snapshot == null`, commit 뒤 동일 value/revision hit
  - transaction body가 예외를 던지면 정책 기준 데이터가 여전히 null
  - `saveIfGenerationMatches`가 예외를 던진 경로에서는 stage 호출 자체가 없고 null
  - invalidation 이후 old miss를 stage해도 candidate cache에 old value가 생기지 않음
  - 같은 logical payload라도 tenant 또는 clinic이 다르면 서로의 hit가 아님
  - 동일 miss token을 두 번 claim/stage하지 않음
  - toggle OFF는 baseline `EffectivePolicyCache`를 사용하고 candidate는 비어 있음

  ```bash
  ./gradlew :appointment-api:test --tests \
    'io.bluetape4k.clinic.appointment.api.config.JdbcCaffeineEffectivePolicyPilotTest' \
    --no-daemon
  ./gradlew :appointment-core:test --tests \
    'io.bluetape4k.clinic.appointment.policy.EffectivePolicyCacheTest' \
    --no-daemon
  ./gradlew :appointment-api:test --tests \
    'io.bluetape4k.clinic.appointment.api.policy.EffectiveSchedulingPolicyServiceTest' \
    --no-daemon
  ```

  Expected: pilot 신규 테스트와 기존 기준선이 모두 PASS한다.

- [x] **Step 6: transaction contract commit**

  ```bash
  git add appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotFixture.kt \
    appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotTest.kt
  git commit -m "Issue #313 JDBC Caffeine 커밋 경계를 파일럿으로 검증한다" -m "detached 정책 기준 데이터의 commit-only publication, rollback, 세대 충돌과 local fence를 테스트 전용 fixture로 고정한다.

  Constraint: production EffectivePolicyCache와 service는 변경하지 않는다.
  Rejected: cache facade를 production bean으로 주입 | 파일럿 결과가 없는 상태에서 동작 계약을 바꾼다.
  Confidence: high
  Scope-risk: narrow
  Directive: stage는 최상위 JdbcTransaction 안에서만 호출한다.
  Tested: targeted pilot, core cache regression, API policy regression.
  Not-tested: 반복 benchmark와 chart는 다음 task에서 실행한다."
  ```

## Task 3: 반복 측정 harness와 benchmark task 추가

**Files:**

- Modify: `appointment-api/build.gradle.kts`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotBenchmark.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotFixture.kt`

- [ ] **Step 1: benchmark runner와 report schema를 먼저 고정**

  `JdbcCaffeineEffectivePolicyPilotBenchmark.main()`은 system property로 output
  path를 받고, 기본값을 `build/reports/issue-313/jdbc-caffeine-pilot.json`으로
  둔다. JSON은 다음 필드를 반드시 포함한다.

  ```json
  {
    "schemaVersion": 1,
    "benchmarkFamily": "io.bluetape4k.clinic.appointment.api.config.JdbcCaffeineEffectivePolicyPilotBenchmark",
    "sourceCommit": "git rev-parse HEAD 결과",
    "warmupRounds": 5,
    "measurementRounds": 20,
    "environment": {"java": "...", "os": "...", "db": "h2"},
    "profiles": [],
    "productionSloEvidence": false
  }
  ```

  raw policy payload와 tenant/clinic 식별자는 report에 쓰지 않는다.

- [ ] **Step 2: baseline/candidate 대칭 프로필 구현**

  동일한 detached value와 key에 대해 다음 프로필을 각각 5 warm-up/20 measured
  sample로 실행한다. 각 sample은 `System.nanoTime()`으로 감싸고 p50/p95/p99와
  전체 sample을 보존한다.

  | profile | baseline | candidate |
  |---|---|---|
  | `hot-hit` | `EffectivePolicyCache.get` | `candidate.lookup(key).snapshot` |
  | `cold-fill-commit` | baseline `put` 후 `get` | miss → `stageSnapshot` → commit |
  | `invalidation-commit` | `invalidateClinic` | `stageInvalidation` → commit |
  | `cold-start` | 새 baseline cache 첫 연산 | 새 JDBC Caffeine cache 첫 연산 |

  `com.sun.management.ThreadMXBean`이 지원하면 measured thread의
  `getThreadAllocatedBytes` delta를 기록하고, 지원하지 않으면 allocation 값을
  `null`로 기록한다. allocation을 heap 전체 차이로 추정하지 않는다.

- [ ] **Step 3: benchmark JavaExec task 등록**

  `appointment-api/build.gradle.kts`에 기존 `JavaExec` 관례를 따라 import와 task를
  추가한다.

  ```kotlin
  import org.gradle.api.tasks.JavaExec

  tasks.register<JavaExec>("jdbcCaffeineEffectivePolicyPilotBenchmark") {
      group = "benchmark"
      description = "Issue #313 JDBC Caffeine policy cache pilot benchmark"
      dependsOn(tasks.named("testClasses"))
      classpath = sourceSets.test.get().runtimeClasspath
      mainClass.set("io.bluetape4k.clinic.appointment.api.config.JdbcCaffeineEffectivePolicyPilotBenchmark")
      systemProperty(
          "issue313.jdbcCaffeineBenchmark.output",
          providers.gradleProperty("issue313JdbcCaffeineBenchmarkOutput").orElse(
              layout.buildDirectory.file("reports/issue-313/jdbc-caffeine-pilot.json")
                  .map { it.asFile.absolutePath },
          ).get(),
      )
  }
  ```

- [ ] **Step 4: smoke report 실행과 raw evidence read-back**

  ```bash
  ./gradlew :appointment-api:jdbcCaffeineEffectivePolicyPilotBenchmark --no-daemon
  jq -e '
    .schemaVersion == 1 and
    .benchmarkFamily == "io.bluetape4k.clinic.appointment.api.config.JdbcCaffeineEffectivePolicyPilotBenchmark" and
    .warmupRounds == 5 and .measurementRounds == 20 and
    .productionSloEvidence == false and (.profiles | length) == 4
  ' appointment-api/build/reports/issue-313/jdbc-caffeine-pilot.json
  ```

  Expected: 모든 profile에 양수 sample, `p50 <= p95 <= p99`, cold-start와
  allocation support 상태가 있고, `productionSloEvidence=false`다. 실패하면
  해당 report를 chart source로 사용하지 않는다.

- [ ] **Step 5: benchmark task commit**

  ```bash
  git add appointment-api/build.gradle.kts \
    appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotBenchmark.kt \
    appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/JdbcCaffeineEffectivePolicyPilotFixture.kt
  git commit -m "Issue #313 파일럿 비용을 반복 측정할 수 있게 한다" -m "baseline과 JDBC Caffeine candidate의 hit, commit, invalidation, cold-start 비용을 동일한 반복 프로토콜로 기록한다.

  Constraint: 측정값은 production SLO가 아니라 local characterization evidence다.
  Rejected: 별도 JMH module | 이번 pilot에 새 benchmark runtime 계약을 추가하지 않는다.
  Confidence: medium
  Scope-risk: moderate
  Directive: report의 environment와 sourceCommit을 chart까지 전달한다.
  Tested: JavaExec benchmark smoke와 JSON schema assertions.
  Not-tested: PostgreSQL 다중 노드와 production traffic은 범위 밖이다."
  ```

## Task 4: chart·summary·provenance 산출물 생성

**Files:**

- Create: `scripts/generate-issue313-jdbc-caffeine-chart.mjs`
- Create: `docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.data.json`
- Create: `docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.svg`
- Create: `docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.semantic.json`
- Create: `docs/benchmarks/issue-313-jdbc-caffeine-pilot/summary.ko.md`
- Create: `docs/benchmarks/issue-313-jdbc-caffeine-pilot/provenance.json`

- [ ] **Step 1: chart skill references와 기존 generator를 read-back**

  chart 변경 전에 다음을 읽고 output contract를 고정한다.

  ```bash
  sed -n '1,320p' /Users/debop/.codex/skills/bluetape-diagram/SKILL.md
  sed -n '1,260p' /Users/debop/.codex/skills/bluetape-diagram/references/common.md
  sed -n '1,320p' /Users/debop/.codex/skills/bluetape-diagram/references/chart.md
  sed -n '1,260p' /Users/debop/.codex/skills/bluetape-diagram/references/semantic-ledger.md
  ```

- [ ] **Step 2: JSON validation과 SVG generator 작성**

  `scripts/generate-issue313-jdbc-caffeine-chart.mjs`는 `--input`, `--output`,
  `--semantic-output`을 필수로 받고, `schemaVersion`, benchmark family,
  sourceCommit, profile matrix, units, `productionSloEvidence=false`를 fail-closed
  검증한다. chart에는 baseline/candidate 색상, profile label, ns 단위, p50/p95,
  “낮을수록 좋음”, allocation `N/A`, H2/local characterization, production SLO가
  아니라는 설명을 포함한다. SVG의 모든 값은 JSON에서만 읽고 손으로 입력하지 않는다.

- [ ] **Step 3: report를 docs/chart data로 정규화**

  benchmark report의 raw sample 전체는 `build/reports`에 남기고, tracked
  `chart.data.json`에는 profile별 summary와 source path/commit/environment만
  기록한다. `summary.ko.md`에는 다음을 실제 수치로 작성한다.

  - commit/rollback/fence 테스트 결과
  - hot/cold/invalidation/cold-start p50/p95/p99
  - allocation 지원 여부와 `N/A` 이유
  - H2 및 local JVM 측정의 대표성 한계
  - 생산 도입 결론(`HOLD` 기본값)과 후속 production DB/multi-node 실험

- [ ] **Step 4: chart audit와 visual evidence 실행**

  ```bash
  node scripts/generate-issue313-jdbc-caffeine-chart.mjs \
    --input appointment-api/build/reports/issue-313/jdbc-caffeine-pilot.json \
    --output docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.svg \
    --semantic-output docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.semantic.json
  node --check scripts/generate-issue313-jdbc-caffeine-chart.mjs
  xmllint --noout docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.svg
  python3 /Users/debop/.codex/skills/bluetape-diagram/scripts/diagram-semantic-audit.py \
    docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.semantic.json
  python3 /Users/debop/.codex/skills/bluetape-diagram/scripts/diagram-endpoint-audit.py \
    docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.svg
  ```

  CairoSVG가 설치된 환경에서는 PNG를 생성하고 full-size 이미지도 확인한다.
  변환 도구가 없으면 SVG/semantic/XML audit는 실행하되 PNG 항목을 `PENDING`으로
  명시하며 chart 생성 성공만으로 visual QA를 통과시키지 않는다.

- [ ] **Step 5: chart artifact commit**

  ```bash
  git add scripts/generate-issue313-jdbc-caffeine-chart.mjs \
    docs/benchmarks/issue-313-jdbc-caffeine-pilot
  git commit -m "Issue #313 파일럿 측정 결과를 한국어 chart로 고정한다" -m "동일 JSON source에서 baseline/candidate latency와 cold-start·allocation 상태를 재현 가능한 SVG와 summary로 만든다.

  Constraint: chart는 local characterization이며 production SLO 증거가 아니다.
  Rejected: raw 숫자만 PR에 붙이기 | 방향·단위·출처·N/A를 잃는다.
  Confidence: medium
  Scope-risk: narrow
  Directive: report를 갱신하면 chart generator를 다시 실행한다.
  Tested: JSON validator, node --check, xmllint, semantic/endpoint audit.
  Not-tested: PNG full-size QA는 CairoSVG 설치 여부에 따른다."
  ```

## Task 5: verifier artifact와 inline review

**Files:**

- Create: `docs/superpowers/reviews/2026-08-25-issue-313-jdbc-caffeine-pilot-plan-review.md`
- Create: `docs/superpowers/reviews/2026-08-25-issue-313-jdbc-caffeine-pilot-inline-review.md`
- Create: `docs/lessons/2026-08-25-issue-313-jdbc-caffeine-pilot.md`
- Modify: `docs/superpowers/plans/2026-08-25-issue-313-jdbc-caffeine-pilot-plan.md`

- [ ] **Step 1: Step 3-R inline plan review 작성**

  다음 필수 항목을 source path/명령으로 read-back하고 P0/P1/P2/P3로 정규화한다.

  | 영역 | 확인 내용 |
  |---|---|
  | spec coverage | production non-goal, commit/rollback/fence, dependency boundary, benchmark/chart가 Task 1~4에 모두 연결됨 |
  | ordering | dependency → RED/GREEN fixture → benchmark → chart → verifier 순서이며 뒤 task 산출물에 앞 task가 의존하지 않음 |
  | Exposed | `org.jetbrains.exposed.v1.*` imports, transaction receiver와 deprecated API 사용 여부를 검사 |
  | performance/stability | blocking/운영 wiring 없음, H2 cleanup, bounded cache, allocation 지원 실패를 N/A로 보존 |
  | docs | Korean spec/plan/summary/lesson과 chart semantic ledger가 실제 source를 가리킴 |
  | rollback | toggle OFF, dependency removal, production wiring 부재를 명시 |

  P0/P1이 있으면 계획을 수정하고 해당 review row를 다시 판정한다. 사용자 요청에
  따라 독립 subagent review는 하지 않는다.

- [ ] **Step 2: TDD/verification checklist read-back**

  다음을 fresh command로 실행하고 결과를 plan/review artifact에 기록한다.

  ```bash
  ./gradlew :appointment-core:test --tests '*EffectivePolicyCacheTest' --no-daemon
  ./gradlew :appointment-api:test --tests '*EffectiveSchedulingPolicyServiceTest' --no-daemon
  ./gradlew :appointment-api:test --tests '*JdbcCaffeineEffectivePolicyPilotTest' --no-daemon
  ./gradlew :appointment-api:build --no-daemon
  git diff --check
  ```

  실패하면 `verification-before-completion` 규칙에 따라 원인을 분리하고,
  benchmark/문서 성공으로 대체하지 않는다.

- [ ] **Step 3: six-lens inline code review**

  최종 diff를 다음 lens로 같은 session에서 검토한다.

  1. Performance: repeated allocation, lock/fence, unbounded buffer, benchmark symmetry
  2. Stability: transaction lifecycle, cleanup, rollback, H2/Testcontainers boundary
  3. Security: tenant/clinic key separation, detached serialization, secret-free report
  4. Ops: diagnostics, report provenance, feature OFF rollback, production artifact boundary
  5. Developer/API: Kotlin idioms, Exposed imports, internal test scope, KDoc/comments
  6. User/caller: misuse-resistant fixture, explicit unsupported production claims, chart clarity

  각 lens에 P0/P1/P2/P3와 `N/A` 근거를 기록하고, P0=0/P1=0이 될 때까지 수정 후
  해당 lens를 재실행한다. 최종 artifact는 변경된 파일과 line evidence를 가리킨다.

- [ ] **Step 4: lesson과 verifier DoD 작성**

  `docs/lessons/...`에 context, decision, outcome, fresh verification, surprise,
  재발 방지 guard를 기록한다. verifier 표에는 A-VER-01~07을 다음처럼 매핑한다.

  - A-VER-01: spec requirement → fixture/test/report/chart table
  - A-VER-02: plan checkbox와 commit/command evidence
  - A-VER-03: `git diff --stat`, production source untouched, dependency scope
  - A-VER-04: production public API N/A와 test-only docs mapping
  - A-VER-05: rollback/generation/fence/lifecycle/tenant risk → test names
  - A-VER-06: current HEAD, module, command, result
  - A-VER-07: production DB/multi-node/PNG gaps와 `HOLD` disposition

- [ ] **Step 5: final implementation commit**

  ```bash
  git add docs/superpowers/reviews docs/lessons docs/superpowers/plans/2026-08-25-issue-313-jdbc-caffeine-pilot-plan.md
  git commit -m "Issue #313 파일럿 검증 결과와 보류 조건을 기록한다" -m "계약 테스트, benchmark/chart, six-lens inline review와 verifier DoD를 연결한다.

  Constraint: production adoption은 production DB와 multi-node evidence 전까지 HOLD다.
  Rejected: local benchmark만으로 ADOPT 선언 | 대표성 없는 결론을 막는다.
  Confidence: high
  Scope-risk: narrow
  Directive: 다음 수정자는 report source와 chart를 함께 갱신한다.
  Tested: full module build, targeted tests, dependency boundary, chart audits.
  Not-tested: 실제 production rollout과 외부 multi-node traffic."
  ```

## Step 3-R 계획 self-review 결과

| Priority | Area | Finding | Required plan edit |
|---|---|---|---|
| P0 | none | 현재 설계의 production non-goal과 test-only dependency 경계가 모든 task에 연결됨 | 없음 |
| P1 | none | Exposed transaction, rollback/fence, benchmark stability와 chart provenance를 명령/테스트로 고정함 | 없음 |
| P2 | measurement | H2/JVM 측정은 production DB·multi-node 대표성이 없음 | summary/lesson/PR에서 `HOLD`와 후속 근거를 유지 |
| P2 | visual | CairoSVG가 없는 환경에서는 PNG full-size QA를 수행할 수 없음 | SVG/XML/semantic audit는 수행하고 PNG를 `PENDING`으로 기록 |

### Plan SPW-01~05 read-back

- **SPW-01:** 승인된 Issue #313 설계, 현재 source paths, dependency 좌표, 명시적
  production 대표성 한계를 Task 1~4에 고정했다.
- **SPW-02:** 파일 구조, bite-sized TDD 순서, 명령/expected output, rollback,
  verifier와 review 산출물을 모두 포함했다.
- **SPW-03:** 한국어 문서 용어를 `정책 기준 데이터`, `커밋 후 게시`, `세대`,
  `무효화`, `local fence`로 통일하고 코드 토큰은 보존한다.
- **SPW-04:** 각 API와 테스트/benchmark/chart가 sibling source 및 현재 저장소
  파일로 추적된다. production SLO 주장은 하지 않는다.
- **SPW-05:** placeholder 없이 전체 계획을 다시 읽었고 P0/P1 0건, P2 2건을
  명시했다. 구현 전 사용자 plan approval이 남아 있다.
