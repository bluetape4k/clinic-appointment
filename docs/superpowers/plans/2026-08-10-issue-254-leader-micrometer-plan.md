# #254 리더 실행 경계와 Micrometer 관측성 구현 계획

> **For agentic workers:** 이 계획은 작업 단위별 체크박스를 순서대로 실행하고 각 RED/GREEN·검증 증거를 남긴다.

**Goal:** `appointment-notification`의 reminder recovery tick 전체를 Leader 0.5 실행 경계 안에서 수행하고, 공식 `leader-micrometer` decorator의 낮은 cardinality 관측성을 연결한다.

**Architecture:** scheduler는 DB scanner의 순수 paging 로직만 담당하고, Spring scheduled runner가 optional `LeaderGroupElector`로 한 tick을 감싼다. Redis connection이 있으면 raw `LettuceLeaderGroupElector`를 만들고 `MeterRegistry`가 있을 때만 `InstrumentedLeaderGroupElector`로 decorate한다. Redis 또는 registry가 없을 때는 각각 direct path 또는 raw elector로 호환 동작한다.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, Micrometer, bluetape4k-leader 0.5.0, Lettuce, JUnit 5, MockK, Kluent assertions, Gradle version catalog.

---

## 파일 소유권과 변경 경계

- Modify: `gradle/libs.versions.toml` — BOM-managed `bluetape4k-leader-micrometer` alias만 추가한다.
- Modify: `appointment-notification/build.gradle.kts` — 위 alias를 `implementation`으로 추가한다.
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/AppointmentReminderScheduler.kt` — scheduled path에서 더 이상 guard를 주입하지 않도록 하고, 기존 direct-call 호환용 `ReminderRecoveryTriggerGuard`와 선택적 인자는 deprecated 유지한다.
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchedulingRunners.kt` — optional `LeaderGroupElector`, 고정 lock name, leader action 경계, cancellation 재전파를 추가한다.
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt` — raw/decorated leader bean을 interface로 노출하고 runner에 주입한다.
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationReminderRecoveryScannerTest.kt` — legacy guard skip direct-call 회귀를 유지하고 runner/elector 경계 테스트를 추가한다.
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchedulingRunnersTest.kt` — acquired/skip/failure/cancel/Redis failure 경로와 runner metric 호출을 추가한다.
- Modify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfigurationTest.kt` — reminder runner가 optional leader 없이도 구성되고 meter registry가 있어도 startup하지 않음을 확인한다.
- Modify: `appointment-notification/README.md`, `appointment-notification/README.ko.md` — reminder recovery가 leader action 전체에서 실행되고 `shedlock.leader.*` meter가 기본 redacted tag로 기록되는 운영 계약을 문서화한다.
- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderMicrometerTest.kt` — 공식 decorator의 acquired/not-acquired/duration/active와 기본 redaction을 SimpleMeterRegistry로 고정한다.
- Create: `docs/reviews/2026-08-10-issue-254-spec-review.md`, `docs/reviews/2026-08-10-issue-254-plan-review.md` — 6-lens + main integration review 결과를 기록한다.
- Create: `docs/lessons/2026-08-10-issue-254-leader-micrometer.md` — 구현 중 발견한 API·취소·cardinality 교훈을 기록한다.
- Create: `docs/reviews/2026-08-10-issue-254-implementation-review.md` — 최종 diff/7-tier review와 P0/P1 수렴을 기록한다.

### 위험과 롤백

- 새 artifact가 resolve되지 않으면 catalog alias와 build dependency를 되돌리고 implementation을 시작하지 않는다.
- Redis bean은 `LeaderGroupElector` interface로만 노출하여 raw/decorated 중복 후보를 방지한다.
- scheduler 직접 호출 호환성을 위해 runner elector 기본값은 `null`이며, Redis 미구성 시 기존 direct path를 유지한다.
- action failure/cancellation은 `runSynchronously` 바깥에서 cancellation을 재전파하고, Redis failure는 tick에서 흡수한다.
- DB lease/fencing와 outbox schema는 touched file에 포함하지 않으며, 실패 시 해당 diff만 revert해도 DB 정확성 경계가 유지된다.

## Task 1: RED 테스트와 dependency resolution 고정

**Files:** 위 테스트 4개와 `gradle/libs.versions.toml`, `appointment-notification/build.gradle.kts`.

- [ ] **Step 1: 현재 테스트를 baseline으로 실행한다.**

  Run:

  ```bash
  ./gradlew :appointment-notification:test --no-daemon --console=plain
  ```

  Expected: 현재 branch에서 모든 notification 테스트 PASS. 실패하면 기존 실패를 기록하고 새 실패와 섞지 않는다.

- [ ] **Step 2: leader decorator contract RED를 작성한다.**

  `NotificationLeaderMicrometerTest`에 다음 동작을 먼저 작성한다.

  ```kotlin
  @Test
  fun `decorator는 acquired duration active를 기록하고 기본 lock tag를 redacted 한다`() {
      val registry = SimpleMeterRegistry()
      val delegate = fakeElector(acquired = true)
      val elector = InstrumentedLeaderGroupElector(
          delegate,
          registry,
          REMINDER_RECOVERY_LOCK_NAME,
      )

      elector.runIfLeader(REMINDER_RECOVERY_LOCK_NAME) { "ok" } shouldBeEqualTo "ok"
      registry.counter("shedlock.leader.acquired", "lock.name", "redacted-lock").count() shouldBeEqualTo 1.0
      registry.find("shedlock.leader.duration").tag("lock.name", "redacted-lock").timer() shouldNotBe null
      registry.find("shedlock.leader.active").tag("lock.name", "redacted-lock").gauge()!!.value() shouldBeEqualTo 0.0
  }
  ```

  같은 파일에 not-acquired counter와 action failure 후 active 0 검증도 작성한다. `fakeElector`는 `LeaderGroupElector`의 `runIfLeader` action만 실행/skip하도록 MockK로 구성하고 나머지 interface 메서드는 relaxed mock으로 둔다.

- [ ] **Step 3: runner leader boundary RED를 작성한다.**

  `NotificationSchedulingRunnersTest`에 다음 네 시나리오를 추가한다.

  1. acquired: elector action이 scheduler를 한 번 호출하고 result metric을 기록한다.
  2. not-acquired: elector가 `null`을 반환하고 scheduler와 recovery metric을 호출하지 않는다.
  3. Redis failure: elector가 `IllegalStateException`을 던지고 scheduler를 호출하지 않는다.
  4. cancellation: scheduler action이 `kotlinx.coroutines.CancellationException`을 던지면 `poll()`도 동일 예외를 재전파한다.

  leader mock expectation은 `runIfLeader(REMINDER_RECOVERY_LOCK_NAME, any<() -> ReminderRecoveryScanResult?>())`로 고정해 lock name drift를 막는다.

- [ ] **Step 4: scheduled guard injection 제거 후 RED를 확인한다.**

  기존 `ReminderRecoveryTriggerGuard { false }` 테스트는 deprecated direct-call
  호환 테스트로 유지한다. auto-configuration이 더 이상 guard provider를
  주입하지 않고 runner가 elector action을 사용하는 새 테스트가 아직
  implementation을 바꿔야 실패하는지 확인한다.

- [ ] **Step 5: dependency alias를 추가하고 dependency graph를 확인한다.**

  `gradle/libs.versions.toml`의 bluetape leader alias 옆에

  ```toml
  bluetape4k-leader-micrometer = { module = "io.github.bluetape4k.leader:bluetape4k-leader-micrometer" }
  ```

  를 추가하고 module build에 `implementation(libs.bluetape4k.leader.micrometer)`를 추가한다. 직접 version은 쓰지 않는다.

  Run:

  ```bash
  ./gradlew :appointment-notification:dependencies --configuration testRuntimeClasspath --no-daemon --console=plain
  ```

  Expected: `bluetape4k-leader-micrometer:0.5.0`과 `micrometer-core`가 resolved graph에 나타난다.

## Task 2: 순수 scheduler와 leader-aware runner 구현

- [ ] **Step 1: scheduled path의 Boolean guard injection을 제거한다.**

  기존 primary constructor의 `triggerGuard: ReminderRecoveryTriggerGuard?` 위치와
  `triggerOnce()`의 false 반환은 direct-call 호환을 위해 유지하되
  `@Deprecated("scheduled path는 leaderElector action을 사용합니다")`로
  표시한다. auto-configuration의 `ObjectProvider<ReminderRecoveryTriggerGuard>`
  주입과 default guard 생성을 제거하고 default 값은 `null`이 되게 한다.
  `batchSize`와 `maxCandidatesPerRun`의 require 및
  `ReminderRecoveryScanResult.plus`는 유지한다.

- [ ] **Step 2: runner에 optional elector와 고정 lock을 추가한다.**

  `NotificationSchedulingRunners.kt`에 다음 형태를 적용한다.

  ```kotlin
  class NotificationReminderSchedulingRunner(
      private val scheduler: AppointmentReminderScheduler,
      private val metrics: NotificationOutboxMetrics? = null,
      private val leaderElector: LeaderGroupElector? = null,
  ) {
      fun poll() {
          try {
              val result = runSynchronously {
                  leaderElector?.runIfLeader(REMINDER_RECOVERY_LOCK_NAME) {
                      scheduler.triggerOnce()
                  } ?: scheduler.triggerOnce()
              } ?: return
              metrics?.recordReminderRecovery(result)
              // 기존 낮은 cardinality 완료 log 유지
          } catch (e: CancellationException) {
              throw e
          } catch (e: Exception) {
              log.warn { "리마인더 보정에 실패했습니다: failure=${e.javaClass.simpleName}" }
          }
      }
  }
  ```

  `REMINDER_RECOVERY_LOCK_NAME`은 `internal const val`로 `appointment-reminder-recovery`를 사용한다. `runSynchronously`는 기존 동기 Spring 경계 helper를 재사용하며 새 `runBlocking`을 도입하지 않는다.

- [ ] **Step 3: targeted runner tests를 GREEN으로 만든다.**

  Run:

  ```bash
  ./gradlew :appointment-notification:test --tests '*NotificationSchedulingRunnersTest' --tests '*NotificationReminderRecoveryScannerTest' --no-daemon --console=plain
  ```

  Expected: acquired/skip/failure/cancel, legacy direct-call guard 및 scheduler paging 테스트 PASS. cancellation assertion은 broad `Exception` catch에 흡수되지 않았음을 증명해야 한다.

## Task 3: Spring auto-configuration에 raw/decorated elector 연결

- [ ] **Step 1: leader bean을 interface + optional meter registry로 변경한다.**

  `NotificationAutoConfiguration.kt`에 `LeaderGroupElector`, `InstrumentedLeaderGroupElector`, `ObjectProvider<MeterRegistry>`를 import한다. 기존 bean은 다음 계약을 따른다.

  ```kotlin
  @Bean
  @ConditionalOnClass(name = ["io.bluetape4k.leader.micrometer.InstrumentedLeaderGroupElector"])
  @ConditionalOnBean(StatefulRedisConnection::class)
  fun notificationLeaderElection(
      connection: StatefulRedisConnection<String, String>,
      meterRegistryProvider: ObjectProvider<MeterRegistry>,
  ): LeaderGroupElector {
      val delegate = connection.leaderGroupElection()
      val registry = meterRegistryProvider.ifAvailable
      return registry?.let {
          InstrumentedLeaderGroupElector(delegate, it, REMINDER_RECOVERY_LOCK_NAME)
      } ?: delegate
  }
  ```

  `@ConditionalOnClass(RedisClient::class)`는 Redis backend classpath 보호를 위해 유지하되, decorator class를 method signature에 노출하지 않는다. bean method의 반환 타입은 interface 하나로 고정하여 raw/decorated 두 bean 후보를 만들지 않는다.

- [ ] **Step 2: reminder runner bean에 elector provider를 연결한다.**

  `notificationReminderSchedulingRunner`에 `ObjectProvider<LeaderGroupElector>`를 추가하고 `leaderElectorProvider.ifAvailable`을 세 번째 constructor 인자로 전달한다. Redis가 없는 기존 context는 `null` path로 유지한다.

- [ ] **Step 3: auto-configuration regression을 GREEN으로 만든다.**

  `NotificationAutoConfigurationTest`에 DB + reminder source/materializer context에서 runner bean이 생성되고, `LeaderGroupElector`가 없을 때 startup failure가 없음을 확인한다. `SimpleMeterRegistry`가 있는 observability context에서도 decorator class 부재/Redis connection 부재로 startup이 깨지지 않는지 확인한다.

  Run:

  ```bash
  ./gradlew :appointment-notification:test --tests '*NotificationAutoConfigurationTest' --no-daemon --console=plain
  ```

## Task 4: decorator/실패 lifecycle 검증과 7-tier 통합 review

- [ ] **Step 1: 공식 metric contract와 sanitization을 GREEN으로 만든다.**

  `NotificationLeaderMicrometerTest`를 실행해 acquired, not-acquired, duration timer 존재, action exception 후 active gauge 0, 기본 `redacted-lock` tag를 모두 확인한다. raw lock name이 registry에 tag로 나타나지 않는 assertion을 추가한다.

- [ ] **Step 2: 전체 notification test를 실행한다.**

  ```bash
  ./gradlew :appointment-notification:test --no-daemon --console=plain
  ```

  Expected: 기존 baseline 대비 새 failure 0, 전체 test PASS.

- [ ] **Step 3: module build와 dependency verification을 실행한다.**

  ```bash
  ./gradlew :appointment-notification:build --no-daemon --console=plain
  ./gradlew dependencyInsight --dependency bluetape4k-leader-micrometer --configuration testRuntimeClasspath --no-daemon --console=plain
  ```

  Expected: compile/test/checkstyle류 task와 dependency verifier가 PASS, resolved version 0.5.0.

- [ ] **Step 4: spec/plan traceability와 7-tier review를 기록한다.**

  `docs/reviews/2026-08-10-issue-254-implementation-review.md`에 성능·안정성·보안·운영·개발자/API·사용자/호출자·통합 관점의 최신 diff 증거를 표로 남긴다. P0/P1은 0이어야 하며, P2/P3는 수정 또는 후속 issue 근거를 적는다. `git diff --check`와 Kotlin pattern checklist의 cancellation/null safety/constructor compatibility 항목을 함께 기록한다.

## Task 5: 문서·lesson·workflow evidence와 issue 갱신

- [ ] **Step 0: 모듈 README의 운영 계약을 갱신한다.**

  `README.md`와 `README.ko.md`의 기존 “Redis 리더 선출은 향후 리마인더 복구 trigger에만 사용” 문장을 현재 계약에 맞게 고치고, 다음을 추가한다: reminder recovery scan 한 tick은 leader action 안에서 실행되며, Redis가 없으면 single-instance direct path를 사용하고, `MeterRegistry`가 있으면 `shedlock.leader.acquired`, `shedlock.leader.not_acquired`, `shedlock.leader.duration`, `shedlock.leader.active`를 `lock.name=redacted-lock` 기본값으로 관측한다. outbox 발송 정확성은 DB lease/fencing이 계속 소유한다.

- [ ] **Step 1: lesson을 작성한다.**

  `docs/lessons/2026-08-10-issue-254-leader-micrometer.md`에 문제, 선택한 공식 decorator, Boolean guard에서 action boundary로 바꾼 이유, cancellation/active gauge 검증, 남은 Redis 실환경 gap과 향후 guard를 한국어로 기록한다.

- [ ] **Step 2: diff/documentation 검증을 실행한다.**

  ```bash
  git diff --check
  git status --short
  rg -n "ReminderRecoveryTriggerGuard|bluetape4k-leader-micrometer|REMINDER_RECOVERY_LOCK_NAME" appointment-notification gradle/libs.versions.toml docs
  ```

  Expected: auto-configuration production path의 guard provider reference 0,
  deprecated compatibility declaration과 alias/lock/documentation anchor가
  모두 존재한다.

- [ ] **Step 3: workflow component checks와 lane completion을 기록한다.**

  appointment-notification test, workflow review, diff-check 각각의 실제 명령 결과를 `.bluetape` evidence JSON으로 남기고 `check-result`/`component-evidence`/`completion-check`를 순서대로 실행한다. main lane은 변경 파일 목록을 포함해 complete 처리한다.

- [ ] **Step 4: issue #254에 Korean 구현 증거 댓글을 남긴다.**

  PR/merge 없이도 `gh issue comment 254 --repo bluetape4k/clinic-appointment --body-file <한국어 증거>`로 변경 파일, 테스트 결과, resolved artifact version, production/CI/push 미실행 경계를 명시한다. 댓글 전후 live issue state를 확인한다.

- [ ] **Step 5: no-PR DoD를 보고한다.**

  local branch/worktree/commit, tests, docs, workflow receipts, known gaps를 정리한다. PR creation, CI, production Redis, merge는 사용자 권한/외부 상태가 필요한 N/A 또는 PENDING으로 남기고 완료라고 주장하지 않는다.

## 검증 순서와 stop condition

의존성 resolve → targeted RED/GREEN → auto-config test → full module test/build → diff/docs/review → workflow receipt → issue evidence 순서로 실행한다. 실패 시 해당 Task로 돌아가 원인을 확인한 뒤 처음부터 재실행한다. 최종 stop condition은 모듈 테스트/build와 review의 P0/P1=0, workflow component completion PASS, issue evidence 갱신, 그리고 외부 delivery를 제외한 no-PR DoD가 fresh output으로 증명된 상태다.
