package io.bluetape4k.clinic.appointment.api.reliability

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.io.Serializable
import java.time.Instant

/** 예약 신뢰성 request의 선언되지 않은 JSON 필드를 거절하는 공통 기반입니다. */
abstract class StrictBookingReliabilityBody : Serializable {
    @JsonAnySetter
    fun rejectUnknownProperty(name: String, @Suppress("UNUSED_PARAMETER") value: Any?): Nothing =
        throw IllegalArgumentException("Unknown booking reliability field: $name")

    private companion object {
        const val serialVersionUID = 1L
    }
}

/** 직원이 적용할 bounded override 요청입니다. actor/clinic/member 연락처는 받지 않습니다. */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(requiredProperties = ["verdict", "reasonCode", "evaluationDigest"])
data class BookingReliabilityOverrideRequest(
    val verdict: BookingReliabilityVerdict,
    val reasonCode: BookingReliabilityReasonCode,
    val effectiveFrom: Instant? = null,
    val expiresAt: Instant? = null,
    @field:Positive
    val decisionId: Long? = null,
    @field:NotBlank
    @field:Pattern(regexp = "[0-9a-f]{64}")
    val evaluationDigest: String,
) : StrictBookingReliabilityBody() {
    init {
        require(verdict !in setOf(BookingReliabilityVerdict.STALE, BookingReliabilityVerdict.UNAVAILABLE)) {
            "override verdict must be actionable"
        }
        require(expiresAt == null || effectiveFrom == null || expiresAt > effectiveFrom) {
            "expiresAt must be after effectiveFrom"
        }
    }
}

/** 활성 override 또는 제한을 해제하는 bounded 요청입니다. */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(requiredProperties = ["reasonCode", "evaluationDigest"])
data class BookingReliabilityClearRequest(
    val reasonCode: BookingReliabilityReasonCode = BookingReliabilityReasonCode.MANUAL_CLEAR,
    @field:Positive
    val decisionId: Long? = null,
    @field:NotBlank
    @field:Pattern(regexp = "[0-9a-f]{64}")
    val evaluationDigest: String,
) : StrictBookingReliabilityBody()

/** 결정 조회 응답입니다. 회원 이름·전화번호·자유 텍스트는 포함하지 않습니다. */
data class BookingReliabilityDecisionResponse(
    val decisionId: Long?,
    val clinicId: Long,
    val memberId: String,
    val verdict: BookingReliabilityVerdict,
    val policyVersionId: Long?,
    val policyHash: String?,
    val evaluatedAt: Instant,
    val noShowCount: Int,
    val lateCancellationCount: Int,
    val reasonCodes: Set<BookingReliabilityReasonCode>,
    val triggeringAppointmentIds: List<Long>,
    val hasAdditionalTriggers: Boolean,
    val auditCursor: String?,
    val effectiveFrom: Instant?,
    val expiresAt: Instant?,
    val evaluationDigest: String,
    val mode: BookingReliabilityProperties.Mode,
) : Serializable

/** audit 조회의 제한된 한 행입니다. */
data class BookingReliabilityAuditEntry(
    val decisionId: Long?,
    val evaluatedAt: Instant,
    val verdict: BookingReliabilityVerdict,
    val reasonCodes: Set<BookingReliabilityReasonCode>,
    val evaluationDigest: String,
    val actorRef: String? = null,
) : Serializable

/** keyset cursor를 사용하는 audit page입니다. cursor 원문은 외부에 의미를 노출하지 않습니다. */
data class BookingReliabilityAuditPage(
    val entries: List<BookingReliabilityAuditEntry>,
    val nextCursor: String?,
) : Serializable

fun BookingReliabilityDecisionRecord.toApiResponse(
    mode: BookingReliabilityProperties.Mode,
): BookingReliabilityDecisionResponse =
    BookingReliabilityDecisionResponse(
        decisionId = decisionId,
        clinicId = clinicId,
        memberId = memberId.value,
        verdict = verdict,
        policyVersionId = policyVersionId,
        policyHash = policyHash,
        evaluatedAt = evaluatedAt,
        noShowCount = noShowCount,
        lateCancellationCount = lateCancellationCount,
        reasonCodes = reasonCodes,
        triggeringAppointmentIds = triggers.map { it.appointmentId },
        hasAdditionalTriggers = hasAdditionalTriggers,
        auditCursor = auditCursor,
        effectiveFrom = effectiveFrom,
        expiresAt = expiresAt,
        evaluationDigest = decisionDigest,
        mode = mode,
    )
