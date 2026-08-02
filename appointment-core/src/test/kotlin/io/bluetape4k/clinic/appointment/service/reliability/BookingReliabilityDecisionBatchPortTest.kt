package io.bluetape4k.clinic.appointment.service.reliability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityTrigger
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityTriggerType
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityDecisions
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityEvents
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityOverrides
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.BookingReliabilityRepository
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

class BookingReliabilityDecisionBatchPortTest : AbstractExposedTest() {
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one scoped batch returns latest eligible stamp and omits unavailable members`(testDB: TestDB) {
        withTables(testDB, BookingReliabilityEvents, BookingReliabilityDecisions, BookingReliabilityOverrides) {
            val repository = BookingReliabilityRepository()
            val memberOne = MemberId("member-batch-1")
            val memberTwo = MemberId("member-batch-2")
            val evaluatedAt = Instant.parse("2026-08-01T10:00:00Z")
            repository.saveDecision(decision(memberOne, evaluatedAt.minusSeconds(60), "a"))
            repository.saveDecision(decision(memberOne, evaluatedAt.minusSeconds(30), "b"))
            repository.saveDecision(decision(memberTwo, evaluatedAt.minusSeconds(20), "c"))

            val port = RepositoryBookingReliabilityDecisionBatchPort(repository)
            val scope = WaitlistScope(1L, 2L, memberOne)
            val stamps = port.findLatestDecisionStamps(scope, listOf(memberOne), evaluatedAt)

            stamps.keys shouldBeEqualTo setOf(memberOne)
            stamps.getValue(memberOne).evaluationDigest shouldBeEqualTo "b".repeat(64)

            port.findLatestDecisionStamps(scope, listOf(MemberId("member-missing")), evaluatedAt) shouldBeEqualTo emptyMap()

            repository.saveDecision(
                decision(
                    memberId = memberOne,
                    evaluatedAt = evaluatedAt.plusSeconds(1),
                    marker = "c",
                    verdict = BookingReliabilityVerdict.RESTRICTED,
                ),
            )
            port.findLatestDecisionStamps(scope, listOf(memberOne), evaluatedAt.plusSeconds(2)) shouldBeEqualTo emptyMap()
        }
    }

    private fun decision(
        memberId: MemberId,
        evaluatedAt: Instant,
        marker: String,
        verdict: BookingReliabilityVerdict = BookingReliabilityVerdict.ELIGIBLE,
    ) =
        BookingReliabilityDecisionRecord(
            tenantGroupId = 1L,
            clinicId = 2L,
            memberId = memberId,
            policyVersionId = 7L,
            policyHash = "f".repeat(64),
            evaluatedAt = evaluatedAt,
            verdict = verdict,
            reasonCodes = setOf(BookingReliabilityReasonCode.MANUAL_CLEAR),
            triggers = listOf(BookingReliabilityTrigger(1L, BookingReliabilityTriggerType.NO_SHOW)),
            noShowCount = 0,
            lateCancellationCount = 0,
            effectiveFrom = evaluatedAt,
            expiresAt = evaluatedAt.plusSeconds(3_600),
            decisionDigest = marker.repeat(64),
        )
}
