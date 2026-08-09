# Issue #253 의존성 1.4.0 전환 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `bluetape4k-dependencies:1.4.0`을 단일 ecosystem 권한으로 적용하고 Timefold 2.4.0, Redis LZ4+Fory payload, Exposed, Kafka4와 non-frontend 모듈 호환성을 검증한다.

**Architecture:** 좌표마다 하나의 버전 권한만 둔다. Timefold와 Springdoc 직접 override는 제거하고 Exposed Gradle plugin은 1.4.0으로 명시한다. 캐시는 변경 전 payload fixture의 provenance를 보존하되 Fory의 임의 버전 호환성에 의존하지 않고 remote namespace를 `-v2`로 분리한다.

**Tech Stack:** Gradle 9.6.1, Kotlin 2.4.0, Spring Boot 4.1.0, bluetape4k-dependencies 1.4.0, Timefold Solver 2.4.0, Exposed 1.4.0, Redis/Lettuce, Apache Fory, Kafka4, JUnit 5, bluetape4k assertions.

---

## 1. 파일 구조와 소유권

### 빌드 권한

- Modify: `gradle/libs.versions.toml`
  - BOM과 plugin version을 1.4.0으로 정렬하고 Timefold/Springdoc 직접 version을 제거한다.
- Modify: `build.gradle.kts`
  - 별도 Timefold BOM import를 제거한다.

### 캐시 회귀와 운영 계약

- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheFixtureIntegrityTest.kt`
  - 1.3.1 fixture provenance와 SHA-256을 영구 검증한다.
- Temporary only: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/LegacyNearCacheDecodeDiagnosticTest.kt`
  - 변경 전후 legacy decode 결과를 기록하고 commit 전에 삭제한다.
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheWireCompatibilityTest.kt`
  - production `CacheConfig` bean과 새 runtime Redis writer/reader/raw key를 검증한다.
- Create: `appointment-api/src/test/resources/cache/issue-253/*.base64`
  - 변경 전 실제 DTO 목록 payload를 보존한다.
- Create: `appointment-api/src/test/resources/cache/issue-253/fixture-provenance.properties`
  - 기준 commit, resolved Fory 좌표, codec과 파일별 SHA-256을 보존한다.
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt`
  - 논리 cache name과 v2 remote namespace를 분리한다.
- Create: `docs/runbooks/dependency-1.4.0-cache-migration.md`
  - targeted clear, deploy, rollback 순서를 고정한다.

### benchmark와 증거

- Modify: `appointment-solver/README.md`
  - 존재하지 않는 `SolverBenchmarkTest`와 잘못된 report path를 실제 class/path로 수정한다.
- Modify: `appointment-solver/README.ko.md`
  - 한국어 variant의 같은 잘못된 class selector와 report path를 함께 수정한다.
- Create: `scripts/verify-dependency-1.4.0.sh`
  - 정확한 resolved 좌표와 목표 버전을 executable contract로 검증한다.
- Create: `docs/lessons/2026-08-09-issue-253-dependencies-1.4.0.md`
  - resolved graph, cache 결정, solver 전후 score/time, module/benchmark 결과를 기록한다.

### Type A 실행 게이트와 위험 예측

모든 Kotlin task는 `$bluetape-kotlin-patterns`를 적용하고, 동작 변경은 실패하는 contract를
먼저 관찰한 뒤 최소 production diff로 GREEN을 만든다. 각 task의 Step DoD는 명시된 command와
`git diff --check`가 통과하고 해당 task의 독립 spec/code-quality 재검토에 P0/P1이 없는 것이다.

| 위험 | 조기 signal | mitigation | rollback | rerun 지점 |
|---|---|---|---|---|
| BOM 이중 권한 | exact-coordinate graph에 구/신 버전 동시 존재 | 직접 override 제거, executable graph contract | catalog/BOM commit revert | Task 2 전체 |
| Fory rolling decode | legacy decode 실패 또는 reverse compatibility 미증명 | logical name 유지 + remote v2 격리 | v1 targeted clear 후 구 binary | Task 3 cache suite |
| Redis stale key | raw v1 key 생성 또는 v2 key 미생성 | raw key assertion, SCAN/UNLINK runbook | traffic stop + v1 clear | Task 3 raw-key test |
| Exposed plugin/runtime drift | task 미등록, generated migration 실패 | plugin 1.4.0 명시, exact runtime graph | build authority commit revert | Task 2 graph + Task 5 migration |
| Timefold 품질/시간 회귀 | score 하락 또는 반복 25% 초과 | 고정 dataset/seed, 두 번 측정 | dependency commit revert | Task 4 전체 |
| singleton container 충돌 | port/launcher/flaky failure | module command 순차 실행 | 실패 module만 clean rerun | Task 5 해당 module |

## 2. Task 1 — 변경 전 wire fixture와 기준선 고정

**Complexity:** high. **Pattern:** `$bluetape-kotlin-patterns`. **RED/GREEN:** 1.4.0 graph
contract는 RED로 유지하고 1.3.1 fixture round-trip은 GREEN으로 고정한다. **Task DoD:** 기준 graph,
benchmark task 존재, fixture provenance/SHA-256과 baseline decode가 모두 증명된다.

- [ ] **Step 0: 변경 전 resolved graph와 task 존재를 기록한다**

Run the exact-coordinate `dependencyInsight` commands from Task 2 Step 4 before any catalog edit,
and record the selected versions in the working evidence. Then run:

```bash
./gradlew :appointment-messaging-benchmark:tasks --all --no-daemon --console=plain \
  | rg 'mainSmokeBenchmark'
./gradlew :appointment-core:tasks :appointment-event:tasks --all --no-daemon --console=plain \
  | rg 'generateMigrations'
```

Expected: baseline versions are Timefold 2.2.0, Springdoc 3.0.3, Exposed runtime 1.3.0,
Fory core 1.1.0/Kotlin 1.3.0, Leader 0.4.0, Kafka clients 4.2.1. The benchmark task and both
Exposed `generateMigrations` tasks exist.

- [ ] **Step 1: legacy fixture 생성용 임시 테스트를 작성한다**

Create temporarily:
`appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/LegacyNearCacheFixtureGeneratorTest.kt`

```kotlin
package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.redis.lettuce.codec.LettuceBinaryCodecs
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.Base64

class LegacyNearCacheFixtureGeneratorTest {
    @Test
    fun `1_3_1 LZ4 Fory fixture를 출력한다`() {
        printFixture("doctors", listOf(DoctorRecord(11L, 7L, "김의사", "내과", "DOCTOR", 2)))
        printFixture("equipments", listOf(EquipmentRecord(21L, 7L, "MRI", 30, 1)))
        printFixture(
            "treatment-types",
            listOf(TreatmentTypeRecord(31L, 7L, "일반 진료", defaultDurationMinutes = 30)),
        )
    }

    private fun printFixture(name: String, value: Any) {
        val encoded: ByteBuffer = LettuceBinaryCodecs.default<Any>().encodeValue(value)
        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
        println("ISSUE253_FIXTURE[$name]=${Base64.getEncoder().encodeToString(bytes)}")
    }
}
```

- [ ] **Step 2: baseline runtime에서 fixture를 생성한다**

Run:

```bash
./gradlew :appointment-api:test --no-daemon --console=plain \
  --tests '*LegacyNearCacheFixtureGeneratorTest'
```

Expected: `BUILD SUCCESSFUL`이고 세 `ISSUE253_FIXTURE[...]` 값이 test XML의
`system-out`에 기록된다.

- [ ] **Step 3: base64 fixture, provenance와 영구 integrity test를 작성한다**

각 출력값을 다음 resource에 한 줄로 저장한다.

```text
appointment-api/src/test/resources/cache/issue-253/doctors-1.3.1.base64
appointment-api/src/test/resources/cache/issue-253/equipments-1.3.1.base64
appointment-api/src/test/resources/cache/issue-253/treatment-types-1.3.1.base64
```

같은 directory의 `fixture-provenance.properties`에는 아래 key를 실제 값으로 저장한다.

```properties
base.commit=e790793a2e8eccf4269eba97f3faad084b7c568d
bluetape4k.dependencies=1.3.1
fory.core=1.1.0
fory.kotlin=1.3.0
codec=io.bluetape4k.redis.lettuce.codec.LettuceBinaryCodecs.default
doctors.sha256=<actual sha256>
equipments.sha256=<actual sha256>
treatment-types.sha256=<actual sha256>
```

`shasum -a 256 appointment-api/src/test/resources/cache/issue-253/*.base64`로 실제 hash를
구하고 integrity test에서도 provenance의 hash와 resource bytes가 일치하는지 검증한다.

Create:
`appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheFixtureIntegrityTest.kt`

```kotlin
package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Properties

class NearCacheFixtureIntegrityTest {
    @Test
    fun `1_3_1 fixture provenance와 SHA_256이 일치한다`() {
        val provenance = Properties().apply {
            checkNotNull(
                NearCacheFixtureIntegrityTest::class.java
                    .getResourceAsStream("/cache/issue-253/fixture-provenance.properties")
            )
                .use { load(it) }
        }
        provenance.getProperty("base.commit") shouldBeEqualTo
            "e790793a2e8eccf4269eba97f3faad084b7c568d"
        provenance.getProperty("bluetape4k.dependencies") shouldBeEqualTo "1.3.1"
        provenance.getProperty("fory.core") shouldBeEqualTo "1.1.0"
        provenance.getProperty("fory.kotlin") shouldBeEqualTo "1.3.0"
        provenance.getProperty("codec") shouldBeEqualTo
            "io.bluetape4k.redis.lettuce.codec.LettuceBinaryCodecs.default"

        mapOf(
            "doctors" to "doctors-1.3.1.base64",
            "equipments" to "equipments-1.3.1.base64",
            "treatment-types" to "treatment-types-1.3.1.base64",
        ).forEach { (family, resourceName) ->
            val bytes = checkNotNull(
                NearCacheFixtureIntegrityTest::class.java.getResourceAsStream(
                    "/cache/issue-253/$resourceName"
                )
            )
                .use { it.readAllBytes() }
            sha256(bytes) shouldBeEqualTo provenance.getProperty("$family.sha256")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}
```

- [ ] **Step 4: 임시 decode diagnostic으로 baseline을 확인한 뒤 임시 test를 삭제한다**

Create temporarily `LegacyNearCacheDecodeDiagnosticTest.kt` using the three DTO equality assertions
and the nullable-safe decoder below:

```kotlin
private inline fun <reified T> decode(name: String): T {
    val encoded = checkNotNull(javaClass.getResource("/cache/issue-253/$name")).readText().trim()
    return checkNotNull(
        LettuceBinaryCodecs.default<T>().decodeValue(ByteBuffer.wrap(Base64.getDecoder().decode(encoded)))
    )
}
```

Run:

```bash
./gradlew :appointment-api:test --no-daemon --console=plain \
  --tests '*NearCacheFixtureIntegrityTest' \
  --tests '*LegacyNearCacheDecodeDiagnosticTest'
```

Expected: integrity와 payload decode 3건 passing. 이 성공은 fixture가 현재 1.3.1 runtime에서
생성·복원됨을 증명한다. 그 뒤 generator와 diagnostic test를 모두 `apply_patch`로 삭제한다.

- [ ] **Step 5: baseline fixture를 커밋한다**

```bash
git add appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheFixtureIntegrityTest.kt \
  appointment-api/src/test/resources/cache/issue-253
git commit
```

Commit intent: `변경 전 캐시 wire payload를 회귀 기준으로 고정한다`와 Lore trailer를 사용한다.

## 3. Task 2 — BOM 권한 정리와 RED 확인

**Complexity:** high. **Pattern:** `$bluetape-kotlin-patterns` + Gradle catalog. **RED/GREEN:**
목표 version contract를 catalog 변경 전에 실패시키고 변경 뒤 통과시킨다. **Task DoD:** exact
좌표가 목표 버전 하나로 해석되고 구 Timefold/Springdoc authority가 사라진다.

- [ ] **Step 0: executable resolved-graph contract를 먼저 작성해 RED를 확인한다**

Create `scripts/verify-dependency-1.4.0.sh`. It must run the exact-coordinate commands from
Step 4, capture each result in a temporary directory created by `mktemp -d`, and fail unless these
coordinates resolve only to the target selected version: Timefold 2.4.0, Springdoc 3.1.0, JetBrains Exposed
1.4.0, Fory core/Kotlin 1.5.0, Leader Redis Lettuce 0.5.0, Kafka clients 4.2.1. It must also fail
when `2.2.0`, `3.0.3`, or JetBrains Exposed `1.3.0` remains, and remove the temporary directory by
`trap`.

Run before catalog modification:

```bash
bash scripts/verify-dependency-1.4.0.sh
```

Expected: non-zero RED caused by the known baseline versions. Record the failed assertion; do not
weaken the expected versions.

- [ ] **Step 1: version catalog를 최소 수정한다**

Modify `gradle/libs.versions.toml`:

```toml
[versions]
bluetape4k-dependencies = "1.4.0"
exposed = "1.4.0"

# springdoc-openapi와 timefold-solver version key는 삭제한다.

[libraries]
springdoc-openapi-starter-webmvc-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui" }

# timefold-solver-bom alias는 삭제한다.
timefold-solver-core = { module = "ai.timefold.solver:timefold-solver-core" }
timefold-solver-benchmark = { module = "ai.timefold.solver:timefold-solver-benchmark" }
```

Kotlin, Coroutines와 project-local version은 변경하지 않는다.

- [ ] **Step 2: root의 별도 Timefold BOM import를 제거한다**

Modify `build.gradle.kts`의 `dependencyManagement.imports`:

```kotlin
imports {
    mavenBom(rootLibs.bluetape4k.dependencies.get().toString())
    mavenBom(rootLibs.spring.boot4.dependencies.get().toString())
    mavenBom(rootLibs.kotlin.bom.get().toString())
    mavenBom(rootLibs.kotlinx.coroutines.bom.get().toString())
}
```

- [ ] **Step 3: 변경 후 legacy fixture diagnostic을 먼저 실행한다**

Task 1의 `LegacyNearCacheDecodeDiagnosticTest.kt`를 같은 내용으로 임시 재생성한다.

Run:

```bash
./gradlew :appointment-api:test --no-daemon --console=plain \
  --refresh-dependencies \
  --tests '*NearCacheFixtureIntegrityTest' \
  --tests '*LegacyNearCacheDecodeDiagnosticTest'
```

Expected decision: 성공/실패를 lesson에 기록한다. 어느 결과든 reverse compatibility를
가정하지 않고 Task 3의 v2 remote namespace를 적용한다. fixture integrity test는 결과와
무관하게 그대로 유지하고, diagnostic test는 결과를 기록한 뒤 `apply_patch`로 다시 삭제한다.

- [ ] **Step 4: resolved graph를 확인한다**

Run sequentially:

```bash
./gradlew :appointment-solver:dependencyInsight \
  --dependency 'ai.timefold.solver:timefold-solver-core' \
  --configuration runtimeClasspath --refresh-dependencies --no-daemon --console=plain
./gradlew :appointment-api:dependencyInsight --dependency 'org.apache.fory:fory-core' \
  --configuration runtimeClasspath --no-daemon --console=plain
./gradlew :appointment-api:dependencyInsight --dependency 'org.apache.fory:fory-kotlin' \
  --configuration runtimeClasspath --no-daemon --console=plain
./gradlew :appointment-core:dependencyInsight --dependency 'org.jetbrains.exposed:exposed-core' \
  --configuration runtimeClasspath --no-daemon --console=plain
./gradlew :appointment-api:dependencyInsight \
  --dependency 'org.springdoc:springdoc-openapi-starter-webmvc-ui' \
  --configuration runtimeClasspath --no-daemon --console=plain
./gradlew :appointment-messaging:dependencyInsight \
  --dependency 'org.apache.kafka:kafka-clients' \
  --configuration runtimeClasspath --no-daemon --console=plain
./gradlew :appointment-notification:dependencyInsight \
  --dependency 'io.github.bluetape4k:bluetape4k-leader-redis-lettuce' \
  --configuration runtimeClasspath --no-daemon --console=plain
bash scripts/verify-dependency-1.4.0.sh
```

Expected: Timefold 2.4.0, Exposed 1.4.0, Springdoc 3.1.0, Fory 1.5.0,
Leader 0.5.0과 Kafka clients 4.2.1. Timefold 2.2.0, Springdoc 3.0.3와 JetBrains Exposed
1.3.0은 없어야 한다. `gradle/libs.versions.toml`의 Exposed plugin도 1.4.0이어야 한다.

- [ ] **Step 5: build 권한 변경을 커밋한다**

```bash
git add gradle/libs.versions.toml build.gradle.kts scripts/verify-dependency-1.4.0.sh
git commit
```

Commit intent: `공유 좌표의 버전 권한을 1.4.0 BOM으로 단일화한다`와 Lore trailer를 사용한다.

## 4. Task 3 — Redis wire 결정과 GREEN

**Complexity:** high. **Pattern:** `$bluetape-kotlin-patterns`, singleton container. **RED/GREEN:**
v2 remote-name/raw-key contract를 먼저 실패시키고 `CacheConfig`만 최소 수정해 통과시킨다.
**Task DoD:** logical cache name은 유지되고 v2 raw keys만 생성되며 독립 cache/client round-trip과
rollback runbook이 검증된다.

- [ ] **Step 1: v2 remote-name contract를 먼저 추가해 RED를 확인한다**

Create `NearCacheWireCompatibilityTest` with a singleton Redis container from
`appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/Containers.kt`.
production constant가 아직 없는 상태에서 test가 `clinic-doctors-v2`, `clinic-equipments-v2`,
`clinic-treatment-types-v2`를 요구하도록 작성하고 targeted test의 compile/assertion RED를
관찰한다. hard-coded test-only `LettuceCaches.nearCache`를 만들지 말고 실제 `CacheConfig` bean
factory를 서로 다른 `RedisClient`로 두 번 호출해 production wiring을 검증한다.

```kotlin
val config = CacheConfig()
val writer = config.clinicDoctorsCache(writerClient)
val reader = config.clinicDoctorsCache(readerClient)

writer.put("1:7", expectedDoctors)
reader.get("1:7") shouldBeEqualTo expectedDoctors
rawCommands.exists("${CacheConfig.DOCTORS_REMOTE_CACHE_NAME}:1:7") shouldBeEqualTo 1L
rawCommands.exists("${CacheConfig.DOCTORS_CACHE_NAME}:1:7") shouldBeEqualTo 0L
```

equipment/treatment는 각각 `clinicEquipmentsCache`, `clinicTreatmentTypesCache` production
factory로 같은 검증을 반복한다. 각 family를 독립적으로 검증하고 raw Redis connection으로
`$remoteName:1:7`이 존재하며 `$logicalName:1:7`이 존재하지 않는지 확인한다. test 전후에는
두 exact key만 삭제한다. 각 cache의 `close()`와 각 `RedisClient.shutdown()`은 서로 독립된
`runCatching`으로 실행하되 모든 exception을 수집해 마지막에 하나의 `AssertionError`에
suppressed exception으로 추가하고 test를 실패시킨다. 첫 close 실패가 나머지 정리를 막거나
cleanup 실패가 숨겨져서는 안 된다.
`@Testcontainers`는 사용하지 않고 기존 singleton launcher만 사용한다.

```kotlin
val cleanupFailures = cleanupActions.mapNotNull { action ->
    runCatching(action).exceptionOrNull()
}
if (cleanupFailures.isNotEmpty()) {
    throw AssertionError("Redis cache test cleanup failed").also { failure ->
        cleanupFailures.forEach(failure::addSuppressed)
    }
}
```

- [ ] **Step 2: CacheConfig를 v2 remote namespace로 분리해 GREEN으로 만든다**

Modify `CacheConfig.kt`:

```kotlin
companion object : KLogging() {
    internal const val DOCTORS_CACHE_NAME = "clinic-doctors"
    internal const val EQUIPMENTS_CACHE_NAME = "clinic-equipments"
    internal const val TREATMENT_TYPES_CACHE_NAME = "clinic-treatment-types"

    internal const val DOCTORS_REMOTE_CACHE_NAME = "clinic-doctors-v2"
    internal const val EQUIPMENTS_REMOTE_CACHE_NAME = "clinic-equipments-v2"
    internal const val TREATMENT_TYPES_REMOTE_CACHE_NAME = "clinic-treatment-types-v2"
}
```

Use `*_REMOTE_CACHE_NAME` only for `LettuceCaches.nearCache { cacheName = ... }`. Keep
`NearCacheAdapter` names and all `@Cacheable` names on the logical constants.

이 분리는 mixed-version rolling deployment에서 구 binary가 v1만, 새 binary가 v2만 사용하게
하므로 새 payload를 구 decoder로 읽는 경로를 제거한다. legacy decode diagnostic의 성공 여부는
namespace 유지 조건으로 사용하지 않는다.

- [ ] **Step 3: targeted cache tests를 GREEN으로 만든다**

Run:

```bash
./gradlew :appointment-api:test --no-daemon --console=plain \
  --tests '*NearCacheWireCompatibilityTest' \
  --tests '*CacheIntegrationTest' \
  --tests '*NearCacheAdapterTest'
```

Expected: all passing. `CacheIntegrationTest`는 logical cache name을 계속 사용하고, raw Redis에는
세 v2 key만 존재하며 같은 logical key의 v1 key는 없어야 한다.

- [ ] **Step 4: 운영 runbook을 작성한다**

Create `docs/runbooks/dependency-1.4.0-cache-migration.md` with this exact order:

1. 각 Redis primary shard에서 cursor 기반 `SCAN MATCH 'clinic-doctors-v2:*' COUNT 500`을
   equipment/treatment prefix에도 반복하고, 반환된 exact key만 batch `UNLINK`한다. `KEYS`와
   glob을 직접 넘긴 `DEL`은 사용하지 않는다.
2. 새 binary를 canary 배포하고 application serialization/decode error log, v2 prefix SCAN count,
   Redis `INFO stats`의 keyspace hit/miss delta를 배포 전 snapshot과 비교한다.
3. rollback이면 트래픽을 중단하고 같은 per-primary `SCAN` + exact-key `UNLINK` 절차로
   `clinic-doctors:*`, `clinic-equipments:*`, `clinic-treatment-types:*` v1 key를 비운 뒤 구
   binary를 배포한다.
4. rollout 성공 뒤 1시간 TTL과 관찰 window가 지난 후 v1 key를 삭제한다.
5. `FLUSHALL`, tenant 동적 suffix, schema-down은 사용하지 않는다.

- [ ] **Step 5: cache migration을 커밋한다**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheWireCompatibilityTest.kt \
  docs/runbooks/dependency-1.4.0-cache-migration.md
git commit
```

## 5. Task 4 — Timefold 2.4.0 품질과 성능

**Complexity:** medium. **Pattern:** `$bluetape-kotlin-patterns`. **RED/GREEN:** 기존 score/time
gate를 그대로 유지하고 dependency upgrade 후 같은 dataset으로 GREEN을 재확인한다. **Task DoD:**
전체 solver test, 두 번의 benchmark와 두 README selector가 통과한다.

- [ ] **Step 1: solver 전체 테스트로 2.4.0 validation을 실행한다**

Run:

```bash
./gradlew :appointment-solver:test --no-daemon --console=plain
```

Expected: 68 tests passing. 새 fail-fast 오류가 나오면 임의 수정하지 않고
`AppointmentPlanning.kt`, solver config와 해당 failing fixture를 근거로 계획 검토 단계로 돌아간다.
validation disable 또는 범위 밖 model 변경은 재승인 전 수행하지 않는다.

- [ ] **Step 2: 실제 benchmark class를 두 번 반복한다**

Run twice:

```bash
./gradlew :appointment-solver:test --rerun-tasks --no-daemon --console=plain \
  --tests '*solver.benchmark.BenchmarkTest'
```

각 실행의 test XML에서 소·중·대 score와 시간을 기록한다. 절대 gate는 기존 test의
`15s/20s/40s`, `0/-500/-2000 soft` 하한이다. warm-up 이후 시간이 변경 전
`5,027/8,075/15,922ms`보다 반복해서 25% 이상 나빠지면 원인을 분석하고 merge-ready로
표시하지 않는다.

- [ ] **Step 3: 두 README의 잘못된 benchmark class를 수정한다**

Modify `appointment-solver/README.md` and `appointment-solver/README.ko.md`:

```bash
./gradlew :appointment-solver:test --tests "*solver.benchmark.BenchmarkTest"
```

두 문서의 결과 path도 `BenchmarkConfig.BENCHMARK_DIR`과 일치하는 `local/benchmark/`로
수정한다.

- [ ] **Step 4: solver 검증을 커밋한다**

```bash
git add appointment-solver/README.md appointment-solver/README.ko.md
git commit
```

README가 이미 정확하면 문서 커밋은 생략하고 lesson에 검증 결과만 기록한다.

## 6. Task 5 — non-frontend 모듈과 benchmark 검증

**Complexity:** high. **Pattern:** `$bluetape-kotlin-patterns`, singleton testcontainer. **RED/GREEN:**
각 module과 migration/benchmark task를 독립 command로 실행해 실패 범위를 좁힌다. **Task DoD:**
frontend를 제외한 모든 module build/test, Exposed migrations, Kafka target, benchmark smoke/validator가
통과한다.

- [ ] **Step 1: 정적 build를 실행한다**

```bash
./gradlew :appointment-core:build :appointment-event:build :appointment-solver:build \
  :appointment-notification:build :appointment-messaging:build :appointment-api:build \
  :appointment-messaging-benchmark:build -x test \
  --refresh-dependencies --no-daemon --console=plain
./gradlew build -x test -x :frontend:appointment-frontend:build \
  --no-daemon --console=plain
./gradlew detekt --parallel --no-daemon --console=plain
```

Expected: explicit module build, frontend-excluded root build와 detekt 모두 successful.

- [ ] **Step 2: singleton container 충돌을 피하도록 module tests를 순차 실행한다**

```bash
./gradlew :appointment-core:test --no-daemon --console=plain
./gradlew :appointment-event:test --no-daemon --console=plain
./gradlew :appointment-solver:test --no-daemon --console=plain
./gradlew :appointment-notification:test --no-daemon --console=plain
./gradlew :appointment-messaging:test --no-daemon --console=plain
./gradlew :appointment-api:test --no-daemon --console=plain -Dspring.profiles.active=test
./gradlew :appointment-api:test --no-daemon --console=plain -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --no-daemon --console=plain -Dspring.profiles.active=test,test-mysql
./gradlew :appointment-messaging-benchmark:test --no-daemon --console=plain
```

- [ ] **Step 3: Exposed plugin migration task를 실제 실행한다**

```bash
./gradlew :appointment-core:generateMigrations --no-daemon --console=plain
./gradlew :appointment-event:generateMigrations --no-daemon --console=plain
git status --short
```

Expected: both tasks registered and successful. Generated output이 tracked source를 예상 밖으로
변경하면 commit하지 말고 plugin/runtime regression으로 분류해 Task 2 계획 검토로 돌아간다.

- [ ] **Step 4: Kafka4 targeted integration을 확인한다**

```bash
./gradlew :appointment-messaging:test --no-daemon --console=plain \
  --tests '*AppointmentMessagingKafkaIntegrationTest' \
  --tests '*AppointmentKafkaConsumerIntegrationTest'
```

Expected: readiness, broker ack, duplicate redelivery와 rebalance recovery tests passing.

- [ ] **Step 5: PostgreSQL messaging benchmark smoke와 validator를 실행한다**

```bash
./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark --no-daemon --console=plain
node --test tests/benchmarks/appointment-messaging-benchmark-scripts.test.mjs
node scripts/collect-appointment-messaging-benchmark.mjs \
  --input-dir benchmark/appointment-messaging-benchmark/build/reports/benchmarks \
  --config smoke \
  --output build/reports/appointment-messaging-postgresql/benchmark.json
node scripts/validate-appointment-messaging-benchmark.mjs \
  --input build/reports/appointment-messaging-postgresql/benchmark.json
```

Expected: smoke task and JSON validator successful. benchmark API 역의존은 수정하지 않는다.

- [ ] **Step 6: security evidence를 확인한다**

```bash
gh api repos/bluetape4k/clinic-appointment/dependabot/alerts \
  --paginate --jq '[.[] | select(.state == "open") | {number, manifest: .dependency.manifest_path, dependency: .dependency.package.name, severity: .security_advisory.severity}]'
```

Open alert가 있으면 #253 diff와 관련된 좌표인지 분리한다. 권한이 없어 조회하지 못하면
그 사실을 lesson과 PR의 unchecked 항목에 기록한다. 2026-08-09 live baseline의 open 4건은
모두 `frontend/appointment-frontend/package-lock.json` development scope이므로 JVM 무관 alert로
기록하되 “전체 취약점 없음”이라고 표현하지 않는다.

## 7. Task 6 — evidence, review, commit

**Complexity:** high. **Pattern:** `$bluetape-kotlin-patterns` + 7-tier review. **RED/GREEN:** 각
독립 관점의 P0/P1을 RED backlog로 수집하고 수정 후 같은 관점 재검토로 GREEN을 증명한다.
**Task DoD:** lesson과 통합 review artifact가 exact diff를 반영하고 모든 관점 P0=0/P1=0이다.

- [ ] **Step 1: lesson을 작성한다**

Create `docs/lessons/2026-08-09-issue-253-dependencies-1.4.0.md` with:

- pre/post resolved version table
- legacy fixture decode 결과와 namespace 결정
- deploy/rollback targeted clear 순서
- Timefold 두 실행의 score/time table
- module/static/Kafka/benchmark command와 결과
- production에서 실행하지 않은 항목

- [ ] **Step 2: 계획과 실제 diff를 대조한다**

```bash
git status --short
git diff --stat origin/develop...HEAD
git diff --check origin/develop...HEAD
rg -n 'timefold-solver\s*=\s*"2\.2\.0"|timefold-solver-bom|springdoc-openapi\s*=\s*"3\.0\.3"' \
  gradle/libs.versions.toml build.gradle.kts
```

Expected: forbidden version/alias matches absent and diff check clean.

- [ ] **Step 3: 여섯 독립 관점과 main integration review를 실행한다**

Fresh read-only reviewers report JSON evidence under `.bluetape/tmp/issue-253-review/`:

1. `architect-reviewer` — BOM authority, cache boundary, #249/#250/#254 separation.
2. `code-reviewer` — full 7-tier diff review and `$bluetape-kotlin-patterns`.
3. `security-reviewer` — Fory input boundary, Dependabot scope, Redis operational safety.
4. `performance-reviewer` — Timefold repeated score/time and benchmark evidence.
5. `sre-reviewer` — rolling deploy, targeted clear, rollback and observability.
6. `library-user-reviewer` — public API/logical cache names/README commands.

The leader integrates them into
`docs/reviews/2026-08-09-issue-253-dependencies-1.4.0-implementation-review.md` and independently
checks the integration/test lens. Any fix invalidates the affected reviewer result; rerun that fresh
reviewer and main integration before PR creation.

Review lenses:

1. 성능: Timefold score/time과 messaging smoke.
2. 안정성: module tests, fail-fast validation, Redis cross-instance.
3. 보안: advisory state와 serialization input boundary.
4. 운영: namespace, targeted clear, rollback stale 방지.
5. 개발자/API: 좌표별 단일 권한, 불필요한 override 없음.
6. 사용자/호출자: API cache 논리 이름과 solver 결과 회귀 없음.
7. 통합/테스트: Exposed, Kafka4, Springdoc, dialect matrix.

Kotlin pattern review는 `checkNotNull`, immutable fixture, singleton container 규칙,
resource close와 예외 경계를 확인한다. `P0=0`, `P1=0`이 아니면 PR을 만들지 않는다.

- [ ] **Step 4: lesson과 최종 수정사항을 커밋한다**

```bash
git add docs/lessons/2026-08-09-issue-253-dependencies-1.4.0.md \
  docs/reviews/2026-08-09-issue-253-dependencies-1.4.0-implementation-review.md
git commit
```

Commit intent: `의존성 전환의 resolved graph와 복구 증거를 남긴다`와 Lore trailer를 사용한다.

## 8. Task 7 — push, PR, CI readiness

**Complexity:** medium. **Pattern:** workflow delivery gate. **RED/GREEN:** live remote head, required
checks, review decision와 unresolved thread count를 각각 검증한다. **Task DoD:** exact local/remote/PR
head가 같고 required CI GREEN, unresolved thread 0이며 merge는 수행하지 않는다.

- [ ] **Step 1: fresh local verification을 요약한다**

```bash
git status --short --branch
git log --oneline origin/develop..HEAD
git diff --check origin/develop...HEAD
```

- [ ] **Step 2: 승인된 head branch를 push한다**

```bash
git push -u origin codex/issue-253-dependencies-1.4.0
```

- [ ] **Step 3: 한국어 PR을 `develop` base로 생성한다**

PR metadata:

- repository: `bluetape4k/clinic-appointment`
- base: `develop`
- head: `codex/issue-253-dependencies-1.4.0`
- assignee: `debop`
- labels: `dependencies`, `test`, `build`
- milestone: none
- body: Issue #253 연결, resolved graph·cache·benchmark evidence, production unchecked 항목
- body closing keyword: `Closes #253`
- final section: `## DoD Status`

- [ ] **Step 4: live PR body와 CI를 재확인한다**

```bash
git rev-parse HEAD
git ls-remote origin refs/heads/codex/issue-253-dependencies-1.4.0
gh pr view --json number,url,headRefOid,body,assignees,labels,milestone,mergeStateStatus,reviewDecision,statusCheckRollup
gh pr checks --required
issue253_pr_number="$(gh pr view --json number --jq .number)"
gh api graphql --paginate -F owner=bluetape4k -F name=clinic-appointment \
  -F number="$issue253_pr_number" \
  -f query='query($owner:String!,$name:String!,$number:Int!,$endCursor:String){repository(owner:$owner,name:$name){pullRequest(number:$number){reviewThreads(first:100,after:$endCursor){nodes{isResolved}pageInfo{hasNextPage endCursor}}}}}' \
  --jq '[.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved == false)] | length' \
  | awk '{ total += $1 } END { print total + 0 }'
```

Expected: local HEAD, remote branch SHA와 `headRefOid`가 동일하고 required checks successful,
unresolved thread count 0, blocking `reviewDecision` 없음. exact `headRefOid`와 merge-ready 근거를
사용자에게 보고하고 멈춘다. merge, auto-merge, branch 삭제는 수행하지 않는다.

## 9. 계획 자체 검토

- 설계의 버전 권한, Redis wire/rollback, solver 품질, module/benchmark, review/CI 기준이
  각각 Task 2~7에 연결된다.
- Timefold 공개 API 노출이나 benchmark module 역의존은 #253에서 변경하지 않는다.
- legacy fixture는 구현 변경 전에 생성·검증되고 BOM 변경 뒤 RED/GREEN 결정 근거가 된다.
- cache는 logical name을 유지하고 v2 remote namespace로 격리되며 rollback 조건이 명시되어 있다.
- 임시 generator는 fixture 생성 뒤 삭제되며 repository에 남지 않는다.
- 실제 production Redis clear와 배포는 승인 범위가 아니므로 runbook만 만들고 실행하지 않는다.
