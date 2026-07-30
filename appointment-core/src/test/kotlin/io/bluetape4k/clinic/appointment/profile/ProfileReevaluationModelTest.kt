package io.bluetape4k.clinic.appointment.profile

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import io.bluetape4k.clinic.appointment.model.profile.isProfileReevaluationEligible
import org.junit.jupiter.api.Test

class ProfileReevaluationModelTest {

    @Test
    fun `확정 예약은 재평가 대상이 아니다`() {
        AppointmentCommitmentStatus.CONFIRMED.isProfileReevaluationEligible shouldBeEqualTo false
        AppointmentCommitmentStatus.PROPOSED.isProfileReevaluationEligible shouldBeEqualTo true
        AppointmentCommitmentStatus.HELD.isProfileReevaluationEligible shouldBeEqualTo true
        AppointmentCommitmentStatus.EXPIRED.isProfileReevaluationEligible shouldBeEqualTo false
        AppointmentCommitmentStatus.CANCELLED.isProfileReevaluationEligible shouldBeEqualTo false
    }

    @Test
    fun `작업 상태와 예약별 결과는 승인된 닫힌 집합만 노출한다`() {
        ProfileReevaluationJobStatus.entries.map { it.name } shouldBeEqualTo
            listOf("PENDING", "RUNNING", "RETRY_WAIT", "COMPLETED", "STALE", "FAILED")
        ProfileReevaluationOutcomeType.entries.map { it.name } shouldBeEqualTo
            listOf(
                "PROPOSAL_SUPERSEDED",
                "HOLD_KEPT",
                "HOLD_REPLACED",
                "FALLBACK_TO_PROPOSED",
                "SKIPPED_INELIGIBLE",
                "SKIPPED_UNCHANGED",
            )
    }
}
