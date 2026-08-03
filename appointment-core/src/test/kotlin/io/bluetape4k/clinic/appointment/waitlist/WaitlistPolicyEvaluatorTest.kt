package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyDocument
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistPolicyCandidate
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistPolicyEvaluator
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistPolicyVacancy
import org.junit.jupiter.api.Test
import java.time.Instant

class WaitlistPolicyEvaluatorTest {
    private val evaluator = WaitlistPolicyEvaluator()

    @Test
    fun `restriction failure exits before scoring`() {
        val result = evaluator.evaluate(
            candidate = candidate(restricted = true),
            vacancy = vacancy(),
            policy = policy(),
        )

        result.eligible shouldBeEqualTo false
        result.reasonCodes shouldBeEqualTo listOf(WaitlistReasonCode("RESTRICTION_ACTIVE"))
        result.scoreTuple.shouldBeEmpty()
    }

    @Test
    fun `eligible candidate returns deterministic integer score tuple and policy digest`() {
        val result = evaluator.evaluate(
            candidate = candidate(
                urgencyScore = 7,
                recoveryCreditActive = true,
                benefitGrantActive = true,
                reliabilityScore = 93,
                waitingAgeMinutes = 120,
                slotFitScore = 80,
            ),
            vacancy = vacancy(),
            policy = policy(),
        )

        result.eligible shouldBeEqualTo true
        result.reasonCodes shouldBeEqualTo listOf(WaitlistReasonCode("ELIGIBLE"))
        result.scoreTuple shouldBeEqualTo listOf(70L, 3L, 2L, 372L, 720L, 400L)
        result.policyDigest shouldBeEqualTo WaitlistPolicyDocument.canonicalDigest(policy())
    }

    @Test
    fun `lexicographic urgency outranks a lower urgency aggregate total`() {
        evaluator.rank(
            candidates = listOf(
                candidate(
                    id = 20L,
                    urgencyScore = 9,
                    reliabilityScore = 0,
                    waitingAgeMinutes = 100,
                    slotFitScore = 0,
                ),
                candidate(
                    id = 10L,
                    urgencyScore = 10,
                    reliabilityScore = 0,
                    waitingAgeMinutes = 0,
                    slotFitScore = 0,
                ),
            ),
            vacancy = vacancy(),
            policy = policy(),
        ).map { it.entryId } shouldBeEqualTo listOf(10L, 20L)
    }

    @Test
    fun `ties are ranked by entry id ascending`() {
        evaluator.rank(
            candidates = listOf(candidate(id = 12L), candidate(id = 7L)),
            vacancy = vacancy(),
            policy = policy(),
        ).map { it.entryId } shouldBeEqualTo listOf(7L, 12L)
    }

    private fun policy(): WaitlistPolicyDocument =
        WaitlistPolicyDocument(
            urgencyWeight = 10,
            recoveryWeight = 3,
            benefitWeight = 2,
            reliabilityWeight = 4,
            waitingAgeWeight = 6,
            slotFitWeight = 5,
        )

    private fun candidate(
        id: Long = 1L,
        restricted: Boolean = false,
        urgencyScore: Int = 1,
        recoveryCreditActive: Boolean = false,
        benefitGrantActive: Boolean = false,
        reliabilityScore: Int = 1,
        waitingAgeMinutes: Long = 1L,
        slotFitScore: Int = 1,
    ): WaitlistPolicyCandidate =
        WaitlistPolicyCandidate(
            entryId = id,
            restricted = restricted,
            urgencyScore = urgencyScore,
            recoveryCreditActive = recoveryCreditActive,
            benefitGrantActive = benefitGrantActive,
            reliabilityScore = reliabilityScore,
            waitingAgeMinutes = waitingAgeMinutes,
            slotFitScore = slotFitScore,
        )

    private fun vacancy(): WaitlistPolicyVacancy =
        WaitlistPolicyVacancy(
            vacancyKey = "vacancy-1",
            generatedAt = Instant.parse("2026-08-03T10:00:00Z"),
        )
}
