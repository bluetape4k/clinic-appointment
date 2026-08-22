package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.notification.NotificationComponentState
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxHealthIndicator
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxLivenessSnapshot
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxLivenessSource
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxReadinessSnapshot
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxReadinessSource
import io.bluetape4k.clinic.appointment.notification.NotificationLeaderHealthSnapshot
import io.bluetape4k.clinic.appointment.notification.NotificationLeaderHealthSource
import io.bluetape4k.clinic.appointment.notification.NotificationLeaderHealthStatus
import org.junit.jupiter.api.Test
import java.time.Instant

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

    @Test
    fun `leader health는 readiness group에 추가할 수 있는 low cardinality 상태만 노출한다`() {
        val source = NotificationLeaderHealthSource {
            NotificationLeaderHealthSnapshot(
                status = NotificationLeaderHealthStatus.DEGRADED,
                backendAvailable = true,
                leaderPresent = true,
                leaseAtRisk = true,
                lastAcquiredAt = Instant.parse("2026-08-22T00:00:00Z"),
                lastAcquisitionFailureAt = Instant.parse("2026-08-21T23:59:00Z"),
                recentAcquisitionFailures = 1,
                failureWindowSeconds = 300,
                leaseRiskWindowSeconds = 30,
                leaseUntil = Instant.parse("2026-08-22T00:00:10Z"),
            )
        }

        val health = requireNotNull(
            NotificationHealthConfiguration()
                .notificationLeaderActuatorHealth(source)
                .health(),
        )

        health.status.code shouldBeEqualTo "DEGRADED"
        health.details["leaderPresent"] shouldBeEqualTo true
        health.details["leaseAtRisk"] shouldBeEqualTo true
        health.details["recentAcquisitionFailures"] shouldBeEqualTo 1
        health.details.keys.intersect(
            setOf("lockName", "tenantId", "requestId", "nodeId", "payload"),
        ).isEmpty() shouldBeEqualTo true
    }
}
