package io.bluetape4k.clinic.appointment.service.reliability

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import io.bluetape4k.clinic.appointment.model.waitlist.DecisionStamp
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import io.bluetape4k.clinic.appointment.repository.BookingReliabilityRepository
import java.time.Instant

/**
 * 후보 page 전체의 booking reliability decision을 batch로 읽는 경계입니다.
 * 외부 evaluator를 호출하지 않고 caller transaction에 있는 immutable snapshot만 사용합니다.
 */
fun interface BookingReliabilityDecisionBatchPort {
    fun findLatestDecisionStamps(
        scope: WaitlistScope,
        memberIds: Collection<MemberId>,
        evaluatedAt: Instant,
    ): Map<MemberId, DecisionStamp>
}

/** local decision snapshot repository를 waitlist matcher에 연결하는 adapter입니다. */
class RepositoryBookingReliabilityDecisionBatchPort(
    private val repository: BookingReliabilityRepository,
) : BookingReliabilityDecisionBatchPort {
    override fun findLatestDecisionStamps(
        scope: WaitlistScope,
        memberIds: Collection<MemberId>,
        evaluatedAt: Instant,
    ): Map<MemberId, DecisionStamp> {
        val decisions = repository.findLatestDecisions(
            tenantGroupId = scope.tenantGroupId,
            clinicId = scope.clinicId,
            memberIds = memberIds,
            evaluatedAt = evaluatedAt,
        )
        return memberIds.distinct().mapNotNull { memberId ->
            val decision = decisions[memberId] ?: return@mapNotNull null
            if (decision.verdict !in AUTOMATIC_WAITLIST_VERDICTS) {
                return@mapNotNull null
            }
            val decisionId = decision.decisionId ?: return@mapNotNull null
            val policyVersionId = decision.policyVersionId ?: return@mapNotNull null
            val policyHash = decision.policyHash ?: return@mapNotNull null
            require(decision.tenantGroupId == scope.tenantGroupId && decision.clinicId == scope.clinicId) {
                "decision scope must match waitlist scope"
            }
            check(decision.memberId == memberId) { "decision member must match requested member" }
            memberId to DecisionStamp(
                scope = scope.copy(memberId = memberId),
                decisionId = decisionId,
                policyVersionId = policyVersionId,
                policyHash = policyHash,
                evaluationDigest = decision.decisionDigest,
                expiresAt = decision.expiresAt,
            )
        }.toMap()
    }

    private companion object {
        private val AUTOMATIC_WAITLIST_VERDICTS = setOf(
            BookingReliabilityVerdict.ELIGIBLE,
            BookingReliabilityVerdict.OVERRIDDEN,
            BookingReliabilityVerdict.POLICY_DISABLED,
        )
    }
}
