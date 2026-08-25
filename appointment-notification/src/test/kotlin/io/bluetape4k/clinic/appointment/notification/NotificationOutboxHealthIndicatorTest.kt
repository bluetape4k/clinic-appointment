package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

internal class NotificationOutboxHealthIndicatorTest {

    @Test
    fun `schema claim key ring 실패는 readiness DOWN으로 분리한다`() {
        val indicator = NotificationOutboxHealthIndicator(
            readinessSource = FixedReadinessSource(
                NotificationOutboxReadinessSnapshot(
                    schema = NotificationComponentState.down("SCHEMA_UNAVAILABLE"),
                    claim = NotificationComponentState.up(),
                    keyRing = NotificationComponentState.down("HMAC_KEY_UNAVAILABLE"),
                ),
            ),
            livenessSource = FixedLivenessSource(),
        )

        val readiness = indicator.readiness()

        readiness.status shouldBeEqualTo NotificationOutboxHealthStatus.DOWN
        readiness.details shouldBeEqualTo mapOf(
            "schema" to "SCHEMA_UNAVAILABLE",
            "claim" to "UP",
            "keyRing" to "HMAC_KEY_UNAVAILABLE",
            "failedComponents" to 2,
            "diagnostics" to emptyList<NotificationReadinessDiagnostic>(),
        )
    }

    @Test
    fun `readiness diagnostic은 operation target code error class retryable로 health에 전달된다`() {
        val diagnostic = NotificationReadinessDiagnostic(
            operation = "schema.table",
            target = "scheduling_notification_outbox",
            code = "SCHEMA_PERMISSION_DENIED",
            errorClass = "SQLInvalidAuthorizationSpecException",
            retryable = false,
        )
        val indicator = NotificationOutboxHealthIndicator(
            readinessSource = FixedReadinessSource(
                NotificationOutboxReadinessSnapshot(
                    schema = NotificationComponentState.down(diagnostic.code),
                    claim = NotificationComponentState.up(),
                    keyRing = NotificationComponentState.up(),
                    diagnostics = listOf(diagnostic),
                ),
            ),
            livenessSource = FixedLivenessSource(),
        )

        indicator.readiness().details["diagnostics"] shouldBeEqualTo listOf(diagnostic.toHealthDetail())
    }

    @Test
    fun `provider와 member circuit backlog retention 실패는 liveness UP과 degraded detail로만 남긴다`() {
        val indicator = NotificationOutboxHealthIndicator(
            readinessSource = FixedReadinessSource(NotificationOutboxReadinessSnapshot.up()),
            livenessSource = FixedLivenessSource(
                NotificationOutboxLivenessSnapshot(
                    providerCircuitOpen = 2,
                    memberCircuitOpen = 1,
                    oldestActiveAge = Duration.ofMinutes(31),
                    retentionFailures = 1,
                    backlogCapped = true,
                ),
            ),
        )

        val liveness = indicator.liveness()

        liveness.status shouldBeEqualTo NotificationOutboxHealthStatus.UP
        liveness.details shouldBeEqualTo mapOf(
            "degraded" to true,
            "providerCircuitOpen" to 2,
            "memberCircuitOpen" to 1,
            "oldestActiveAgeSeconds" to 1_860L,
            "retentionFailures" to 1,
            "backlogCapped" to true,
        )
    }

    @Test
    fun `health detail은 안정적인 code와 count만 반환하고 raw id를 포함하지 않는다`() {
        val indicator = NotificationOutboxHealthIndicator(
            readinessSource = FixedReadinessSource(NotificationOutboxReadinessSnapshot.up()),
            livenessSource = FixedLivenessSource(
                NotificationOutboxLivenessSnapshot(
                    providerCircuitOpen = 1,
                    memberCircuitOpen = 1,
                    oldestActiveAge = Duration.ofMinutes(6),
                    retentionFailures = 1,
                ),
            ),
        )

        val details = indicator.liveness().details

        details.keys.intersect(
            setOf("tenantId", "clinicId", "memberId", "appointmentId", "outboxId", "rawId"),
        ) shouldBeEqualTo emptySet()
    }

    @Test
    fun `oldest active age가 warning 경계 이하면 liveness를 degraded로 표시하지 않는다`() {
        val indicator = NotificationOutboxHealthIndicator(
            readinessSource = FixedReadinessSource(NotificationOutboxReadinessSnapshot.up()),
            livenessSource = FixedLivenessSource(
                NotificationOutboxLivenessSnapshot(oldestActiveAge = Duration.ofMinutes(5)),
            ),
        )

        indicator.liveness().details["degraded"] shouldBeEqualTo false
    }

    @Test
    fun `runtime health signal은 circuit과 연속 retention 실패를 기본 liveness snapshot에 반영한다`() {
        val signals = NotificationRuntimeHealthSignals()
        signals.setProviderCircuitOpen(true)
        signals.setMemberCircuitOpen(true)
        signals.recordRetentionFailure()
        signals.recordRetentionFailure()

        signals.snapshot(Duration.ofMinutes(7), backlogCapped = true) shouldBeEqualTo NotificationOutboxLivenessSnapshot(
            providerCircuitOpen = 1,
            memberCircuitOpen = 1,
            oldestActiveAge = Duration.ofMinutes(7),
            retentionFailures = 2,
            backlogCapped = true,
        )

        signals.recordRetentionSuccess()
        signals.snapshot(null).retentionFailures shouldBeEqualTo 0
    }

    private class FixedReadinessSource(
        private val snapshot: NotificationOutboxReadinessSnapshot,
    ) : NotificationOutboxReadinessSource {
        override fun snapshot(): NotificationOutboxReadinessSnapshot = snapshot
    }

    private class FixedLivenessSource(
        private val snapshot: NotificationOutboxLivenessSnapshot = NotificationOutboxLivenessSnapshot(),
    ) : NotificationOutboxLivenessSource {
        override fun snapshot(): NotificationOutboxLivenessSnapshot = snapshot
    }
}
