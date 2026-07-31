package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.notification.NotificationComponentState
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxHealthIndicator
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxLivenessSnapshot
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxLivenessSource
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxReadinessSnapshot
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxReadinessSource
import org.junit.jupiter.api.Test

internal class NotificationHealthConfigurationTest {

    @Test
    fun `Actuator health는 readiness down을 반영하고 privacy-safe 집계만 노출한다`() {
        val indicator = NotificationOutboxHealthIndicator(
            readinessSource = NotificationOutboxReadinessSource {
                NotificationOutboxReadinessSnapshot(
                    schema = NotificationComponentState.down("SCHEMA_MISSING"),
                    claim = NotificationComponentState.up(),
                    keyRing = NotificationComponentState.up(),
                )
            },
            livenessSource = NotificationOutboxLivenessSource {
                NotificationOutboxLivenessSnapshot(backlogCapped = true)
            },
        )

        val health = requireNotNull(
            NotificationHealthConfiguration()
                .notificationOutboxActuatorHealth(indicator)
                .health(),
        )

        health.status.code shouldBeEqualTo "DOWN"
        health.details.keys shouldBeEqualTo setOf("readiness", "liveness")
        health.details.toString().contains("backlogCapped=true") shouldBeEqualTo true
        health.details.keys.intersect(
            setOf("tenantId", "clinicId", "memberId", "appointmentId", "destination"),
        ).isEmpty() shouldBeEqualTo true
    }
}
