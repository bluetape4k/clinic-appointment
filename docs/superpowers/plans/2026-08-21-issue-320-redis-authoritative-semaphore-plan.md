# Notification Outbox Redis 권위 동시성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `NotificationOutboxDispatcher`의 전역·병원별 provider admission을 Redis-authoritative expirable semaphore로 공유하고 Redis 장애에서는 fail-closed backpressure를 적용한다.

**Architecture:** dispatcher는 `NotificationOutboxConcurrencyCoordinator` 하나의 port만 호출한다. Redis connection이 없으면 기존 local semaphore implementation을 사용하고, connection이 있으면 global/clinic별 `LettuceSuspendPermitExpirableSemaphore`를 lazy-create하여 capacity 초기화, bounded acquire, renew, reconcile, release를 수행한다. Redis 조정 실패는 `NOT_READY`로 반환하여 DB lease recovery가 재시도하게 한다.

**Tech Stack:** Kotlin 2.3, kotlinx-coroutines, Spring Boot 4 auto-configuration, Lettuce 7.6, `bluetape4k-lettuce 1.12.1`, JUnit 5, MockK, Testcontainers Redis 8 launcher, Micrometer.

---

## 변경 파일 지도

- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxConcurrencyCoordinator.kt` — local/distributed permit port, expirable lease lifecycle, typed failure mapping.
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxDispatcher.kt` — 기존 local permit 직접 사용을 coordinator 호출로 교체하고 `close` lifecycle을 노출한다.
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt` — optional `StatefulRedisConnection`을 dispatcher coordinator에 전달하고 Spring destroy lifecycle을 연결한다.
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxMetrics.kt` — bounded concurrency admission/backpressure counter를 추가한다.
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxDispatcherTest.kt` — local coordinator 회귀와 Redis failure/backpressure 결과를 고정한다.
- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxConcurrencyCoordinatorTest.kt` — fake semaphore 기반 acquire/reconcile/renew/release 단위 테스트.
- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxConcurrencyRedisIntegrationTest.kt` — Redis 8 launcher 기반 두 coordinator global/clinic 상한, release, expiry 검증.
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfigurationTest.kt` — Redis connection 제공 시 dispatcher가 distributed coordinator를 선택하는지 검증한다.
- Create: `docs/lessons/2026-08-21-issue-320-redis-authoritative-semaphore.md` — 구현 중 배운 운영·API 결정 기록.

## Task 1: failing contract tests 작성

**Files:**
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxConcurrencyCoordinatorTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxDispatcherTest.kt`

- [ ] **Step 1: fake semaphore와 coordinator contract fixture를 만든다.**
  - capacity, active count, acquire outcome, renew outcome, release/reconcile 호출을
    관찰할 수 있는 fake를 만든다.
  - production class나 Redis client를 먼저 추가하지 않는다.

- [ ] **Step 2: Redis unavailable/backpressure 테스트를 작성한다.**
  - distributed mode에서 global 또는 clinic acquire가 unavailable/backend failure이면
    worker 호출 횟수가 0이고 dispatcher 결과가 `NOT_READY`인지 고정한다.
  - Redis 없는 default constructor는 기존 worker 결과와 global/clinic local 상한을
    유지하는지 고정한다.

- [ ] **Step 3: ambiguous acquire/reconcile와 cleanup 테스트를 작성한다.**
  - 첫 acquire가 ambiguous여도 동일 owner/request로 reconcile하여 owned handle을
    받은 경우에만 worker가 실행되는지 확인한다.
  - worker 예외와 cancellation 모두 release가 시도되는지 확인한다.

- [ ] **Step 4: RED를 확인한다.**
  - Run: `./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationOutboxConcurrencyCoordinatorTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationOutboxDispatcherTest"`
  - Expected: coordinator type, distributed gate, 또는 새 contract가 아직 없어 의도한 compile/test failure가 난다.

## Task 2: coordinator의 최소 local/distributed admission 구현

**Files:**
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxConcurrencyCoordinator.kt`

- [ ] **Step 1: public 내부 port와 bounded outcomes를 추가한다.**
  - coordinator는 `suspend fun <T> withPermit(notification, action)`을 제공한다.
  - acquired value와 fail-closed backpressure를 구분하는 내부 outcome을 사용한다.
  - tenant/clinic/request identity를 log/metric tag에 넣지 않는다.

- [ ] **Step 2: local path를 기존 semantics로 이식한다.**
  - global `Semaphore`와 reference-counted clinic registry를 유지한다.
  - registry entry는 reference count가 0일 때만 제거한다.

- [ ] **Step 3: Lettuce expirable adapter를 연결한다.**
  - `LettuceSuspendPermitExpirableSemaphore.create(connection, name, config)`를 사용한다.
  - `trySetPermits`의 `Initialized`와 `AlreadyInitialized`만 성공으로 취급한다.
  - owner는 coordinator lifetime 동안 유지하고 request는 permit acquire마다 새로 만든다.
  - global key와 tenant/clinic key를 분리한다.

- [ ] **Step 4: acquire ambiguous를 reconcile한다.**
  - `Acquired`는 handle을 반환한다.
  - `Ambiguous`는 동일 request로 reconcile하고 `Owned`인 경우에만 작업을 계속한다.
  - `Unavailable`, `TimedOut`, backend/integrity/closed/unknown 결과는 backpressure로
    변환한다.

- [ ] **Step 5: expirable renew/release lifecycle을 구현한다.**
  - lease 중간 간격으로 renew하고 ownership loss/backend/integrity failure에서는
    작업을 중단한다.
  - `finally`에서 renew job을 취소하고 `withContext(NonCancellable)` 안에서 clinic,
    global 순서로 release/reconcile를 수행한다.
  - `CancellationException`은 재전파한다.

- [ ] **Step 6: 최소 단위 테스트를 GREEN으로 만든다.**
  - Run: Task 1의 targeted Gradle command.
  - Expected: fake semaphore contract, local semantics, failure mapping, cleanup tests PASS.

## Task 3: dispatcher 및 Spring wiring 통합

**Files:**
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxDispatcher.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxDispatcherTest.kt`
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfigurationTest.kt`

- [ ] **Step 1: dispatcher가 coordinator를 통해서만 worker를 admission한다.**
  - 기존 claim/fairness/recovery 흐름과 route gate는 바꾸지 않는다.
  - permit을 얻지 못한 claimed row는 worker를 호출하지 않고 `NOT_READY`를 반환한다.
  - dispatcher를 `AutoCloseable`로 만들고 coordinator close를 위임한다.

- [ ] **Step 2: optional Redis connection을 주입한다.**
  - `ObjectProvider<StatefulRedisConnection<String, String>>`로 connection 부재를
    정상 처리한다.
  - connection이 있으면 distributed coordinator를 생성하고, 없으면 local coordinator를
    생성한다.
  - shared Lettuce connection 자체는 dispatcher가 닫지 않는다.

- [ ] **Step 3: auto-configuration 회귀 테스트를 통과시킨다.**
  - connection 없는 context가 기존 dispatcher를 구성하는지 확인한다.
  - connection 있는 context가 Redis coordinator를 선택하는지 확인한다.

- [ ] **Step 4: targeted regression을 실행한다.**
  - Run: `./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationOutboxDispatcherTest" --tests "io.bluetape4k.clinic.appointment.notification.NotificationAutoConfigurationTest"`
  - Expected: PASS.

## Task 4: metrics 및 Redis 8 통합 검증

**Files:**
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxMetrics.kt`
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxMetricsTest.kt`
- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxConcurrencyRedisIntegrationTest.kt`

- [ ] **Step 1: bounded admission/backpressure metric을 추가한다.**
  - mode와 bounded reason만 tag로 사용한다.
  - 기존 `METER_NAMES` 계약과 metric test를 갱신한다.

- [ ] **Step 2: Redis 8 단일 launcher 통합 테스트를 작성한다.**
  - `RedisServer.Launcher.redis`와 `RedisServer.Launcher.LettuceLib.getRedisClient()`만
    사용한다.
  - `RedisServer.TAG == "8"`을 검증한다.
  - 두 coordinator가 같은 global/clinic key에서 동시에 action을 실행할 때 총 active
    수가 각 상한 이하인지 확인한다.
  - 정상 release와 짧은 expirable lease 만료 후 capacity 회복을 확인한다.

- [ ] **Step 3: 통합 테스트를 실행한다.**
  - Run: `./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationOutboxConcurrencyRedisIntegrationTest"`
  - Expected: Docker/Colima의 Redis 8이 시작되고 PASS. bind-mount 오류나 skip은 성공으로
    처리하지 않고 원인을 진단한다.

## Task 5: 문서·정적 검증·리뷰

**Files:**
- Create: `docs/lessons/2026-08-21-issue-320-redis-authoritative-semaphore.md`
- All changed Kotlin/docs files

- [ ] **Step 1: Kotlin pattern/testing/Spring checklist를 적용한다.**
  - KDoc은 한국어로 작성한다.
  - cancellation, resource ownership, logging, `require`/`check`, no `!!`를 확인한다.

- [ ] **Step 2: lesson 문서를 작성한다.**
  - expirable 선택 이유, Redis 장애 fail-closed 정책, provider/DB authority 경계를
    기록한다.

- [ ] **Step 3: 전체 notification 검증을 실행한다.**
  - Run: `./gradlew :appointment-notification:build`
  - Run: `git diff --check`
  - Expected: build/test/static checks PASS and whitespace check clean.

- [ ] **Step 4: six-perspective code review를 수행한다.**
  - caller/API, developer, verifier, performance, security, SRE/library-user 관점으로
    독립 검토하고 P0/P1을 수정한다.
  - 리뷰 문서는 변경 파일과 acceptance evidence만 참조한다.

- [ ] **Step 5: 구현/lesson commit을 Lore protocol로 생성한다.**
  - Korean intent line과 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`,
    `Directive`, `Tested`, `Not-tested` trailers를 포함한다.

## Task 6: PR 및 merge-ready handoff

- [ ] **Step 1: PR 전 최신 상태를 확인한다.**
  - `git status --short --branch`, `git log`, `gh pr list`, targeted test/build 결과를
    다시 확인한다.

- [ ] **Step 2: Korean PR을 생성한다.**
  - Repository: `bluetape4k/clinic-appointment`
  - Base: `develop`
  - Head: `feat/issue-320-redis-authoritative-semaphore`
  - Body에는 `Closes #320`, 설계/테스트 evidence, Redis 장애 정책, `## DoD Status`를
    포함한다.
  - assignee `debop`, milestone `1.4.0`, issue labels와 metadata를 live read-back한다.

- [ ] **Step 3: CI와 review state를 확인한다.**
  - PR checks가 성공했는지 `gh pr checks`와 `gh pr view --json`으로 확인한다.
  - 실패 시 원인별로 수정하고 재검증한다.

- [ ] **Step 4: merge-ready에서 멈춘다.**
  - fresh merge approval 없이는 merge, branch deletion, worktree cleanup을 수행하지
    않는다.
