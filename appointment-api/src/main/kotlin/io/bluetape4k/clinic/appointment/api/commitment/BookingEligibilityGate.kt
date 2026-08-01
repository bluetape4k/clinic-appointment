package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiError
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiException
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApplicationPort
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityProperties
import io.bluetape4k.clinic.appointment.api.reliability.DefaultBookingReliabilitySchemaReadiness
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionStamp
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import java.time.Clock

/**
 * commitment 명령 transaction 안에서 새 예약의 eligibility를 재검증하는 좁은 gate입니다.
 * 기존 `CONFIRMED` commitment 변경·취소 경로에는 연결하지 않고, 새 `PROPOSED`·`HELD`와
 * 신규 직접 `CONFIRMED` 전환 직전에만 호출합니다.
 */
internal class BookingEligibilityGate(
    private val port: BookingReliabilityApplicationPort?,
    private val properties: BookingReliabilityProperties,
    private val schemaReadiness: DefaultBookingReliabilitySchemaReadiness? = null,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun requireAllowed(
        context: CommitmentCommandContext,
        memberId: MemberId?,
    ): BookingReliabilityDecisionStamp? {
        if (properties.mode == BookingReliabilityProperties.Mode.OFF || port == null) return null
        if (properties.clinicAllowList.isNotEmpty() && context.clinicId !in properties.clinicAllowList) return null
        if (properties.mode == BookingReliabilityProperties.Mode.ENFORCE &&
            schemaReadiness?.canEnforce(properties) != true
        ) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE)
        }
        val resolvedMemberId = memberId
            ?: throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE)
        val evaluatedAt = clock.instant()
        val decision = port.evaluate(
            tenantGroupId = context.tenantGroupId,
            clinicId = context.clinicId,
            memberId = resolvedMemberId,
            at = evaluatedAt,
        )
        if (properties.mode == BookingReliabilityProperties.Mode.SHADOW) {
            return decision.toStampOrNull()
        }
        when (decision.verdict) {
            BookingReliabilityVerdict.RESTRICTED,
            BookingReliabilityVerdict.REQUIRES_STAFF_APPROVAL,
            -> throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_REVIEW_REQUIRED)

            BookingReliabilityVerdict.STALE -> throw BookingReliabilityApiException(
                BookingReliabilityApiError.BOOKING_DECISION_STALE,
            )

            BookingReliabilityVerdict.UNAVAILABLE -> throw BookingReliabilityApiException(
                BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE,
            )

            BookingReliabilityVerdict.ELIGIBLE,
            BookingReliabilityVerdict.OVERRIDDEN,
            BookingReliabilityVerdict.POLICY_DISABLED,
            -> {
                val stamp = decision.toStampOrNull()
                    ?: throw BookingReliabilityApiException(
                        BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE,
                    )
                if (!stamp.isUsableAt(evaluatedAt)) {
                    throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_STALE)
                }
                return stamp
            }
        }
    }

    /**
     * allocation/CAS 직전에 같은 reliability head를 다시 읽고 stamp를 비교합니다.
     * adapter는 expected policy version이 있는 조회에서 최신 decision row를 잠그므로,
     * override command와 booking command가 서로의 stale decision을 통과시키지 않습니다.
     */
    fun requireFresh(
        context: CommitmentCommandContext,
        memberId: MemberId?,
        expected: BookingReliabilityDecisionStamp?,
    ) {
        if (expected == null || properties.mode == BookingReliabilityProperties.Mode.OFF || port == null) return
        if (properties.clinicAllowList.isNotEmpty() && context.clinicId !in properties.clinicAllowList) return
        if (properties.mode == BookingReliabilityProperties.Mode.ENFORCE &&
            schemaReadiness?.canEnforce(properties) != true
        ) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE)
        }
        val resolvedMemberId = memberId
            ?: throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE)
        val evaluatedAt = clock.instant()
        if (!expected.isUsableAt(evaluatedAt)) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_STALE)
        }
        val decision = port.evaluate(
            tenantGroupId = context.tenantGroupId,
            clinicId = context.clinicId,
            memberId = resolvedMemberId,
            at = evaluatedAt,
            requestedPolicySnapshotId = expected.policyVersionId,
        )
        when (decision.verdict) {
            BookingReliabilityVerdict.UNAVAILABLE ->
                throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE)

            BookingReliabilityVerdict.STALE ->
                throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_STALE)

            BookingReliabilityVerdict.RESTRICTED,
            BookingReliabilityVerdict.REQUIRES_STAFF_APPROVAL,
            -> if (properties.mode == BookingReliabilityProperties.Mode.ENFORCE) {
                throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_REVIEW_REQUIRED)
            }

            BookingReliabilityVerdict.ELIGIBLE,
            BookingReliabilityVerdict.OVERRIDDEN,
            BookingReliabilityVerdict.POLICY_DISABLED,
            -> Unit
        }
        if (decision.toStampOrNull() != expected) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_STALE)
        }
    }

    companion object {
        fun disabled(): BookingEligibilityGate =
            BookingEligibilityGate(null, BookingReliabilityProperties())
    }
}

private fun BookingReliabilityDecisionRecord.toStampOrNull(): BookingReliabilityDecisionStamp? =
    run {
        val id = decisionId ?: return@run null
        val version = policyVersionId ?: return@run null
        val hash = policyHash ?: return@run null
        BookingReliabilityDecisionStamp(
            decisionId = id,
            policyVersionId = version,
            policyHash = hash,
            evaluationDigest = decisionDigest,
            expiresAt = expiresAt,
        )
    }
