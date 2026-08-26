package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import java.io.Serializable
import java.time.Instant

/**
 * 예약별 알림 상태를 개인정보 없이 조회합니다.
 *
 * [store] 구현은 예약 row의 tenant/clinic 소유권을 먼저 확인하고, outbox 조회에도 같은
 * tenant/clinic/appointment predicate를 적용해야 합니다. 반환값에는 member, destination,
 * outbox/attempt 식별자와 provider payload를 포함할 수 없습니다.
 */
class NotificationStatusQueryService(
    private val store: NotificationStatusQueryStore,
) {

    suspend fun find(
        scope: NotificationStatusScope,
        audience: NotificationStatusAudience,
    ): NotificationStatusView? {
        val snapshot = store.findLatest(scope) ?: return null
        return when (audience) {
            NotificationStatusAudience.STAFF -> snapshot.toStaffView()
            NotificationStatusAudience.PATIENT -> snapshot.toPatientView()
        }
    }

    private fun NotificationStatusSnapshot.toStaffView(): NotificationStatusView {
        val reasonCode = suppressionReason?.name ?: failureCode?.name
        return NotificationStatusView(
            status = status.toDisplayStatus(),
            reasonCode = reasonCode,
            nextAttemptAt = nextAttemptAt.takeIf { status == NotificationOutboxStatus.RETRY_WAIT },
            exhaustedAt = terminalAt.takeIf { status == NotificationOutboxStatus.EXHAUSTED },
            recommendedAction = recommendedAction(reasonCode, status),
            patientVisible = status !in PATIENT_HIDDEN_STATUSES,
        )
    }

    private fun NotificationStatusSnapshot.toPatientView(): NotificationStatusView =
        if (status in PATIENT_HIDDEN_STATUSES) {
            NotificationStatusView(
                status = NotificationDisplayStatus.NOT_AVAILABLE,
                reasonCode = null,
                nextAttemptAt = null,
                exhaustedAt = null,
                recommendedAction = NotificationRecommendedAction.NONE,
                patientVisible = false,
            )
        } else {
            NotificationStatusView(
                status = status.toDisplayStatus(),
                reasonCode = null,
                nextAttemptAt = null,
                exhaustedAt = null,
                recommendedAction = NotificationRecommendedAction.NONE,
                patientVisible = true,
            )
        }

    private fun recommendedAction(
        reasonCode: String?,
        status: NotificationOutboxStatus,
    ): NotificationRecommendedAction =
        when (reasonCode) {
            NotificationSuppressionReasonCode.CONSENT_DENIED.name ->
                NotificationRecommendedAction.CHECK_MEMBER_SETTINGS

            NotificationSuppressionReasonCode.DESTINATION_UNAVAILABLE.name ->
                NotificationRecommendedAction.CHECK_MEMBER_CONTACT

            NotificationSuppressionReasonCode.REMINDER_WINDOW_MISSED.name ->
                NotificationRecommendedAction.CONTACT_PATIENT

            else -> when (status) {
                NotificationOutboxStatus.RETRY_WAIT -> NotificationRecommendedAction.WAIT_FOR_RETRY
                NotificationOutboxStatus.EXHAUSTED -> NotificationRecommendedAction.CONTACT_NOTIFICATION_SUPPORT
                else -> NotificationRecommendedAction.NONE
            }
        }

    private fun NotificationOutboxStatus.toDisplayStatus(): NotificationDisplayStatus =
        NotificationDisplayStatus.valueOf(name)

    private companion object {
        val PATIENT_HIDDEN_STATUSES =
            setOf(NotificationOutboxStatus.SUPPRESSED, NotificationOutboxStatus.EXHAUSTED)
    }
}

/**
 * tenant·clinic·appointment 결합 predicate로 최신 알림 상태 한 건만 조회하는 port입니다.
 *
 * 구현은 appointment scope 검증과 outbox 조회 사이에 범위가 느슨해지지 않도록 같은
 * transaction 또는 동등한 일관성 경계에서 처리해야 합니다.
 */
fun interface NotificationStatusQueryStore {
    suspend fun findLatest(scope: NotificationStatusScope): NotificationStatusSnapshot?
}

/** tenant, clinic, appointment를 함께 고정한 조회 범위입니다. */
data class NotificationStatusScope(
    val tenantGroupId: Long,
    val clinicId: Long,
    val appointmentId: Long,
) : Serializable {
    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(appointmentId > 0) { "appointmentId must be positive" }
    }

    override fun toString(): String = "NotificationStatusScope(<redacted>)"

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 개인정보와 provider 원문을 제거한 최신 알림 상태입니다. */
data class NotificationStatusSnapshot(
    val status: NotificationOutboxStatus,
    val suppressionReason: NotificationSuppressionReasonCode? = null,
    val failureCode: NotificationFailureCode? = null,
    val nextAttemptAt: Instant? = null,
    val terminalAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 상태 정보를 읽는 대상에 따른 공개 수준입니다. */
enum class NotificationStatusAudience {
    STAFF,
    PATIENT,
}

/** API에 공개할 수 있는 닫힌 알림 상태입니다. */
enum class NotificationDisplayStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SENT,
    SUPPRESSED,
    EXHAUSTED,
    NOT_AVAILABLE,
}

/** 운영자나 환자에게 제시할 수 있는 닫힌 조치 코드입니다. */
enum class NotificationRecommendedAction {
    NONE,
    WAIT_FOR_RETRY,
    CHECK_MEMBER_SETTINGS,
    CHECK_MEMBER_CONTACT,
    CONTACT_PATIENT,
    CONTACT_NOTIFICATION_SUPPORT,
}

/** 정확히 여섯 개의 안전한 필드로 제한한 상태 조회 결과입니다. */
data class NotificationStatusView(
    val status: NotificationDisplayStatus,
    val reasonCode: String?,
    val nextAttemptAt: Instant?,
    val exhaustedAt: Instant?,
    val recommendedAction: NotificationRecommendedAction,
    val patientVisible: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
