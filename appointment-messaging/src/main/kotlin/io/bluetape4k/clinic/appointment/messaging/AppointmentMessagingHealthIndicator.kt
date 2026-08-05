package io.bluetape4k.clinic.appointment.messaging

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * 메시징 readiness를 Actuator에 bounded 상태로 노출한다.
 *
 * broker outage는 애플리케이션 liveness를 끊지 않지만 relay readiness를 `OUT_OF_SERVICE`로
 * 표시한다. schema/serializer/configuration 오류는 durable intent 경로도 안전하지 않으므로
 * `DOWN`으로 표시한다. 상세 값에는 tenant, clinic, appointment, topic, credential을 넣지 않는다.
 */
class AppointmentMessagingHealthIndicator(
    private val readiness: AppointmentMessagingReadinessProbe,
) : HealthIndicator {
    override fun health(): Health {
        val state = readiness.snapshot()
        val builder = when {
            !state.enabled -> Health.outOfService()
            !state.configurationValid || !state.schemaValid || !state.serializerValid -> Health.down()
            !state.brokerAvailable || state.relayPaused || state.relayHeld -> Health.outOfService()
            else -> Health.up()
        }
        return builder
            .withDetail("enabled", state.enabled)
            .withDetail("configurationValid", state.configurationValid)
            .withDetail("brokerAvailable", state.brokerAvailable)
            .withDetail("relayPaused", state.relayPaused)
            .withDetail("relayHeld", state.relayHeld)
            .withDetail("schemaValid", state.schemaValid)
            .withDetail("serializerValid", state.serializerValid)
            .build()
    }
}
