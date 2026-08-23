package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
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
                check(context.getBeansOfType(LeaderAopMetricsRecorder::class.java).size == 1)
                check(context.getBeansOfType(LeaderElectionListener::class.java).size == 1)
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
