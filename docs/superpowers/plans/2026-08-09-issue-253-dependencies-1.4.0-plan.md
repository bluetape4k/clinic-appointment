# Issue #253 의존성 1.4.0 전환 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `bluetape4k-dependencies:1.4.0`을 단일 ecosystem 권한으로 적용하고 Timefold 2.4.0, Redis LZ4+Fory payload, Exposed, Kafka4와 non-frontend 모듈 호환성을 검증한다.

**Architecture:** 좌표마다 하나의 버전 권한만 둔다. Timefold와 Springdoc 직접 override는 제거하고 Exposed Gradle plugin은 1.4.0으로 명시한다. 캐시는 변경 전 payload fixture와 실제 Redis writer/reader로 wire 경계를 검증하고, legacy decode가 깨질 때만 remote namespace를 `-v2`로 분리한다.

**Tech Stack:** Gradle 9.6.1, Kotlin 2.4.0, Spring Boot 4.1.0, bluetape4k-dependencies 1.4.0, Timefold Solver 2.4.0, Exposed 1.4.0, Redis/Lettuce, Apache Fory, Kafka4, JUnit 5, Kluent.

---

## 1. 파일 구조와 소유권

### 빌드 권한

- Modify: `gradle/libs.versions.toml`
  - BOM과 plugin version을 1.4.0으로 정렬하고 Timefold/Springdoc 직접 version을 제거한다.
- Modify: `build.gradle.kts`
  - 별도 Timefold BOM import를 제거한다.

### 캐시 회귀와 운영 계약

- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheWireCompatibilityTest.kt`
  - 1.3.1 legacy LZ4+Fory fixture decode와 새 runtime Redis writer/reader를 검증한다.
- Create: `appointment-api/src/test/resources/cache/issue-253/*.base64`
  - 변경 전 실제 DTO 목록 payload를 보존한다.
- Modify when legacy decode fails: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt`
  - 논리 cache name과 v2 remote namespace를 분리한다.
- Create when namespace changes: `docs/runbooks/dependency-1.4.0-cache-migration.md`
  - targeted clear, deploy, rollback 순서를 고정한다.

### benchmark와 증거

- Modify: `appointment-solver/README.md`
  - 존재하지 않는 `SolverBenchmarkTest` 명령을 실제 `BenchmarkTest`로 수정한다.
- Create: `docs/lessons/2026-08-09-issue-253-dependencies-1.4.0.md`
  - resolved graph, cache 결정, solver 전후 score/time, module/benchmark 결과를 기록한다.

## 2. Task 1 — 변경 전 wire fixture와 기준선 고정

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

- [ ] **Step 3: base64 fixture와 영구 compatibility test를 작성한다**

각 출력값을 다음 resource에 한 줄로 저장한다.

```text
appointment-api/src/test/resources/cache/issue-253/doctors-1.3.1.base64
appointment-api/src/test/resources/cache/issue-253/equipments-1.3.1.base64
appointment-api/src/test/resources/cache/issue-253/treatment-types-1.3.1.base64
```

Create:
`appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheWireCompatibilityTest.kt`

```kotlin
package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.redis.lettuce.codec.LettuceBinaryCodecs
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.Base64

class NearCacheWireCompatibilityTest {
    @Test
    fun `1_3_1 doctor payload를 읽는다`() {
        decode<List<DoctorRecord>>("doctors-1.3.1.base64") shouldBeEqualTo
            listOf(DoctorRecord(11L, 7L, "김의사", "내과", "DOCTOR", 2))
    }

    @Test
    fun `1_3_1 equipment payload를 읽는다`() {
        decode<List<EquipmentRecord>>("equipments-1.3.1.base64") shouldBeEqualTo
            listOf(EquipmentRecord(21L, 7L, "MRI", 30, 1))
    }

    @Test
    fun `1_3_1 treatment payload를 읽는다`() {
        decode<List<TreatmentTypeRecord>>("treatment-types-1.3.1.base64") shouldBeEqualTo
            listOf(TreatmentTypeRecord(31L, 7L, "일반 진료", defaultDurationMinutes = 30))
    }

    private inline fun <reified T> decode(name: String): T {
        val encoded = checkNotNull(javaClass.getResource("/cache/issue-253/$name"))
            .readText()
            .trim()
        val bytes = Base64.getDecoder().decode(encoded)
        return LettuceBinaryCodecs.default<T>().decodeValue(ByteBuffer.wrap(bytes))
    }
}
```

- [ ] **Step 4: 임시 generator를 삭제하고 baseline fixture test를 통과시킨다**

Run:

```bash
./gradlew :appointment-api:test --no-daemon --console=plain \
  --tests '*NearCacheWireCompatibilityTest'
```

Expected: 3 tests passing. 이 성공은 fixture가 현재 1.3.1 runtime에서 생성·복원됨을
증명한다.

- [ ] **Step 5: baseline fixture를 커밋한다**

```bash
git add appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheWireCompatibilityTest.kt \
  appointment-api/src/test/resources/cache/issue-253
git commit
```

Commit intent: `변경 전 캐시 wire payload를 회귀 기준으로 고정한다`와 Lore trailer를 사용한다.

## 3. Task 2 — BOM 권한 정리와 RED 확인

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

- [ ] **Step 3: 변경 후 legacy fixture test를 먼저 실행한다**

Run:

```bash
./gradlew :appointment-api:test --no-daemon --console=plain \
  --refresh-dependencies \
  --tests '*NearCacheWireCompatibilityTest'
```

Expected decision:

- 3 tests passing: 기존 namespace 유지 후보. Task 3에서 실제 Redis cross-instance test까지
  통과해야 최종 유지한다.
- 하나 이상 failing: Fory/LZ4 wire 비호환 RED 증거. Task 3의 v2 remote namespace를 적용한다.

- [ ] **Step 4: resolved graph를 확인한다**

Run sequentially:

```bash
./gradlew :appointment-solver:dependencyInsight --dependency timefold-solver-core \
  --configuration runtimeClasspath --refresh-dependencies --no-daemon --console=plain
./gradlew :appointment-api:dependencyInsight --dependency fory-kotlin \
  --configuration runtimeClasspath --no-daemon --console=plain
./gradlew :appointment-api:dependencyInsight --dependency exposed-core \
  --configuration runtimeClasspath --no-daemon --console=plain
./gradlew :appointment-api:dependencyInsight --dependency springdoc-openapi-starter-webmvc-ui \
  --configuration runtimeClasspath --no-daemon --console=plain
./gradlew :appointment-messaging:dependencyInsight --dependency kafka-clients \
  --configuration runtimeClasspath --no-daemon --console=plain
./gradlew :appointment-notification:dependencyInsight \
  --dependency bluetape4k-leader-redis-lettuce \
  --configuration runtimeClasspath --no-daemon --console=plain
```

Expected: Timefold 2.4.0, Exposed 1.4.0, Springdoc 3.1.0, Fory 1.5.0,
Leader 0.5.0과 BOM이 선택한 단일 Kafka version. Timefold 2.2.0은 없어야 한다.

- [ ] **Step 5: build 권한 변경을 커밋한다**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit
```

Commit intent: `공유 좌표의 버전 권한을 1.4.0 BOM으로 단일화한다`와 Lore trailer를 사용한다.

## 4. Task 3 — Redis wire 결정과 GREEN

- [ ] **Step 1: 실제 Redis cross-instance 회귀 테스트를 추가한다**

Extend `NearCacheWireCompatibilityTest` with a singleton Redis container from
`appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/Containers.kt`.
동일 cache name을 가진 writer와 reader를 서로 다른 `RedisClient`와 NearCache instance로
만들고 다음 순서를 검증한다.

```kotlin
val writer = LettuceCaches.nearCache<List<DoctorRecord>>(writerClient) {
    cacheName = remoteName
    maxLocalSize = 10
    redisTtl = Duration.ofMinutes(5)
}
val reader = LettuceCaches.nearCache<List<DoctorRecord>>(readerClient) {
    cacheName = remoteName
    maxLocalSize = 10
    redisTtl = Duration.ofMinutes(5)
}

writer.put("1:7", expectedDoctors)
reader.get("1:7") shouldBeEqualTo expectedDoctors
```

Test doctor/equipment/treatment caches independently and `finally`에서 두 cache와 client를
닫는다. `@Testcontainers`는 사용하지 않고 기존 singleton launcher만 사용한다.

- [ ] **Step 2: wire 결과에 따라 namespace를 확정한다**

If the three legacy fixtures and Redis cross-instance tests pass, retain existing remote cache names.

If any legacy fixture fails, modify `CacheConfig.kt`:

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

Replace the legacy success assertions with one migration contract test that verifies legacy decode
fails for the observed fixture family and all remote names end in `-v2`. Do not assert a vendor
exception class; assert `runCatching { decode<...>(...) }.isFailure`.

- [ ] **Step 3: targeted cache tests를 GREEN으로 만든다**

Run:

```bash
./gradlew :appointment-api:test --no-daemon --console=plain \
  --tests '*NearCacheWireCompatibilityTest' \
  --tests '*CacheIntegrationTest' \
  --tests '*NearCacheAdapterTest'
```

Expected: all passing. `CacheIntegrationTest`는 logical cache name을 계속 사용해야 한다.

- [ ] **Step 4: namespace가 변경된 경우 운영 runbook을 작성한다**

Create `docs/runbooks/dependency-1.4.0-cache-migration.md` with this exact order:

1. 새 binary 배포 전 `clinic-*-v2` key만 targeted clear한다.
2. 새 binary를 canary 배포하고 v2 hit/miss와 decode error를 확인한다.
3. rollback이면 트래픽을 중단하고 `clinic-*` v1 key를 targeted clear한 뒤 구 binary를 배포한다.
4. rollout 성공 뒤 1시간 TTL과 관찰 window가 지난 후 v1 key를 삭제한다.
5. `FLUSHALL`, tenant 동적 suffix, schema-down은 사용하지 않는다.

- [ ] **Step 5: cache migration을 커밋한다**

```bash
git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheWireCompatibilityTest.kt \
  docs/runbooks/dependency-1.4.0-cache-migration.md
git commit
```

`CacheConfig.kt` 또는 runbook이 변경되지 않은 compatible 경로에서는 실제 변경 파일만 add한다.

## 5. Task 4 — Timefold 2.4.0 품질과 성능

- [ ] **Step 1: solver 전체 테스트로 2.4.0 validation을 실행한다**

Run:

```bash
./gradlew :appointment-solver:test --no-daemon --console=plain
```

Expected: 68 tests passing. 새 fail-fast 오류가 나오면 validation을 끄지 않고 planning model 또는
fixture의 실제 계약만 최소 수정한다.

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

- [ ] **Step 3: README의 잘못된 benchmark class를 수정한다**

Modify `appointment-solver/README.md`:

```bash
./gradlew :appointment-solver:test --tests "*solver.benchmark.BenchmarkTest"
```

- [ ] **Step 4: solver 검증을 커밋한다**

```bash
git add appointment-solver/README.md
git commit
```

README가 이미 정확하면 문서 커밋은 생략하고 lesson에 검증 결과만 기록한다.

## 6. Task 5 — non-frontend 모듈과 benchmark 검증

- [ ] **Step 1: 정적 build를 실행한다**

```bash
./gradlew build -x test -x :frontend:appointment-frontend:build \
  --parallel --refresh-dependencies --no-daemon --console=plain
./gradlew detekt --parallel --no-daemon --console=plain
```

Expected: both successful.

- [ ] **Step 2: singleton container 충돌을 피하도록 module tests를 순차 실행한다**

```bash
./gradlew :appointment-core:test :appointment-event:test --parallel --no-daemon --console=plain
./gradlew :appointment-solver:test --no-daemon --console=plain
./gradlew :appointment-notification:test --no-daemon --console=plain
./gradlew :appointment-messaging:test --no-daemon --console=plain
./gradlew :appointment-api:test --no-daemon --console=plain -Dspring.profiles.active=test
./gradlew :appointment-api:test --no-daemon --console=plain -Dspring.profiles.active=test,test-postgresql
./gradlew :appointment-api:test --no-daemon --console=plain -Dspring.profiles.active=test,test-mysql
./gradlew :appointment-messaging-benchmark:test --no-daemon --console=plain
```

- [ ] **Step 3: Kafka4 targeted integration을 확인한다**

```bash
./gradlew :appointment-messaging:test --no-daemon --console=plain \
  --tests '*AppointmentMessagingKafkaIntegrationTest' \
  --tests '*AppointmentKafkaConsumerIntegrationTest'
```

Expected: readiness, broker ack, duplicate redelivery와 rebalance recovery tests passing.

- [ ] **Step 4: PostgreSQL messaging benchmark smoke와 validator를 실행한다**

```bash
./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark --no-daemon --console=plain
node --test tests/benchmarks/appointment-messaging-benchmark-scripts.test.mjs
node scripts/collect-appointment-messaging-benchmark.mjs \
  --input-dir benchmark/appointment-messaging-benchmark/build/reports/benchmarks \
  --output build/reports/appointment-messaging-postgresql/benchmark.json
node scripts/validate-appointment-messaging-benchmark.mjs \
  --input build/reports/appointment-messaging-postgresql/benchmark.json
```

Expected: smoke task and JSON validator successful. benchmark API 역의존은 수정하지 않는다.

- [ ] **Step 5: security evidence를 확인한다**

```bash
gh api repos/bluetape4k/clinic-appointment/dependabot/alerts \
  --paginate --jq '[.[] | select(.state == "open") | {number, dependency: .dependency.package.name, severity: .security_advisory.severity}]'
```

Open alert가 있으면 #253 diff와 관련된 좌표인지 분리한다. 권한이 없어 조회하지 못하면
그 사실을 lesson과 PR의 unchecked 항목에 기록한다.

## 7. Task 6 — evidence, review, commit

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
rg -n '2\.2\.0|timefold-solver-bom|springdoc-openapi = "' \
  gradle/libs.versions.toml build.gradle.kts
```

Expected: forbidden version/alias matches absent and diff check clean.

- [ ] **Step 3: 독립 7-tier/Kotlin pattern review를 실행한다**

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
git add docs/lessons/2026-08-09-issue-253-dependencies-1.4.0.md
git commit
```

Commit intent: `의존성 전환의 resolved graph와 복구 증거를 남긴다`와 Lore trailer를 사용한다.

## 8. Task 7 — push, PR, CI readiness

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
- final section: `## DoD Status`

- [ ] **Step 4: live PR body와 CI를 재확인한다**

```bash
gh pr view --json number,url,headRefOid,body,assignees,labels,milestone,mergeStateStatus,statusCheckRollup
```

모든 required check와 review thread를 확인한다. exact `headRefOid`와 merge-ready 근거를
사용자에게 보고하고 멈춘다. merge, auto-merge, branch 삭제는 수행하지 않는다.

## 9. 계획 자체 검토

- 설계의 버전 권한, Redis wire/rollback, solver 품질, module/benchmark, review/CI 기준이
  각각 Task 2~8에 연결된다.
- Timefold 공개 API 노출이나 benchmark module 역의존은 #253에서 변경하지 않는다.
- legacy fixture는 구현 변경 전에 생성·검증되고 BOM 변경 뒤 RED/GREEN 결정 근거가 된다.
- cache compatible/incompatible 두 경로 모두 논리 cache name과 rollback 조건이 명시되어 있다.
- 임시 generator는 fixture 생성 뒤 삭제되며 repository에 남지 않는다.
- 실제 production Redis clear와 배포는 승인 범위가 아니므로 runbook만 만들고 실행하지 않는다.

