package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import org.junit.jupiter.api.Test
import java.time.Duration

internal class NotificationOutboxAlertPolicyTest {

    @Test
    fun `oldest exhausted provider unknown lease pending 기준은 warning critical ticket과 해제 조건을 구분한다`() {
        val policy = NotificationOutboxAlertPolicy()

        policy.evaluate(
            NotificationOutboxAlertSample(
                oldestActiveAge = Duration.ofMinutes(6),
                oldestBreachDuration = Duration.ofMinutes(10),
                exhaustedInFiveMinutes = 1,
                providerAttempts = 100,
                providerFailures = 20,
                providerFailureBreachDuration = Duration.ofMinutes(5),
                unknownInFiveMinutes = 1,
                leaseRecoveries = 6,
                deliveryAttempts = 100,
                leaseRecoveryBreachDuration = Duration.ofMinutes(10),
                pendingBacklog = 10_001,
                pendingIncreasingDuration = Duration.ofMinutes(10),
            ),
        ).map { it.signal to it.severity }.toSet() shouldBeEqualTo setOf(
            NotificationOutboxAlertSignal.OLDEST_ACTIVE_AGE to NotificationOutboxAlertSeverity.WARNING,
            NotificationOutboxAlertSignal.EXHAUSTED to NotificationOutboxAlertSeverity.TICKET,
            NotificationOutboxAlertSignal.PROVIDER_FAILURE_RATIO to NotificationOutboxAlertSeverity.WARNING,
            NotificationOutboxAlertSignal.DELIVERY_RESULT_UNKNOWN to NotificationOutboxAlertSeverity.WARNING,
            NotificationOutboxAlertSignal.LEASE_RECOVERY_RATIO to NotificationOutboxAlertSeverity.WARNING,
            NotificationOutboxAlertSignal.PENDING_BACKLOG to NotificationOutboxAlertSeverity.WARNING,
        )

        policy.evaluate(
            NotificationOutboxAlertSample(
                oldestActiveAge = Duration.ofMinutes(31),
                oldestBreachDuration = Duration.ofMinutes(5),
                exhaustedInFiveMinutes = 10,
                providerAttempts = 100,
                providerFailures = 50,
                providerFailureBreachDuration = Duration.ofMinutes(5),
                unknownInFiveMinutes = 5,
            ),
        ).map { it.signal to it.severity }.toSet() shouldBeEqualTo setOf(
            NotificationOutboxAlertSignal.OLDEST_ACTIVE_AGE to NotificationOutboxAlertSeverity.CRITICAL,
            NotificationOutboxAlertSignal.EXHAUSTED to NotificationOutboxAlertSeverity.CRITICAL,
            NotificationOutboxAlertSignal.PROVIDER_FAILURE_RATIO to NotificationOutboxAlertSeverity.CRITICAL,
            NotificationOutboxAlertSignal.DELIVERY_RESULT_UNKNOWN to NotificationOutboxAlertSeverity.CRITICAL,
        )

        policy.evaluate(NotificationOutboxAlertSample()).isEmpty() shouldBeEqualTo true
    }

    @Test
    fun `alert 해제는 설계의 안정 구간과 unknown 원인 확인을 모두 요구한다`() {
        val policy = NotificationOutboxAlertPolicy()

        policy.clearedSignals(
            NotificationOutboxAlertSample(
                oldestActiveAge = Duration.ofMinutes(4),
                oldestHealthyDuration = Duration.ofMinutes(10),
                exhaustedZeroDuration = Duration.ofMinutes(15),
                providerAttempts = 100,
                providerFailures = 4,
                providerHealthyDuration = Duration.ofMinutes(15),
                unknownCauseAcknowledged = true,
                deliveryAttempts = 100,
                leaseRecoveries = 0,
                leaseRecoveryHealthyDuration = Duration.ofMinutes(15),
                pendingDecreasingDuration = Duration.ofMinutes(15),
            ),
        ) shouldBeEqualTo setOf(
            NotificationOutboxAlertSignal.OLDEST_ACTIVE_AGE,
            NotificationOutboxAlertSignal.EXHAUSTED,
            NotificationOutboxAlertSignal.PROVIDER_FAILURE_RATIO,
            NotificationOutboxAlertSignal.DELIVERY_RESULT_UNKNOWN,
            NotificationOutboxAlertSignal.LEASE_RECOVERY_RATIO,
            NotificationOutboxAlertSignal.PENDING_BACKLOG,
        )

        policy.clearedSignals(
            NotificationOutboxAlertSample(
                oldestActiveAge = Duration.ofMinutes(5),
                oldestHealthyDuration = Duration.ofMinutes(9),
                exhaustedZeroDuration = Duration.ofMinutes(14),
                providerAttempts = 100,
                providerFailures = 5,
                providerHealthyDuration = Duration.ofMinutes(14),
                deliveryAttempts = 100,
                leaseRecoveries = 1,
                leaseRecoveryHealthyDuration = Duration.ofMinutes(14),
                pendingDecreasingDuration = Duration.ofMinutes(14),
            ),
        ) shouldBeEqualTo emptySet()
    }

    @Test
    fun `emergency key revoke와 key lookup 실패는 readiness 영향과 공동 on-call alert를 만든다`() {
        val policy = NotificationOutboxAlertPolicy()

        val alerts = policy.evaluate(
            NotificationOutboxAlertSample(
                emergencyKeyRevoked = true,
                keyLookupFailures = 1,
            ),
        )

        alerts.map { it.signal }.toSet() shouldBeEqualTo setOf(
            NotificationOutboxAlertSignal.EMERGENCY_KEY_REVOKED,
            NotificationOutboxAlertSignal.KEY_LOOKUP_FAILURE,
        )
        alerts.forEach { alert ->
            alert.severity shouldBeEqualTo NotificationOutboxAlertSeverity.CRITICAL
            alert.enqueueReadinessStatus shouldBeEqualTo 503
            alert.owners shouldBeEqualTo setOf(NotificationOnCallOwner.SECURITY, NotificationOnCallOwner.NOTIFICATION)
        }
    }

    @Test
    fun `공용 alert label은 channel event outcome providerCategory만 허용한다`() {
        val policy = NotificationOutboxAlertPolicy()

        val alerts = policy.evaluate(
            NotificationOutboxAlertSample(
                providerAttempts = 100,
                providerFailures = 50,
                providerFailureBreachDuration = Duration.ofMinutes(5),
                channel = NotificationChannelType.SMS,
                eventType = NotificationEventType.REMINDER,
                providerCategory = NotificationProviderCategory.SMS_GATEWAY,
            ),
        )

        alerts.single().labels.keys.all {
            it in NotificationOutboxAlertPolicy.ALLOWED_LABEL_KEYS
        } shouldBeEqualTo true
        alerts.single().labels.keys.any {
            it in setOf("tenant", "tenantId", "clinic", "clinicId", "memberId", "appointmentId", "outboxId")
        }.shouldBeFalse()

        assertFailsWith<IllegalArgumentException> {
            NotificationOutboxAlert(
                signal = NotificationOutboxAlertSignal.PENDING_BACKLOG,
                severity = NotificationOutboxAlertSeverity.WARNING,
                labels = mapOf("provider_category" to "appointment_123"),
            )
        }
    }
}
