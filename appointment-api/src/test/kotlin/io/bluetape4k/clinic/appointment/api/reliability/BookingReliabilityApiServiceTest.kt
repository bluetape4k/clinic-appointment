package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import org.junit.jupiter.api.Test
import java.time.Instant

class BookingReliabilityApiServiceTest {

    private val now = Instant.parse("2026-08-01T03:00:00Z")
    private val memberId = MemberId("opaque-member-001")
    private val digest = "a".repeat(64)
    private val actor = ActorContext(
        actorId = "staff-001",
        actorType = ActorType.STAFF,
        roles = setOf(ActorRole.STAFF),
        scopes = setOf("booking-reliability:write"),
        allowedTenantCodes = setOf("tenant-a"),
        allowedClinicIds = setOf(20L),
        patientSubjectId = null,
        assurance = AuthenticationAssurance.MFA,
        issuer = "issuer",
        tokenId = "token-id",
        authenticatedAt = now,
        correlationId = "correlation-id",
        selectedClinicId = 20L,
    )

    @Test
    fun `override requires a bounded idempotency key`() {
        val service = DefaultBookingReliabilityApiService(
            FakePort(decision()),
            BookingReliabilityProperties(),
        )

        val failure = runCatching {
            service.override(
                tenantGroupId = 10L,
                clinicId = 20L,
                memberId = memberId,
                actor = actor,
                request = BookingReliabilityOverrideRequest(
                    verdict = BookingReliabilityVerdict.ELIGIBLE,
                    reasonCode = BookingReliabilityReasonCode.MANUAL_OVERRIDE,
                    decisionId = 7L,
                    evaluationDigest = digest,
                ),
                idempotencyKey = null,
                now = now,
            )
        }.exceptionOrNull() as BookingReliabilityApiException

        failure.error shouldBeEqualTo BookingReliabilityApiError.BOOKING_IDEMPOTENCY_REQUIRED
    }

    @Test
    fun `stale decision digest is rejected before override persistence`() {
        val port = FakePort(decision())
        val service = DefaultBookingReliabilityApiService(port, BookingReliabilityProperties())

        val failure = runCatching {
            service.override(
                10L,
                20L,
                memberId,
                actor,
                BookingReliabilityOverrideRequest(
                    verdict = BookingReliabilityVerdict.ELIGIBLE,
                    reasonCode = BookingReliabilityReasonCode.MANUAL_OVERRIDE,
                    decisionId = 7L,
                    evaluationDigest = "b".repeat(64),
                ),
                "override-1",
                now,
            )
        }.exceptionOrNull() as BookingReliabilityApiException

        failure.error shouldBeEqualTo BookingReliabilityApiError.BOOKING_DECISION_STALE
        port.overrideCalls shouldBeEqualTo 0
    }

    @Test
    fun `response carries only opaque member id and bounded decision fields`() {
        val response = DefaultBookingReliabilityApiService(
            FakePort(decision()),
            BookingReliabilityProperties(mode = BookingReliabilityProperties.Mode.SHADOW),
        ).decision(10L, 20L, memberId, now)

        response.memberId shouldBeEqualTo memberId.value
        response.mode shouldBeEqualTo BookingReliabilityProperties.Mode.SHADOW
        response.reasonCodes shouldBeEqualTo setOf(BookingReliabilityReasonCode.NO_PATIENT_RESPONSIBLE_TRIGGER)
    }

    private fun decision(): BookingReliabilityDecisionRecord = BookingReliabilityDecisionRecord(
        tenantGroupId = 10L,
        clinicId = 20L,
        memberId = memberId,
        policyVersionId = 77L,
        policyHash = "c".repeat(64),
        evaluatedAt = now,
        verdict = BookingReliabilityVerdict.ELIGIBLE,
        reasonCodes = setOf(BookingReliabilityReasonCode.NO_PATIENT_RESPONSIBLE_TRIGGER),
        triggers = emptyList(),
        noShowCount = 0,
        lateCancellationCount = 0,
        effectiveFrom = null,
        expiresAt = null,
        decisionDigest = digest,
        decisionId = 7L,
    )

    private class FakePort(
        private val current: BookingReliabilityDecisionRecord,
    ) : BookingReliabilityApplicationPort {
        var overrideCalls: Int = 0

        override fun evaluate(
            tenantGroupId: Long,
            clinicId: Long,
            memberId: MemberId,
            at: Instant,
            requestedPolicySnapshotId: Long?,
        ): BookingReliabilityDecisionRecord = current

        override fun override(
            tenantGroupId: Long,
            clinicId: Long,
            memberId: MemberId,
            command: BookingReliabilityOverrideCommand,
        ): BookingReliabilityDecisionRecord {
            overrideCalls++
            return current
        }

        override fun clear(
            tenantGroupId: Long,
            clinicId: Long,
            memberId: MemberId,
            command: BookingReliabilityClearCommand,
        ): BookingReliabilityDecisionRecord = current

        override fun audit(
            tenantGroupId: Long,
            clinicId: Long,
            memberId: MemberId,
            cursor: String?,
            limit: Int,
        ): BookingReliabilityAuditPage = BookingReliabilityAuditPage(emptyList(), null)
    }
}
