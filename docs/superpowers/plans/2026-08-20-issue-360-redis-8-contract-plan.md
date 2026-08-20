# Redis 8 고정 호환성 검증 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `RedisServer.Launcher.redis`가 제공하는 `redis:8.8.1`에서 API cache/NearCache와 notification Lettuce leader lifecycle의 실제 호환성 계약을 검증한다.

**Architecture:** 기존 API `Containers.Redis` singleton은 그대로 재사용하고 notification 테스트에도 동일한 `RedisServer.Launcher.redis`와 Lettuce helper를 사용한다. API에는 이미지·singleton 계약 테스트를 추가하고, notification에는 `SCRIPT FLUSH` 후 실제 `LettuceLeaderGroupElector`를 실행하는 통합 테스트를 추가한다. CI workflow는 변경하지 않고 기존 모듈 테스트 job이 새 테스트를 실행하게 한다.

**Tech Stack:** Kotlin 2.3, JUnit 5, Lettuce 7.6.0.RELEASE, `bluetape4k-testcontainers`, Redis 8.8.1, Gradle, Testcontainers singleton launcher.

---

## 파일 구조와 책임

| 경로 | 책임 |
|---|---|
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/RedisServerContractTest.kt` | API 테스트가 `RedisServer.Launcher.redis`와 Redis 8 image를 사용하는지 검증한다. |
| `appointment-notification/build.gradle.kts` | notification 통합 테스트가 기존 bluetape4k Redis launcher를 사용할 수 있도록 테스트 전용 의존성을 선언한다. |
| `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/RedisLeaderGroupCompatibilityTest.kt` | 실제 Redis에서 Lua fallback, leader action/release, connection close를 검증한다. |
| `docs/lessons/2026-08-20-issue-360-redis-8-contract.md` | Redis 8 고정 결정, launcher 선택 이유, 검증 결과와 향후 matrix 경계를 기록한다. |
| `.github/workflows/ci.yml`, `.github/workflows/nightly.yml` | 변경하지 않는다. 기존 API/notification 전체 테스트 job이 새 테스트를 자동 실행하는지 확인만 한다. |

## Task 1: API Redis 8 launcher 계약 테스트 추가

**Files:**
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/RedisServerContractTest.kt`
- Reference only: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/Containers.kt`

- [ ] **Step 1: 계약 테스트를 작성한다.**

```kotlin
package io.bluetape4k.clinic.appointment.api.test

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.Test

class RedisServerContractTest {

    @Test
    fun `API 테스트는 Redis 8 launcher singleton을 사용한다`() {
        (Containers.Redis === RedisServer.Launcher.redis).shouldBeTrue()
        Containers.Redis.dockerImageName shouldBeEqualTo "${RedisServer.IMAGE}:${RedisServer.TAG}"
        RedisServer.TAG.startsWith("8.").shouldBeTrue()
    }
}
```

- [ ] **Step 2: 새 테스트만 실행해 계약을 확인한다.**

Run:

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.test.RedisServerContractTest" \
  --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`과 `1 test completed, 1 passed`가 출력된다. 실패하면 `Containers.Redis`를 수정하지 말고 `RedisServer.TAG`와 resolved `bluetape4k-testcontainers` 버전을 먼저 확인한다.

- [ ] **Step 3: 기존 API Redis 경로가 같은 singleton을 계속 쓰는지 확인한다.**

Run:

```bash
rg -n "RedisServer\.Launcher\.redis|Containers\.Redis\.url" \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test \
  appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config
```

Expected: `Containers.kt`, `NearCacheWireCompatibilityTest.kt`, `AbstractApiIntegrationTest.kt`가 launcher/url 경로를 유지하고 별도 matrix fixture가 없다.

## Task 2: notification Redis launcher 통합 테스트 추가

**Files:**
- Modify: `appointment-notification/build.gradle.kts` dependency block
- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/RedisLeaderGroupCompatibilityTest.kt`

- [ ] **Step 1: 테스트 전용 Testcontainers helper 의존성을 추가한다.**

`appointment-notification/build.gradle.kts`의 기존 테스트 의존성 아래에 다음 한 줄을 추가한다.

```kotlin
testImplementation(libs.bluetape4k.testcontainers)
```

`@Testcontainers`, `org.testcontainers.containers.GenericContainer`, 별도 `RedisServer()` 생성은 추가하지 않는다.

- [ ] **Step 2: Lua fallback과 leader lifecycle 테스트를 작성한다.**

```kotlin
package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.lettuce.leaderGroupElection
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class RedisLeaderGroupCompatibilityTest {

    @Test
    fun `Redis 8 launcher에서 Lua fallback과 leader lifecycle을 검증한다`() {
        val redis = RedisServer.Launcher.redis
        val client = RedisServer.Launcher.LettuceLib.getRedisClient()
        val connection = client.connect()
        val commands = connection.sync()
        val lockName = "clinic-appointment:redis-contract:${UUID.randomUUID()}"
        val slotKey = "lg{$lockName}"

        try {
            redis.dockerImageName shouldBeEqualTo "${RedisServer.IMAGE}:${RedisServer.TAG}"
            RedisServer.TAG.startsWith("8.").shouldBeTrue()
            commands.del(slotKey, "$slotKey:meta")
            commands.scriptFlush()

            val elector = connection.leaderGroupElection(
                LeaderGroupElectionOptions(
                    maxLeaders = 1,
                    waitTime = 1.seconds,
                    leaseTime = 5.seconds,
                )
            )

            elector.runIfLeader(lockName) { "leader" } shouldBeEqualTo "leader"
            elector.activeCount(lockName) shouldBeEqualTo 0
            elector.availableSlots(lockName) shouldBeEqualTo 1
        } finally {
            runCatching { commands.del(slotKey, "$slotKey:meta") }
            connection.close()
        }

        connection.isOpen.shouldBeFalse()
    }
}
```

`SCRIPT FLUSH`가 서버의 script cache를 비운 뒤 첫 acquire/release/status 호출은 `EVALSHA`의 `NOSCRIPT` fallback을 거쳐야 한다. `runIfLeader`의 결과와 release 후 `activeCount`/`availableSlots`를 확인해 action 및 lifecycle을 함께 고정한다. connection은 `finally`에서 닫고 닫힌 상태를 확인한다.

- [ ] **Step 3: 새 notification 테스트를 단독 실행한다.**

Run:

```bash
./gradlew :appointment-notification:test \
  --tests "io.bluetape4k.clinic.appointment.notification.RedisLeaderGroupCompatibilityTest" \
  --no-daemon --console=plain
```

Expected: Redis singleton이 기동되고 `BUILD SUCCESSFUL`이 출력된다. Docker/Colima 오류가 발생하면 `colima status`, `docker context show`, `docker info`를 확인한 뒤 동일 테스트를 순차 재실행한다. 테스트를 skip하거나 `@Testcontainers`로 우회하지 않는다.

## Task 3: lesson 문서로 Redis 8 결정과 경계를 기록한다

**Files:**
- Create: `docs/lessons/2026-08-20-issue-360-redis-8-contract.md`
- Reference: `docs/superpowers/specs/2026-08-20-issue-360-redis-8-contract-design.md`
- Reference: Issue `https://github.com/bluetape4k/clinic-appointment/issues/360`

- [ ] **Step 1: 한국어 lesson을 작성한다.**

문서에는 다음 사실을 포함한다.

```markdown
# Issue #360 Redis 8 고정 계약 lesson

## 배경

`RedisServer.Launcher.redis`는 현재 `RedisServer.TAG = "8.8.1"`을 사용한다. 7.2/8.8 matrix를 구현하면 현재 launcher 계약을 우회하게 된다.

## 결정

서비스 지원 기준은 `redis:8.8.1`로 고정하고 API와 notification 테스트 모두 `RedisServer.Launcher.redis` singleton을 사용한다. 전역 Gradle dependency locking은 Issue #361에 남긴다.

## 결과

API image/singleton 계약, 기존 cache/NearCache 통합 경로, notification Lua fallback·leader release·connection close를 실제 Redis에서 검증한다.

## 검증

`<실제 실행한 명령과 결과>`를 기록하고 CI job 링크와 PR을 연결한다. 실패한 시도와 Docker/Colima 원인이 있으면 성공 결과와 구분해 기록한다.

## 다음 작업 경계

Redis 7.2/8.8 matrix 또는 Redis 8 전용 명령(Array/INCREX/XNACK)은 별도 Issue와 launcher/API 계약 없이는 추가하지 않는다.
```

이 절에는 작성 시점에 실제 실행한 명령과 출력 결과를 기록한다. 구현 전 결과를 추정하지 않는다.

- [ ] **Step 2: 문서 검수와 terminology audit을 실행한다.**

Run:

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  --series clinic-appointment --json \
  docs/lessons/2026-08-20-issue-360-redis-8-contract.md
```

Expected: `git diff --check`가 조용히 종료되고 terminology findings가 없다. SPW-01~05를 문서 검수 기록에 남긴다.

## Task 4: 모듈 전체 검증과 CI 경로 확인

**Files:**
- Reference: `.github/workflows/ci.yml`
- Reference: `.github/workflows/nightly.yml`
- Reference: `appointment-api/build.gradle.kts`
- Reference: `appointment-notification/build.gradle.kts`

- [ ] **Step 1: API와 notification 전체 테스트를 순차 실행한다.**

Run:

```bash
./gradlew :appointment-api:test --no-daemon --console=plain
./gradlew :appointment-notification:test --no-daemon --console=plain
```

Expected: 두 명령 모두 `BUILD SUCCESSFUL`이다. 두 모듈 모두 Testcontainers-backed 테스트를 포함하므로 동시에 실행하지 않는다.

- [ ] **Step 2: 변경 범위의 컴파일·정적 검증을 실행한다.**

Run:

```bash
./gradlew :appointment-api:compileTestKotlin \
  :appointment-notification:compileTestKotlin \
  --no-daemon --console=plain
git diff --check
```

Expected: compile/test source set이 성공하고 whitespace 오류가 없다.

- [ ] **Step 3: CI와 nightly가 새 테스트를 포함하는지 확인한다.**

Run:

```bash
rg -n -C 6 'test-api|test-notification|appointment-api:test|appointment-notification:test' \
  .github/workflows/ci.yml .github/workflows/nightly.yml
```

Expected: 기존 `test-api`와 `test-notification` job이 각각 모듈 전체 `test`를 실행한다. workflow 파일은 변경하지 않으며 actionlint를 새로 실행할 변경도 없다.

## Task 5: 커밋, PR, CI 증거를 정리한다

**Files:**
- Modify: implementation and lesson files from Tasks 1–3
- Reference: Issue #360 live metadata
- Create: Korean PR body and live GitHub PR metadata

- [ ] **Step 1: 변경 diff와 #361 격리를 확인한다.**

Run:

```bash
git status --short
git diff --stat origin/develop...HEAD
git diff --name-only origin/develop...HEAD
```

Expected: 변경 파일은 API 계약 테스트, notification build/test, lesson 문서뿐이며 #361 lockfile/verification metadata 변경은 없다.

- [ ] **Step 2: Lore trailers를 포함한 한국어 커밋을 만든다.**

```bash
git add appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/RedisServerContractTest.kt \
  appointment-notification/build.gradle.kts \
  appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/RedisLeaderGroupCompatibilityTest.kt \
  docs/lessons/2026-08-20-issue-360-redis-8-contract.md
git commit -m "검증: Redis 8.8.1 launcher 호환성 계약을 고정한다" \
  -m "API cache/NearCache와 notification Lettuce leader lifecycle을 기존 Redis launcher singleton에서 실제 Redis로 검증한다." \
  -m "Constraint: RedisServer.Launcher.redis는 RedisServer.TAG=8.8.1을 사용한다." \
  -m "Rejected: Redis 7.2/8.8 matrix와 전역 dependency locking은 별도 Issue로 남긴다." \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Directive: 다른 Redis 버전이 필요하면 launcher/API 계약과 별도 Issue를 먼저 만든다." \
  -m "Tested: API contract, API integration, notification Lua/lifecycle, compileTestKotlin, git diff --check." \
  -m "Not-tested: production Redis deployment과 Redis 7.2 호환성은 이 범위에서 검증하지 않는다."
```

- [ ] **Step 3: PR을 생성하고 live metadata를 읽어 확인한다.**

PR은 `feat/issue-360-redis-matrix`에서 `develop`을 base로 생성한다. 제목과 본문은 한국어로 작성하고 `Closes #360`을 보존한다. Issue #360의 assignee `debop`, labels `enhancement`, `maintenance`, `test`, milestone `1.4.0`을 PR metadata에도 맞춘다.

PR 본문에는 다음을 포함한다.

```markdown
## 요약

- `RedisServer.Launcher.redis` 기반 Redis 8.8.1 계약 테스트 추가
- API cache/NearCache 경로와 notification Lettuce leader Lua fallback/lifecycle 검증
- #361 전역 Gradle dependency locking은 범위에서 제외

## 검증

- `./gradlew :appointment-api:test --no-daemon --console=plain`
- `./gradlew :appointment-notification:test --no-daemon --console=plain`
- `./gradlew :appointment-api:compileTestKotlin :appointment-notification:compileTestKotlin --no-daemon --console=plain`
- `git diff --check`

## DoD Status

- [x] Redis 8.8.1 launcher singleton 계약 고정
- [x] API cache/NearCache 검증
- [x] notification Lua fallback, release, connection close 검증
- [x] 한국어 lesson 기록
- [ ] CI 전체 통과 대기

Closes #360
```

PR 생성 후 `gh pr view <number> --json title,body,headRefName,baseRefName,assignees,labels,milestone,statusCheckRollup,url`로 title/body/브랜치/metadata/CI를 읽어 확인한다. CI가 실패하면 로그를 원인별로 수정하고 전체 모듈 테스트를 다시 실행한다.

## 계획 자체 검토

- **Spec coverage:** Redis 8.8.1 고정, launcher singleton, API cache/NearCache, notification Lua fallback, release/close, 문서, CI 경로, #361 제외를 각각 Task 1–5에 매핑했다.
- **Placeholder scan:** 구현·검증 단계에는 실제 파일, 코드, 명령, 기대 결과를 적었고, lesson의 검증 결과는 실행 후 실제 출력으로 채운다.
- **Type consistency:** `RedisServerContractTest`, `RedisLeaderGroupCompatibilityTest`, `RedisServer.TAG`, `RedisServer.Launcher.redis` 이름을 모든 단계에서 동일하게 사용한다.
- **Plan review:** 성능은 production hot path 변경이 없어 N/A, 안정성은 lifecycle/cleanup 단계로 고정, 보안은 production 입력 경계 변경이 없어 N/A, 운영은 Redis 8.8.1 rollback/지원 경계, 개발자/API는 기존 helper 재사용, 사용자/호출자는 Issue·lesson 범위를 검증한다. P0=0, P1=0.

## 문서 검수 기록

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | Issue #360, 설계 문서, launcher 소스, API/notification 테스트 구조를 source ledger로 고정했다. |
| SPW-02 | PASS | 파일 구조, 순서, 코드, 명령, 기대 결과, rollback, PR/CI handoff를 포함했다. |
| SPW-03 | PASS | 한국어 artifact 정책과 기술 토큰 보존 규칙을 적용했다. |
| SPW-04 | PASS | 설계의 모든 DoD를 Task 1–5와 검증 명령에 매핑했다. |
| SPW-05 | PASS | Markdown header, tables, lists, code fences를 작성 후 read-back했다. |
