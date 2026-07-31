package io.bluetape4k.clinic.appointment.api.dto

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.io.Serializable
import java.time.Instant

/**
 * 알림 상태 조회 응답입니다.
 *
 * recipient, member ID, outbox ID, attempt ID, provider payload, provider 오류 원문은
 * 이 DTO에 추가하지 않습니다. 운영자가 조치할 수 있는 낮은 cardinality 상태만 반환합니다.
 */
data class NotificationStatusResponse(
    val status: NotificationStatusCode,
    val reasonCode: String?,
    val nextAttemptAt: Instant?,
    val exhaustedAt: Instant?,
    val recommendedAction: NotificationRecommendedActionCode,
    val patientVisible: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 외부 API에 고정한 알림 상태 코드입니다. */
enum class NotificationStatusCode {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SENT,
    SUPPRESSED,
    EXHAUSTED,
    NOT_AVAILABLE,
}

/** 외부 API에 고정한 권장 조치 코드입니다. */
enum class NotificationRecommendedActionCode {
    NONE,
    WAIT_FOR_RETRY,
    CHECK_MEMBER_SETTINGS,
    CHECK_MEMBER_CONTACT,
    CONTACT_PATIENT,
    CONTACT_NOTIFICATION_SUPPORT,
}

/**
 * 수동 재알림 요청입니다.
 *
 * [appointmentIds]는 예약 ID 원문이므로 응답이나 audit sink가 그대로 기록하면 안 됩니다.
 * 서비스는 같은 [generation]으로 재시작될 수 있어야 하며, 기본 정책으로 이미 완료된
 * `SENT`와 결과 불명 `DELIVERY_RESULT_UNKNOWN` 행은 제외합니다.
 */
data class ReNotifyRequest(
    @field:Size(min = 1, max = 100)
    @field:ArraySchema(
        minItems = 1,
        maxItems = 100,
        uniqueItems = true,
        schema = Schema(minimum = "1"),
    )
    val appointmentIds: List<Long>,
    @field:NotBlank
    @field:Size(max = 128)
    @field:Pattern(regexp = SAFE_APPROVAL_REFERENCE_PATTERN)
    @field:Schema(minLength = 1, maxLength = 128, pattern = SAFE_APPROVAL_REFERENCE_PATTERN)
    val generation: String,
    @field:Valid
    val platformApproval: ApprovalReferenceRequest,
    @field:Valid
    val clinicApproval: ApprovalReferenceRequest,
    val dryRun: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 외부 승인 시스템이 발급한 비식별 승인 참조입니다.
 *
 * 사람 이름, 전화번호, 서명 원문, ticket 본문을 넣지 않고 안정적인 authority와 reference만
 * 전달합니다.
 */
data class ApprovalReferenceRequest(
    @field:NotBlank
    @field:Size(max = 128)
    @field:Pattern(regexp = SAFE_APPROVAL_REFERENCE_PATTERN)
    @field:Schema(minLength = 1, maxLength = 128, pattern = SAFE_APPROVAL_REFERENCE_PATTERN)
    val authority: String,
    @field:NotBlank
    @field:Size(max = 128)
    @field:Pattern(regexp = SAFE_APPROVAL_REFERENCE_PATTERN)
    @field:Schema(minLength = 1, maxLength = 128, pattern = SAFE_APPROVAL_REFERENCE_PATTERN)
    val reference: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private const val SAFE_APPROVAL_REFERENCE_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]*"

/** 수동 재알림 실행 결과의 privacy-safe 집계입니다. */
data class ReNotifyResponse(
    val generation: String,
    val dryRun: Boolean,
    val requestedCount: Int,
    val acceptedCount: Int,
    val skippedCount: Int,
    val skippedReasons: Map<String, Int>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
