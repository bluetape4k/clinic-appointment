package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import java.time.Clock
import java.time.Instant

/** API application layer가 요구하는 영속·평가 경계입니다. 구현체는 caller transaction을 소유합니다. */
interface BookingReliabilityApplicationPort {
    fun evaluate(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        at: Instant,
        requestedPolicySnapshotId: Long? = null,
    ): BookingReliabilityDecisionRecord

    fun override(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        command: BookingReliabilityOverrideCommand,
    ): BookingReliabilityDecisionRecord

    fun clear(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        command: BookingReliabilityClearCommand,
    ): BookingReliabilityDecisionRecord

    fun audit(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        cursor: String?,
        limit: Int,
    ): BookingReliabilityAuditPage
}

data class BookingReliabilityOverrideCommand(
    val actor: ActorContext,
    val idempotencyKey: String,
    val expectedDecisionId: Long?,
    val expectedEvaluationDigest: String,
    val verdict: BookingReliabilityVerdict,
    val reasonCode: BookingReliabilityReasonCode,
    val effectiveFrom: Instant,
    val expiresAt: Instant?,
)

data class BookingReliabilityClearCommand(
    val actor: ActorContext,
    val idempotencyKey: String,
    val expectedDecisionId: Long?,
    val expectedEvaluationDigest: String,
    val reasonCode: BookingReliabilityReasonCode,
)

/** controller가 사용하는 API facade입니다. */
interface BookingReliabilityApiService {
    fun decision(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        at: Instant,
        requestedPolicySnapshotId: Long? = null,
    ): BookingReliabilityDecisionResponse

    fun override(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        actor: ActorContext,
        request: BookingReliabilityOverrideRequest,
        idempotencyKey: String?,
        now: Instant,
    ): BookingReliabilityDecisionResponse

    fun clear(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        actor: ActorContext,
        request: BookingReliabilityClearRequest,
        idempotencyKey: String?,
    ): BookingReliabilityDecisionResponse

    fun audit(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        cursor: String?,
        limit: Int,
    ): BookingReliabilityAuditPage
}

/** mode·idempotency·precondition을 한 곳에서 검증하는 기본 API facade입니다. */
class DefaultBookingReliabilityApiService(
    private val port: BookingReliabilityApplicationPort,
    private val properties: BookingReliabilityProperties,
    private val clock: Clock = Clock.systemUTC(),
) : BookingReliabilityApiService {

    override fun decision(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        at: Instant,
        requestedPolicySnapshotId: Long?,
    ): BookingReliabilityDecisionResponse {
        val record = port.evaluate(tenantGroupId, clinicId, memberId, at, requestedPolicySnapshotId)
        return record.toApiResponse(properties.mode)
    }

    override fun override(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        actor: ActorContext,
        request: BookingReliabilityOverrideRequest,
        idempotencyKey: String?,
        now: Instant,
    ): BookingReliabilityDecisionResponse {
        val key = requireIdempotencyKey(idempotencyKey)
        val current = currentDecision(tenantGroupId, clinicId, memberId)
        if ((request.decisionId != null && request.decisionId != current.decisionId) ||
            request.evaluationDigest != current.decisionDigest
        ) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_STALE)
        }
        val effectiveFrom = request.effectiveFrom ?: now
        val record = port.override(
            tenantGroupId,
            clinicId,
            memberId,
            BookingReliabilityOverrideCommand(
                actor = actor,
                idempotencyKey = key,
                expectedDecisionId = request.decisionId,
                expectedEvaluationDigest = request.evaluationDigest,
                verdict = request.verdict,
                reasonCode = request.reasonCode,
                effectiveFrom = effectiveFrom,
                expiresAt = request.expiresAt,
            ),
        )
        return record.toApiResponse(properties.mode)
    }

    override fun clear(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        actor: ActorContext,
        request: BookingReliabilityClearRequest,
        idempotencyKey: String?,
    ): BookingReliabilityDecisionResponse {
        val key = requireIdempotencyKey(idempotencyKey)
        val current = currentDecision(tenantGroupId, clinicId, memberId)
        if ((request.decisionId != null && request.decisionId != current.decisionId) ||
            request.evaluationDigest != current.decisionDigest
        ) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_STALE)
        }
        val record = port.clear(
            tenantGroupId,
            clinicId,
            memberId,
            BookingReliabilityClearCommand(
                actor = actor,
                idempotencyKey = key,
                expectedDecisionId = request.decisionId,
                expectedEvaluationDigest = request.evaluationDigest,
                reasonCode = request.reasonCode,
            ),
        )
        return record.toApiResponse(properties.mode)
    }

    override fun audit(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        cursor: String?,
        limit: Int,
    ): BookingReliabilityAuditPage {
        require(limit in 1..properties.maxAuditPageSize) { "limit is outside the bounded audit page size" }
        return port.audit(tenantGroupId, clinicId, memberId, cursor, limit)
    }

    private fun currentDecision(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
    ): BookingReliabilityDecisionRecord =
        port.evaluate(tenantGroupId, clinicId, memberId, clock.instant())

    private fun requireIdempotencyKey(value: String?): String =
        value?.takeIf { IDEMPOTENCY_KEY.matches(it) }
            ?: throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_IDEMPOTENCY_REQUIRED)

    private companion object {
        val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9._:/-]{1,128}")
    }
}

enum class BookingReliabilityApiError(
    val httpStatus: org.springframework.http.HttpStatus,
    val safeMessage: String,
    val retryable: Boolean,
    val action: String,
) {
    BOOKING_REVIEW_REQUIRED(
        org.springframework.http.HttpStatus.CONFLICT,
        "Booking requires clinic staff review.",
        false,
        "Ask clinic staff to review the booking decision.",
    ),
    BOOKING_DECISION_UNAVAILABLE(
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
        "Booking reliability decision is temporarily unavailable.",
        true,
        "Retry with the same intent after the service recovers.",
    ),
    BOOKING_DECISION_STALE(
        org.springframework.http.HttpStatus.CONFLICT,
        "Booking reliability decision is stale.",
        false,
        "Reload the current decision before retrying.",
    ),
    BOOKING_RELIABILITY_FORBIDDEN(
        org.springframework.http.HttpStatus.FORBIDDEN,
        "The authenticated actor cannot access booking reliability data.",
        false,
        "Use an authorized clinic-scoped capability.",
    ),
    BOOKING_IDEMPOTENCY_REQUIRED(
        org.springframework.http.HttpStatus.PRECONDITION_REQUIRED,
        "An idempotency key is required for this booking reliability command.",
        false,
        "Send a bounded Idempotency-Key header.",
    ),
    BOOKING_PAYLOAD_INVALID(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "Booking reliability request payload is invalid.",
        false,
        "Correct the request using the published API schema.",
    ),
}

class BookingReliabilityApiException(
    val error: BookingReliabilityApiError,
    cause: Throwable? = null,
) : RuntimeException(error.name, cause)
