package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import java.io.Serializable
import java.util.Locale

/**
 * 회원 서비스에서 발송 직전에만 가져오는 알림 profile입니다.
 *
 * 이름, destination, locale, consent는 outbox에 복사하지 않는다. worker 메모리에서
 * template rendering과 provider 호출 기간에만 사용한다.
 */
data class MemberNotificationProfile(
    val displayName: String,
    val destination: String?,
    val locale: Locale,
    val consent: NotificationConsent,
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
) : Serializable {
    init {
        validateRuntimeText(displayName, "displayName", 120)
        destination?.let { validateRuntimeText(it, "destination", 320) }
    }

    override fun toString(): String =
        "MemberNotificationProfile(displayName=<redacted>, destination=<redacted>, locale=$locale, consent=$consent, scope=<redacted>)"

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 채널별 최신 수신 동의를 나타내는 runtime 값입니다. */
data class NotificationConsent(
    val sms: Boolean = true,
    val email: Boolean = true,
    val push: Boolean = true,
) : Serializable {
    fun allows(channel: NotificationChannelType): Boolean =
        when (channel) {
            NotificationChannelType.SMS -> sms
            NotificationChannelType.EMAIL -> email
            NotificationChannelType.PUSH -> push
            NotificationChannelType.DUMMY -> true
        }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 회원 조회 결과를 retry, suppression 또는 발송 가능 상태로 닫은 판단입니다. */
data class MemberProfileDecision(
    val profile: MemberNotificationProfile?,
    val failureCode: NotificationFailureCode?,
    val suppressionReason: NotificationSuppressionReasonCode?,
) : Serializable {
    val retryable: Boolean = failureCode != null
    val suppressed: Boolean = suppressionReason != null

    companion object {
        private const val serialVersionUID = 1L

        fun resolved(profile: MemberNotificationProfile): MemberProfileDecision =
            MemberProfileDecision(profile, null, null)

        fun retry(failureCode: NotificationFailureCode): MemberProfileDecision =
            MemberProfileDecision(null, failureCode, null)

        fun suppress(reason: NotificationSuppressionReasonCode): MemberProfileDecision =
            MemberProfileDecision(null, null, reason)
    }
}

/** 회원 profile 조회 결과와 outbox claim의 scope를 대조하는 runtime 문맥입니다. */
data class MemberProfileResolutionContext(
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val channel: NotificationChannelType,
    val memberId: MemberId,
) : Serializable {
    override fun toString(): String =
        "MemberProfileResolutionContext(scope=<redacted>, channel=$channel, memberId=<redacted>)"

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** scope 불일치 보안 신호를 개인정보 없는 이벤트로 전달하는 port입니다. */
fun interface NotificationSecurityAuditSink {
    fun scopeMismatch(event: NotificationScopeMismatchSecurityEvent)
}

/** raw 식별자 없이 scope 불일치 사실만 전달하는 보안 이벤트입니다. */
data class NotificationScopeMismatchSecurityEvent(
    val channel: NotificationChannelType,
    val reason: NotificationSuppressionReasonCode = NotificationSuppressionReasonCode.MEMBER_SCOPE_MISMATCH,
    val auditFingerprint: String? = null,
) : Serializable {
    init {
        auditFingerprint?.let {
            require(SECURITY_AUDIT_FINGERPRINT.matches(it)) {
                "auditFingerprint must be a versioned HMAC-SHA256 digest"
            }
        }
    }

    override fun toString(): String =
        "NotificationScopeMismatchSecurityEvent(channel=$channel, reason=$reason, auditFingerprint=${auditFingerprint ?: "<none>"})"

    companion object {
        private const val serialVersionUID = 1L
    }
}

private val SECURITY_AUDIT_FINGERPRINT = Regex("v[1-9][0-9]*\\.hmac-sha256\\.[0-9a-f]{64}")

object NoopNotificationSecurityAuditSink : NotificationSecurityAuditSink {
    override fun scopeMismatch(event: NotificationScopeMismatchSecurityEvent) = Unit
}

/** 회원 directory 결과를 Task2의 닫힌 failure/suppression code로 변환합니다. */
object MemberNotificationProfileClassifier {

    fun classify(
        result: MemberNotificationProfileResult,
        context: MemberProfileResolutionContext,
        auditSink: NotificationSecurityAuditSink = NoopNotificationSecurityAuditSink,
    ): MemberProfileDecision =
        when (result) {
            is MemberNotificationProfileResult.Resolved -> classifyResolved(result.profile, context, auditSink)
            MemberNotificationProfileResult.NotFound,
            MemberNotificationProfileResult.Withdrawn,
            -> MemberProfileDecision.suppress(NotificationSuppressionReasonCode.MEMBER_NOT_AVAILABLE)
            MemberNotificationProfileResult.DirectoryUnavailable,
            MemberNotificationProfileResult.RateLimited,
            -> MemberProfileDecision.retry(NotificationFailureCode.MEMBER_DIRECTORY_UNAVAILABLE)
        }

    private fun classifyResolved(
        profile: MemberNotificationProfile,
        context: MemberProfileResolutionContext,
        auditSink: NotificationSecurityAuditSink,
    ): MemberProfileDecision {
        if (profile.tenantGroupId != context.tenantGroupId || profile.clinicId != context.clinicId) {
            auditSink.scopeMismatch(
                NotificationScopeMismatchSecurityEvent(
                    channel = context.channel,
                )
            )
            return MemberProfileDecision.suppress(NotificationSuppressionReasonCode.MEMBER_SCOPE_MISMATCH)
        }
        if (profile.destination.isNullOrBlank()) {
            return MemberProfileDecision.suppress(NotificationSuppressionReasonCode.DESTINATION_UNAVAILABLE)
        }
        if (!profile.consent.allows(context.channel)) {
            return MemberProfileDecision.suppress(NotificationSuppressionReasonCode.CONSENT_DENIED)
        }
        return MemberProfileDecision.resolved(profile)
    }
}

internal fun validateRuntimeText(
    value: String,
    fieldName: String,
    maxLength: Int,
): String {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require(value.length <= maxLength) { "$fieldName must not exceed $maxLength characters" }
    require(value.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
    return value
}
