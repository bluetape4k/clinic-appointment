package io.bluetape4k.clinic.appointment.service.waitlist

import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyDocument
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

/** waitlist policy 평가에 필요한 후보 snapshot입니다. */
data class WaitlistPolicyCandidate(
    val entryId: Long,
    val restricted: Boolean,
    val urgencyScore: Int,
    val recoveryCreditActive: Boolean,
    val benefitGrantActive: Boolean,
    val reliabilityScore: Int,
    val waitingAgeMinutes: Long,
    val slotFitScore: Int,
) : Serializable {
    init {
        entryId.requirePositiveNumber("entryId")
        urgencyScore.requirePolicySignal("urgencyScore")
        reliabilityScore.requirePolicySignal("reliabilityScore")
        require(waitingAgeMinutes >= 0L) { "waitingAgeMinutes must be zero or positive" }
        slotFitScore.requirePolicySignal("slotFitScore")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** waitlist policy 평가 시 audit에 남길 vacancy snapshot 최소 범위입니다. */
data class WaitlistPolicyVacancy(
    val vacancyKey: String,
    val generatedAt: Instant,
) : Serializable {
    init {
        vacancyKey.requireNotBlank("vacancyKey")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** waitlist policy 평가 결과입니다. */
data class WaitlistPolicyDecision(
    val entryId: Long,
    val eligible: Boolean,
    val reasonCodes: List<WaitlistReasonCode>,
    val scoreTuple: List<Long>,
    val policyDigest: String,
) : Serializable {
    init {
        entryId.requirePositiveNumber("entryId")
        require(reasonCodes.isNotEmpty()) { "reasonCodes must not be empty" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * waitlist 자동 delivery 후보를 deterministic integer tuple로 평가한다.
 *
 * hard eligibility 실패는 점수 계산보다 먼저 종료해 offer 선점 경합과 audit replay가 같은
 * reason code를 관찰하게 한다. 동점은 [WaitlistPolicyDecision.entryId] 오름차순으로 정렬한다.
 */
class WaitlistPolicyEvaluator {
    fun evaluate(
        candidate: WaitlistPolicyCandidate,
        vacancy: WaitlistPolicyVacancy,
        policy: WaitlistPolicyDocument,
    ): WaitlistPolicyDecision {
        val policyDigest = WaitlistPolicyDocument.canonicalDigest(policy)
        if (candidate.restricted) {
            return WaitlistPolicyDecision(
                entryId = candidate.entryId,
                eligible = false,
                reasonCodes = listOf(WaitlistReasonCode("RESTRICTION_ACTIVE")),
                scoreTuple = emptyList(),
                policyDigest = policyDigest,
            )
        }

        val urgencyScore = candidate.urgencyScore.toLong() * policy.urgencyWeight
        val recoveryScore = if (candidate.recoveryCreditActive) policy.recoveryWeight.toLong() else 0L
        val benefitScore = if (candidate.benefitGrantActive) policy.benefitWeight.toLong() else 0L
        val reliabilityScore = candidate.reliabilityScore.toLong() * policy.reliabilityWeight
        val waitingAgeScore = candidate.waitingAgeMinutes * policy.waitingAgeWeight
        val slotFitScore = candidate.slotFitScore.toLong() * policy.slotFitWeight
        return WaitlistPolicyDecision(
            entryId = candidate.entryId,
            eligible = true,
            reasonCodes = listOf(WaitlistReasonCode("ELIGIBLE")),
            scoreTuple = listOf(
                urgencyScore,
                recoveryScore,
                benefitScore,
                reliabilityScore,
                waitingAgeScore,
                slotFitScore,
            ),
            policyDigest = policyDigest,
        )
    }

    fun rank(
        candidates: List<WaitlistPolicyCandidate>,
        vacancy: WaitlistPolicyVacancy,
        policy: WaitlistPolicyDocument,
    ): List<WaitlistPolicyDecision> =
        candidates
            .map { candidate -> evaluate(candidate, vacancy, policy) }
            .filter { decision -> decision.eligible }
            .sortedWith(
                compareByDescending<WaitlistPolicyDecision> { decision -> decision.scoreTuple[0] }
                    .thenByDescending { decision -> decision.scoreTuple[1] }
                    .thenByDescending { decision -> decision.scoreTuple[2] }
                    .thenByDescending { decision -> decision.scoreTuple[3] }
                    .thenByDescending { decision -> decision.scoreTuple[4] }
                    .thenByDescending { decision -> decision.scoreTuple[5] }
                    .thenBy { decision -> decision.entryId },
            )
}

private fun Int.requirePolicySignal(name: String): Int {
    require(this in 0..100) { "$name must be between 0 and 100" }
    return this
}
