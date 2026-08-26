package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant

internal class NotificationStatusQueryServiceTest {

    @Test
    fun `직원 조회는 닫힌 상태와 조치만 반환한다`() {
        runBlocking {
            val exhaustedAt = Instant.parse("2026-07-31T01:00:00Z")
            val service = service(
                NotificationStatusSnapshot(
                    status = NotificationOutboxStatus.EXHAUSTED,
                    failureCode = NotificationFailureCode.DELIVERY_RESULT_UNKNOWN,
                    terminalAt = exhaustedAt,
                )
            )

            service.find(scope(), NotificationStatusAudience.STAFF) shouldBeEqualTo
                NotificationStatusView(
                    status = NotificationDisplayStatus.EXHAUSTED,
                    reasonCode = NotificationFailureCode.DELIVERY_RESULT_UNKNOWN.name,
                    nextAttemptAt = null,
                    exhaustedAt = exhaustedAt,
                    recommendedAction = NotificationRecommendedAction.CONTACT_NOTIFICATION_SUPPORT,
                    patientVisible = false,
                )
        }
    }

    @Test
    fun `환자 조회는 suppression과 provider 실패 상세를 숨긴다`() {
        runBlocking {
            val service = service(
                NotificationStatusSnapshot(
                    status = NotificationOutboxStatus.SUPPRESSED,
                    suppressionReason = NotificationSuppressionReasonCode.CONSENT_DENIED,
                    terminalAt = Instant.parse("2026-07-31T01:00:00Z"),
                )
            )

            service.find(scope(), NotificationStatusAudience.PATIENT) shouldBeEqualTo
                NotificationStatusView(
                    status = NotificationDisplayStatus.NOT_AVAILABLE,
                    reasonCode = null,
                    nextAttemptAt = null,
                    exhaustedAt = null,
                    recommendedAction = NotificationRecommendedAction.NONE,
                    patientVisible = false,
                )
        }
    }

    @Test
    fun `retry 상태는 다음 시도와 대기 조치를 직원에게만 노출한다`() {
        runBlocking {
            val nextAttemptAt = Instant.parse("2026-07-31T01:05:00Z")
            val service = service(
                NotificationStatusSnapshot(
                    status = NotificationOutboxStatus.RETRY_WAIT,
                    failureCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                    nextAttemptAt = nextAttemptAt,
                )
            )

            service.find(scope(), NotificationStatusAudience.STAFF) shouldBeEqualTo
                NotificationStatusView(
                    status = NotificationDisplayStatus.RETRY_WAIT,
                    reasonCode = NotificationFailureCode.PROVIDER_UNAVAILABLE.name,
                    nextAttemptAt = nextAttemptAt,
                    exhaustedAt = null,
                    recommendedAction = NotificationRecommendedAction.WAIT_FOR_RETRY,
                    patientVisible = true,
                )
            service.find(scope(), NotificationStatusAudience.PATIENT) shouldBeEqualTo
                NotificationStatusView(
                    status = NotificationDisplayStatus.RETRY_WAIT,
                    reasonCode = null,
                    nextAttemptAt = null,
                    exhaustedAt = null,
                    recommendedAction = NotificationRecommendedAction.NONE,
                    patientVisible = true,
                )
        }
    }

    private fun service(snapshot: NotificationStatusSnapshot): NotificationStatusQueryService =
        NotificationStatusQueryService(NotificationStatusQueryStore { snapshot })

    private fun scope(): NotificationStatusScope =
        NotificationStatusScope(
            tenantGroupId = 1,
            clinicId = 2,
            appointmentId = 3,
        )
}
