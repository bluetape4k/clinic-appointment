package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiError
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiException
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApplicationPort
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityAuditPage
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityClearCommand
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityOverrideCommand
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityProperties
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilitySchemaReadiness
import io.bluetape4k.clinic.appointment.api.reliability.DefaultBookingReliabilitySchemaReadiness
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import org.junit.jupiter.api.Test
import java.time.Instant

class BookingEligibilityGateTest {

    private val context = CommitmentCommandContext(
        tenantGroupId = 10L,
        clinicId = 20L,
        actorScopeHash = "a".repeat(64),
        actorAuditRef = "staff-1",
        idempotencyKeyHash = "b".repeat(64),
        commandHash = "c".repeat(64),
        correlationId = "correlation-1",
    )
    private val memberId = MemberId("member-opaque-001")

    @Test
    fun `off mode does not read reliability port`() {
        var calls = 0
        val gate = BookingEligibilityGate(
            port = object : FakePort() {
                override fun evaluate(
                    tenantGroupId: Long,
                    clinicId: Long,
                    memberId: MemberId,
                    at: Instant,
                    requestedPolicySnapshotId: Long?,
                ): BookingReliabilityDecisionRecord {
                    calls++
                    return gateDecision(memberId, BookingReliabilityVerdict.RESTRICTED)
                }
            },
            properties = BookingReliabilityProperties(mode = BookingReliabilityProperties.Mode.OFF),
        )

        gate.requireAllowed(context, memberId)

        calls shouldBeEqualTo 0
    }

    @Test
    fun `enforce mode rejects restricted decision before allocation`() {
        val gate = BookingEligibilityGate(
            port = object : FakePort() {
                override fun evaluate(
                    tenantGroupId: Long,
                    clinicId: Long,
                    memberId: MemberId,
                    at: Instant,
                    requestedPolicySnapshotId: Long?,
                ): BookingReliabilityDecisionRecord = gateDecision(memberId, BookingReliabilityVerdict.RESTRICTED)
            },
            properties = BookingReliabilityProperties(mode = BookingReliabilityProperties.Mode.ENFORCE),
            schemaReadiness = readySchemaReadiness(),
        )

        val failure = runCatching { gate.requireAllowed(context, memberId) }
            .exceptionOrNull() as BookingReliabilityApiException

        failure.error shouldBeEqualTo BookingReliabilityApiError.BOOKING_REVIEW_REQUIRED
    }

    @Test
    fun `enforce mode returns an immutable decision stamp for a new commitment`() {
        val gate = BookingEligibilityGate(
            port = object : FakePort() {
                override fun evaluate(
                    tenantGroupId: Long,
                    clinicId: Long,
                    memberId: MemberId,
                    at: Instant,
                    requestedPolicySnapshotId: Long?,
                ): BookingReliabilityDecisionRecord =
                    gateDecision(memberId, BookingReliabilityVerdict.ELIGIBLE, stamped = true)
            },
            properties = BookingReliabilityProperties(mode = BookingReliabilityProperties.Mode.ENFORCE),
            schemaReadiness = readySchemaReadiness(),
        )

        val stamp = gate.requireAllowed(context, memberId)

        stamp?.decisionId shouldBeEqualTo 77L
        stamp?.policyVersionId shouldBeEqualTo 7L
        stamp?.evaluationDigest shouldBeEqualTo "d".repeat(64)
    }

    @Test
    fun `fresh check rejects a superseded decision stamp`() {
        val gate = BookingEligibilityGate(
            port = object : FakePort() {
                override fun evaluate(
                    tenantGroupId: Long,
                    clinicId: Long,
                    memberId: MemberId,
                    at: Instant,
                    requestedPolicySnapshotId: Long?,
                ): BookingReliabilityDecisionRecord =
                    gateDecision(memberId, BookingReliabilityVerdict.ELIGIBLE, stamped = true).let {
                        if (requestedPolicySnapshotId == null) it else it.copy(decisionId = 78L)
                    }
            },
            properties = BookingReliabilityProperties(mode = BookingReliabilityProperties.Mode.ENFORCE),
            schemaReadiness = readySchemaReadiness(),
        )

        val stamp = gate.requireAllowed(context, memberId)
        val failure = runCatching { gate.requireFresh(context, memberId, stamp) }
            .exceptionOrNull() as BookingReliabilityApiException

        failure.error shouldBeEqualTo BookingReliabilityApiError.BOOKING_DECISION_STALE
    }

    @Test
    fun `fresh check accepts an unchanged decision stamp`() {
        val gate = BookingEligibilityGate(
            port = object : FakePort() {
                override fun evaluate(
                    tenantGroupId: Long,
                    clinicId: Long,
                    memberId: MemberId,
                    at: Instant,
                    requestedPolicySnapshotId: Long?,
                ): BookingReliabilityDecisionRecord = gateDecision(memberId, BookingReliabilityVerdict.ELIGIBLE, stamped = true)
            },
            properties = BookingReliabilityProperties(mode = BookingReliabilityProperties.Mode.ENFORCE),
            schemaReadiness = readySchemaReadiness(),
        )

        val stamp = gate.requireAllowed(context, memberId)

        gate.requireFresh(context, memberId, stamp)
    }

    @Test
    fun `enforce mode fails closed when schema readiness is unavailable`() {
        val gate = BookingEligibilityGate(
            port = object : FakePort() {
                override fun evaluate(
                    tenantGroupId: Long,
                    clinicId: Long,
                    memberId: MemberId,
                    at: Instant,
                    requestedPolicySnapshotId: Long?,
                ): BookingReliabilityDecisionRecord = gateDecision(memberId, BookingReliabilityVerdict.ELIGIBLE)
            },
            properties = BookingReliabilityProperties(mode = BookingReliabilityProperties.Mode.ENFORCE),
        )

        val failure = runCatching { gate.requireAllowed(context, memberId) }
            .exceptionOrNull() as BookingReliabilityApiException

        failure.error shouldBeEqualTo BookingReliabilityApiError.BOOKING_DECISION_UNAVAILABLE
    }

    @Test
    fun `shadow mode observes unavailable but keeps booking path open`() {
        val gate = BookingEligibilityGate(
            port = object : FakePort() {
                override fun evaluate(
                    tenantGroupId: Long,
                    clinicId: Long,
                    memberId: MemberId,
                    at: Instant,
                    requestedPolicySnapshotId: Long?,
                ): BookingReliabilityDecisionRecord = gateDecision(memberId, BookingReliabilityVerdict.UNAVAILABLE)
            },
            properties = BookingReliabilityProperties(mode = BookingReliabilityProperties.Mode.SHADOW),
        )

        gate.requireAllowed(context, memberId)
    }

    @Test
    fun `clinic allowlist leaves other clinics on the legacy path`() {
        var calls = 0
        val gate = BookingEligibilityGate(
            port = object : FakePort() {
                override fun evaluate(
                    tenantGroupId: Long,
                    clinicId: Long,
                    memberId: MemberId,
                    at: Instant,
                    requestedPolicySnapshotId: Long?,
                ): BookingReliabilityDecisionRecord {
                    calls++
                    return gateDecision(memberId, BookingReliabilityVerdict.RESTRICTED)
                }
            },
            properties = BookingReliabilityProperties(
                mode = BookingReliabilityProperties.Mode.ENFORCE,
                clinicAllowList = setOf(999L),
            ),
        )

        gate.requireAllowed(context, memberId)

        calls shouldBeEqualTo 0
    }

    private fun decision(verdict: BookingReliabilityVerdict) = BookingReliabilityDecisionRecord(
        tenantGroupId = 10L,
        clinicId = 20L,
        memberId = memberId,
        policyVersionId = null,
        policyHash = null,
        evaluatedAt = Instant.parse("2026-08-01T03:00:00Z"),
        verdict = verdict,
        reasonCodes = setOf(BookingReliabilityReasonCode.DECISION_UNAVAILABLE),
        triggers = emptyList(),
        noShowCount = 0,
        lateCancellationCount = 0,
        effectiveFrom = null,
        expiresAt = null,
        decisionDigest = "d".repeat(64),
    )

    private open class FakePort : BookingReliabilityApplicationPort {
        override fun evaluate(
            tenantGroupId: Long,
            clinicId: Long,
            memberId: MemberId,
            at: Instant,
            requestedPolicySnapshotId: Long?,
        ): BookingReliabilityDecisionRecord = gateDecision(memberId, BookingReliabilityVerdict.ELIGIBLE)

        override fun override(
            tenantGroupId: Long,
            clinicId: Long,
            memberId: MemberId,
            command: BookingReliabilityOverrideCommand,
        ): BookingReliabilityDecisionRecord = gateDecision(memberId, BookingReliabilityVerdict.OVERRIDDEN)

        override fun clear(
            tenantGroupId: Long,
            clinicId: Long,
            memberId: MemberId,
            command: BookingReliabilityClearCommand,
        ): BookingReliabilityDecisionRecord = gateDecision(memberId, BookingReliabilityVerdict.ELIGIBLE)

        override fun audit(
            tenantGroupId: Long,
            clinicId: Long,
            memberId: MemberId,
            cursor: String?,
            limit: Int,
        ): BookingReliabilityAuditPage = BookingReliabilityAuditPage(emptyList(), null)
    }

    private fun readySchemaReadiness() =
        DefaultBookingReliabilitySchemaReadiness {
            BookingReliabilitySchemaReadiness(
                migrationVersion = 17,
                requiredTablesPresent = true,
                requiredIndexesPresent = true,
                migrationCurrent = true,
            )
        }
}

private fun gateDecision(
    memberId: MemberId,
    verdict: BookingReliabilityVerdict,
    stamped: Boolean = false,
) = BookingReliabilityDecisionRecord(
    tenantGroupId = 10L,
    clinicId = 20L,
    memberId = memberId,
    policyVersionId = if (stamped) 7L else null,
    policyHash = if (stamped) "e".repeat(64) else null,
    evaluatedAt = Instant.parse("2026-08-01T03:00:00Z"),
    verdict = verdict,
    reasonCodes = setOf(BookingReliabilityReasonCode.DECISION_UNAVAILABLE),
    triggers = emptyList(),
    noShowCount = 0,
    lateCancellationCount = 0,
    effectiveFrom = null,
    expiresAt = null,
    decisionDigest = "d".repeat(64),
    decisionId = if (stamped) 77L else null,
)
