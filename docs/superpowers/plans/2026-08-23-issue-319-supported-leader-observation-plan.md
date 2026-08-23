# 지원되는 리더 수명주기 관측 구현 계획

> **에이전트 작업자용:** 이 계획은 `executing-plans` 절차로 작업별 체크포인트를 두고 실행한다. 각 단계는 체크박스로 추적한다.

**목표:** `bluetape4k-leader 0.5.0`이 제공하는 acquire·execute·skip·revoke callback만 notification 전용 저카디널리티 Observation으로 연결한다.

**아키텍처:** `NotificationLeaderObservationBridge`가 `LeaderAopMetricsRecorder`와 `LeaderElectionListener`를 함께 구현한다. AOP callback으로 acquire·skip·execute를 한 번씩 기록하고 listener의 revoke callback을 Spring listener registry에서 받는다. ObservationRegistry가 없으면 bridge bean을 만들지 않으며, 관측 handler 오류는 scheduler 실행과 원래 예외에 영향을 주지 않는다.

**기술 스택:** Kotlin 2.3, Spring Boot 4, `bluetape4k-leader 0.5.0`, Micrometer Observation, JUnit 5, Kluent/bluetape assertions, Gradle module test.

---

## 파일 구조

- 생성: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderObservationBridge.kt`
  - reminder lock 전용 callback 매핑, 고정 observation 이름·태그, 관측 오류 격리를 담당한다.
- 수정: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`
  - `ObservationRegistry`가 있을 때만 bridge를 선택적으로 등록한다.
- 생성: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderObservationBridgeTest.kt`
  - callback 매핑, tag safety, 다른 lock 무시, no-op, handler 오류 격리를 검증한다.
- 생성: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderObservationConfigurationTest.kt`
  - Spring bean 조건과 host replacement 조건을 검증한다.
- 참조: `docs/superpowers/specs/2026-08-23-issue-319-supported-leader-observation-design.md`
  - 구현 중 범위·비범위·용어 기준을 변경하지 않는다.

## Task 1: bridge의 실패 테스트 작성

**Files:**

- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderObservationBridgeTest.kt`

- [x] **Step 1: Observation handler가 수집할 테스트 fixture를 작성한다.**

```kotlin
package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.SkipReason
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test

internal class NotificationLeaderObservationBridgeTest {

    @Test
    fun `지원 callback은 reminder lifecycle observation을 순서대로 기록한다`() {
        val records = mutableListOf<RecordedObservation>()
        val bridge = NotificationLeaderObservationBridge(registry(records))
        val options = LeaderElectionOptions.Default

        bridge.onLockAcquired(REMINDER_RECOVERY_LOCK_NAME, options, 5.milliseconds)
        bridge.onTaskFinished(REMINDER_RECOVERY_LOCK_NAME, 20.milliseconds)
        bridge.onLockNotAcquired(REMINDER_RECOVERY_LOCK_NAME, options, SkipReason.CONTENTION)
        bridge.onRevoked(REMINDER_RECOVERY_LOCK_NAME)

        records.map { it.operation to it.outcome } shouldBeEqualTo listOf(
            "acquire" to "acquired",
            "execute" to "success",
            "skip" to "skipped",
            "revoke" to "revoked",
        )
        records.forEach { record ->
            record.name shouldBeEqualTo "clinic.notification.leader.lifecycle"
            record.lowCardinality.keys shouldBeEqualTo setOf("lock", "operation", "outcome")
            record.lowCardinality["lock"] shouldBeEqualTo "reminder"
            check(record.highCardinality.isEmpty())
        }
    }

    @Test
    fun `task failure는 error와 cancellation을 구분하지만 예외 정보를 tag로 만들지 않는다`() {
        val records = mutableListOf<RecordedObservation>()
        val bridge = NotificationLeaderObservationBridge(registry(records))
        val options = LeaderElectionOptions.Default

        bridge.onTaskFailed(
            REMINDER_RECOVERY_LOCK_NAME,
            10.milliseconds,
            IllegalStateException("scan failed for tenant-42"),
        )
        bridge.onTaskFailed(
            REMINDER_RECOVERY_LOCK_NAME,
            10.milliseconds,
            CancellationException("cancelled"),
        )

        records.map { it.outcome } shouldBeEqualTo listOf("error", "cancelled")
        records.forEach { record ->
            check(record.lowCardinality.keys.none { key ->
                key.contains("tenant", ignoreCase = true) ||
                    key.contains("exception", ignoreCase = true) ||
                    key.contains("request", ignoreCase = true)
            })
        }
    }

    @Test
    fun `다른 lock callback은 관측하지 않는다`() {
        val records = mutableListOf<RecordedObservation>()
        val bridge = NotificationLeaderObservationBridge(registry(records))
        val options = LeaderElectionOptions.Default

        bridge.onLockAcquired("other-lock", options, 1.milliseconds)
        bridge.onTaskFinished("other-lock", 1.milliseconds)
        bridge.onLockNotAcquired("other-lock", options, SkipReason.BACKEND_ERROR)
        bridge.onRevoked("other-lock")

        check(records.isEmpty())
    }

    @Test
    fun `NOOP registry는 callback을 업무 실패로 바꾸지 않는다`() {
        val bridge = NotificationLeaderObservationBridge(ObservationRegistry.NOOP)
        val options = LeaderElectionOptions.Default

        bridge.onLockAcquired(REMINDER_RECOVERY_LOCK_NAME, options, 1.milliseconds)
        bridge.onTaskFinished(REMINDER_RECOVERY_LOCK_NAME, 1.milliseconds)
        bridge.onLockNotAcquired(REMINDER_RECOVERY_LOCK_NAME, options, SkipReason.CONTENTION)
        bridge.onTaskFailed(REMINDER_RECOVERY_LOCK_NAME, 1.milliseconds, IllegalStateException("ignored"))
        bridge.onRevoked(REMINDER_RECOVERY_LOCK_NAME)
    }

    @Test
    fun `observation handler 오류는 callback 밖으로 전파되지 않는다`() {
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(
            object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context): Boolean = true

                override fun onStop(context: Observation.Context) {
                    error("telemetry handler failed")
                }
            },
        )
        val bridge = NotificationLeaderObservationBridge(registry)
        val options = LeaderElectionOptions.Default

        bridge.onLockAcquired(REMINDER_RECOVERY_LOCK_NAME, options, 1.milliseconds)
    }

    private fun registry(records: MutableList<RecordedObservation>): ObservationRegistry =
        ObservationRegistry.create().also { registry ->
            registry.observationConfig().observationHandler(
                object : ObservationHandler<Observation.Context> {
                    override fun supportsContext(context: Observation.Context): Boolean = true

                    override fun onStop(context: Observation.Context) {
                        val low = context.lowCardinalityKeyValues.associate { it.key to it.value }
                        val high = context.highCardinalityKeyValues.associate { it.key to it.value }
                        records += RecordedObservation(
                            name = context.name,
                            operation = low.getValue("operation"),
                            outcome = low.getValue("outcome"),
                            lowCardinality = low,
                            highCardinality = high,
                        )
                    }
                },
            )
        }

    private data class RecordedObservation(
        val name: String,
        val operation: String,
        val outcome: String,
        val lowCardinality: Map<String, String>,
        val highCardinality: Map<String, String>,
    )
}
```

- [x] **Step 2: 실패하는지 확인한다.**

실행:

```bash
./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationLeaderObservationBridgeTest"
```

예상 결과: `NotificationLeaderObservationBridge`가 아직 없어서 compile 단계가 실패한다. 이 실패는 구현 전 테스트가 실제 계약을 고정했음을 확인하는 증거다.

## Task 2: 최소 bridge 구현

**Files:**

- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderObservationBridge.kt`

- [x] **Step 1: 고정 lifecycle와 callback 매핑을 구현한다.**

```kotlin
package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.CancellationException
import kotlin.time.Duration

/** 0.5.0에서 실제로 제공되는 reminder leader callback만 notification Observation으로 연결합니다. */
internal class NotificationLeaderObservationBridge(
    private val registry: ObservationRegistry,
    private val lockName: String = REMINDER_RECOVERY_LOCK_NAME,
) : LeaderAopMetricsRecorder, LeaderElectionListener {

    override fun onLockAcquired(
        name: String,
        options: LeaderElectionOptions,
        acquireElapsed: Duration,
    ) {
        observe(name, Operation.ACQUIRE, Outcome.ACQUIRED)
    }

    override fun onLockNotAcquired(
        name: String,
        options: LeaderElectionOptions,
        reason: SkipReason,
    ) {
        observe(name, Operation.SKIP, Outcome.SKIPPED)
    }

    override fun onTaskFinished(name: String, executionTime: Duration) {
        observe(name, Operation.EXECUTE, Outcome.SUCCESS)
    }

    override fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {
        val outcome = if (throwable is CancellationException) Outcome.CANCELLED else Outcome.ERROR
        observe(name, Operation.EXECUTE, outcome, throwable)
    }

    override fun onRevoked(lockName: String) {
        observe(lockName, Operation.REVOKE, Outcome.REVOKED)
    }

    private fun observe(
        name: String,
        operation: Operation,
        outcome: Outcome,
        error: Throwable? = null,
    ) {
        if (name != lockName || registry.isNoop) return

        runCatching {
            var observation = Observation.createNotStarted(OBSERVATION_NAME, registry)
                .lowCardinalityKeyValue(TAG_LOCK, LOCK_VALUE)
                .lowCardinalityKeyValue(TAG_OPERATION, operation.value)
                .lowCardinalityKeyValue(TAG_OUTCOME, outcome.value)
            if (error != null && error !is CancellationException) {
                observation = observation.error(error)
            }
            observation.start().stop()
        }.onFailure { failure ->
            log.warn(failure) {
                "notification leader observation failed: operation=${operation.value}, outcome=${outcome.value}"
            }
        }
    }

    private enum class Operation(val value: String) {
        ACQUIRE("acquire"),
        EXECUTE("execute"),
        SKIP("skip"),
        REVOKE("revoke"),
    }

    private enum class Outcome(val value: String) {
        ACQUIRED("acquired"),
        SUCCESS("success"),
        ERROR("error"),
        CANCELLED("cancelled"),
        SKIPPED("skipped"),
        REVOKED("revoked"),
    }

    private companion object : KLogging() {
        const val OBSERVATION_NAME = "clinic.notification.leader.lifecycle"
        const val TAG_LOCK = "lock"
        const val TAG_OPERATION = "operation"
        const val TAG_OUTCOME = "outcome"
        const val LOCK_VALUE = "reminder"
    }
}
```

- [x] **Step 2: bridge 단위 테스트를 통과시킨다.**

실행:

```bash
./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationLeaderObservationBridgeTest"
```

예상 결과: 5개 테스트가 PASS한다. 특히 handler가 던진 예외가 테스트 밖으로 전파되지 않고, `tenant-42`나 예외 class가 tag에 들어가지 않아야 한다.

## Task 3: Spring auto-configuration에 선택적 bridge 등록

**Files:**

- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`

- [x] **Step 1: observation imports와 조건부 bean을 추가한다.**

기존 `NotificationLeaderAopMetricsRecorder` bean 뒤에 다음을 추가한다.

```kotlin
import io.micrometer.observation.ObservationRegistry
```

```kotlin
    /** ObservationRegistry가 있을 때 지원되는 reminder leader lifecycle만 관측합니다. */
    @Bean
    @ConditionalOnBean(ObservationRegistry::class)
    @ConditionalOnMissingBean(NotificationLeaderObservationBridge::class)
    internal fun notificationLeaderObservationBridge(
        observationRegistry: ObservationRegistry,
    ): NotificationLeaderObservationBridge =
        NotificationLeaderObservationBridge(observationRegistry)
```

`NotificationLeaderObservationBridge`는 `LeaderAopMetricsRecorder`와
`LeaderElectionListener` 타입을 동시에 노출하므로 기존 AOP aspect와
`LeaderElectionListenerRegistry` 자동 등록 경계가 각각 같은 bean을 사용한다.
`onElected`·`onSkipped`는 구현하지 않아 중복 observation을 만들지 않는다.

- [x] **Step 2: 기존 auto-configuration import와 bean 순서를 확인한다.**

실행:

```bash
./gradlew :appointment-notification:compileKotlin
```

예상 결과: `NotificationAutoConfiguration`이 성공적으로 compile되고, 새 bridge가 `ObservationRegistry`를 직접 참조한다.

## Task 4: Spring bean 조건과 replacement 검증

**Files:**

- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderObservationConfigurationTest.kt`

- [x] **Step 1: registry 유무와 host replacement 테스트를 작성한다.**

```kotlin
package io.bluetape4k.clinic.appointment.notification

import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class NotificationLeaderObservationConfigurationTest {

    @Test
    fun `ObservationRegistry가 있으면 notification bridge를 등록한다`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
            .withBean("observationRegistry", ObservationRegistry::class.java, { ObservationRegistry.create() })
            .run { context ->
                check(context.startupFailure == null) { "startup failure: ${context.startupFailure}" }
                check(context.getBeansOfType(NotificationLeaderObservationBridge::class.java).size == 1)
            }
    }

    @Test
    fun `ObservationRegistry가 없으면 notification bridge를 등록하지 않는다`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
            .run { context ->
                check(context.startupFailure == null) { "startup failure: ${context.startupFailure}" }
                check(context.getBeansOfType(NotificationLeaderObservationBridge::class.java).isEmpty())
            }
    }

    @Test
    fun `host가 bridge를 제공하면 기본 bean을 대체한다`() {
        val hostBridge = NotificationLeaderObservationBridge(ObservationRegistry.NOOP)
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
            .withBean("observationRegistry", ObservationRegistry::class.java, { ObservationRegistry.create() })
            .withBean("hostBridge", NotificationLeaderObservationBridge::class.java, { hostBridge })
            .run { context ->
                check(context.startupFailure == null) { "startup failure: ${context.startupFailure}" }
                check(context.getBean(NotificationLeaderObservationBridge::class.java) === hostBridge)
            }
    }
}
```

- [x] **Step 2: 조건 테스트를 실행한다.**

실행:

```bash
./gradlew :appointment-notification:test --tests "io.bluetape4k.clinic.appointment.notification.NotificationLeaderObservationConfigurationTest"
```

예상 결과: 세 테스트가 PASS한다. registry가 없는 경우 기존 notification context는 시작 실패 없이 bridge만 빠져야 한다.

## Task 5: 전체 모듈 회귀와 문서 검증

**Files:**

- Verify: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderMicrometerTest.kt`
- Verify: `docs/superpowers/specs/2026-08-23-issue-319-supported-leader-observation-design.md`

- [x] **Step 1: notification 모듈 테스트를 실행한다.**

실행:

```bash
./gradlew :appointment-notification:test
```

예상 결과: `BUILD SUCCESSFUL`; 기존 `leader.aop.*` metric, `redacted-lock` tag, `shedlock.leader.*` 부재 테스트가 계속 PASS한다.

- [x] **Step 2: compile와 diff를 검증한다.**

실행:

```bash
./gradlew :appointment-notification:build
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/specs/2026-08-23-issue-319-supported-leader-observation-design.md \
  docs/superpowers/plans/2026-08-23-issue-319-supported-leader-observation-plan.md
```

예상 결과: build, diff check, Korean terminology audit가 모두 성공한다.

- [x] **Step 3: capability boundary와 작업 상태를 확인한다.**

실행:

```bash
git grep -n -E 'lease-extension|ownership-loss|LeaderLeaseExtensionObserver' -- \
  appointment-notification/src/main docs/superpowers/specs docs/superpowers/plans
git status --short --branch
```

예상 결과: `extend`·`ownership-loss`는 설계/계획의 후속 경계로만 나타나고, 구현 코드에는 observer나 추정 로직이 없다. 작업 tree에는 의도한 두 Kotlin 파일과 두 테스트 파일만 추가/수정된다.

## Task 6: 구현 커밋과 workflow 증거 기록

- [x] **Step 1: 변경을 Lore 형식으로 커밋한다.**

```bash
git add appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderObservationBridge.kt \
  appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt \
  appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderObservationBridgeTest.kt \
  appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationLeaderObservationConfigurationTest.kt
git commit -m "지원되는 리더 lifecycle 관측을 notification에 연결한다" -m "0.5.0 callback으로 reminder의 acquire, execute, skip, revoke 결과만 저카디널리티 Observation에 연결하고 미래 lease-extension 경계는 추정하지 않는다.\n\nConstraint: clinic-appointment는 bluetape4k-leader 0.5.0에 고정되어 lease-extension observer가 없다.\nRejected: upstream metric 이름 재사용과 reflection 기반 extension 탐색 | 중복 의미와 버전 의존을 피한다.\nConfidence: high\nScope-risk: narrow\nDirective: upstream release capability가 준비되기 전에는 extend와 ownership-loss 관측을 추가하지 않는다.\nTested: appointment-notification test/build, git diff --check, Korean terminology audit\nNot-tested: 실제 Redis lease-extension 및 ownership-loss는 지원 API 부재로 검증하지 않음"
```

- [x] **Step 2: 새 Type A run에 구현·검증 evidence를 연결하고 completion check를 실행한다.**

workflow state root는 `.bluetape`, run id는 `20260823T014254Z-da312f3a`, owner file은 `.bluetape/handles/issue-319-supported-owner.json`이다. 구현과 검증이 끝난 뒤 evidence JSON에는 다음 사실만 기록한다.

```json
[
  {"kind": "implementation", "summary": "notification leader lifecycle bridge와 조건부 Spring bean을 구현함"},
  {"kind": "verification", "summary": "bridge/configuration 단위 테스트와 appointment-notification 모듈 build가 성공함"},
  {"kind": "capability-boundary", "summary": "extend와 ownership-loss는 0.5.0 API 부재로 후속 범위에 남김"}
]
```

`bluetape-flow.py component-evidence`, `completion-check`, `complete`는 fresh test output과 git HEAD를 확인한 뒤 순서대로 실행한다. 원래 capability-blocked run `20260823T012207Z-2e48d1d9`는 재사용하지 않는다.

## 계획 자체 검토

- **Spec coverage:** 네 지원 lifecycle은 Task 1·2·3에, 저카디널리티·raw 식별자 제외는 Task 1·2에, optional registry와 replacement는 Task 3·4에, 기존 metric 회귀와 문서 검증은 Task 5에, 후속 capability 경계는 Task 5·6에 연결했다.
- **Placeholder scan:** 미완성 표식이나 추상적인 작업 지시를 사용하지 않았고 모든 작업에 실제 경로·코드·명령·예상 결과를 넣었다.
- **Type consistency:** `NotificationLeaderObservationBridge`의 생성자와 callback signature를 테스트·구현·Spring bean에서 동일하게 사용한다. `Operation`, `Outcome`, observation 이름과 세 tag key는 테스트와 구현에서 동일하다.
- **Known limitation:** task 실행 중간에 시작하는 long-lived span은 만들지 않는다. 0.5.0 AOP recorder가 제공하는 terminal callback과 기존 upstream duration metric을 사용하며, 이번 범위는 lifecycle 결과 관측에 한정한다.
