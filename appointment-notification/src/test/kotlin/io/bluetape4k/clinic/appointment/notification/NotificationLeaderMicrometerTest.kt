package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.bluetape4k.leader.spring.metrics.LeaderMicrometerAutoConfiguration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.time.Duration.Companion.milliseconds

internal class NotificationLeaderMicrometerTest {

    @Test
    fun `upstream AOP recorder는 reminder lock callback을 leader aop metric으로 기록하고 lock tag를 redacted 한다`() {
        runner()
            .withBean("meterRegistry", SimpleMeterRegistry::class.java, ::SimpleMeterRegistry)
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                val recorder = applicationContext.getBean(MicrometerLeaderAopMetricsRecorder::class.java)
                val registry = applicationContext.getBean(SimpleMeterRegistry::class.java)
                val options = LeaderElectionOptions.Default

                recorder.onLockAttempt(REMINDER_RECOVERY_LOCK_NAME, options)
                recorder.onLockAcquired(REMINDER_RECOVERY_LOCK_NAME, options, 5.milliseconds)
                recorder.onTaskStarted(REMINDER_RECOVERY_LOCK_NAME)
                recorder.onTaskFinished(REMINDER_RECOVERY_LOCK_NAME, 100.milliseconds)

                registry.get("leader.aop.attempts")
                    .tag("lock.name", "redacted-lock")
                    .counter()
                    .count() shouldBeEqualTo 1.0
                registry.get("leader.aop.acquired")
                    .tag("lock.name", "redacted-lock")
                    .counter()
                    .count() shouldBeEqualTo 1.0
                registry.get("leader.aop.execution.duration")
                    .tag("lock.name", "redacted-lock")
                    .timer()
                    .count() shouldBeEqualTo 1L
                registry.find("leader.aop.active")
                    .tag("lock.name", "redacted-lock")
                    .gauge()!!
                    .value() shouldBeEqualTo 0.0
                check(registry.meters.none { it.id.name.startsWith("shedlock.leader.") }) {
                    "legacy shedlock leader metrics must not be registered"
                }
            }
    }

    @Test
    fun `upstream AOP recorder는 contention과 backend failure를 reason별로 기록한다`() {
        runner()
            .withBean("meterRegistry", SimpleMeterRegistry::class.java, ::SimpleMeterRegistry)
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                val recorder = applicationContext.getBean(MicrometerLeaderAopMetricsRecorder::class.java)
                val registry = applicationContext.getBean(SimpleMeterRegistry::class.java)
                val options = LeaderElectionOptions.Default

                recorder.onLockNotAcquired(REMINDER_RECOVERY_LOCK_NAME, options, SkipReason.CONTENTION)
                recorder.onLockNotAcquired(REMINDER_RECOVERY_LOCK_NAME, options, SkipReason.BACKEND_ERROR)

                registry.get("leader.aop.lock.not.acquired")
                    .tag("lock.name", "redacted-lock")
                    .tag("reason", SkipReason.CONTENTION.name)
                    .counter()
                    .count() shouldBeEqualTo 1.0
                registry.get("leader.aop.lock.not.acquired")
                    .tag("lock.name", "redacted-lock")
                    .tag("reason", SkipReason.BACKEND_ERROR.name)
                    .counter()
                    .count() shouldBeEqualTo 1.0
            }
    }

    @Test
    fun `upstream AOP recorder는 task failure와 active gauge 정리를 기록한다`() {
        runner()
            .withBean("meterRegistry", SimpleMeterRegistry::class.java, ::SimpleMeterRegistry)
            .run { applicationContext ->
                applicationContext.startupFailure shouldBeEqualTo null
                val recorder = applicationContext.getBean(MicrometerLeaderAopMetricsRecorder::class.java)
                val registry = applicationContext.getBean(SimpleMeterRegistry::class.java)
                val options = LeaderElectionOptions.Default

                recorder.onLockAttempt(REMINDER_RECOVERY_LOCK_NAME, options)
                recorder.onTaskStarted(REMINDER_RECOVERY_LOCK_NAME)
                recorder.onTaskFailed(
                    REMINDER_RECOVERY_LOCK_NAME,
                    25.milliseconds,
                    IllegalStateException("scan failed"),
                )

                registry.get("leader.aop.task.failed")
                    .tag("lock.name", "redacted-lock")
                    .tag("exception", "IllegalStateException")
                    .counter()
                    .count() shouldBeEqualTo 1.0
                registry.find("leader.aop.active")
                    .tag("lock.name", "redacted-lock")
                    .gauge()!!
                    .value() shouldBeEqualTo 0.0
            }
    }

    private fun runner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    LeaderAopFactoryAutoConfiguration::class.java,
                    LeaderMicrometerAutoConfiguration::class.java,
                    LeaderAopAutoConfiguration::class.java,
                ),
            )
}
