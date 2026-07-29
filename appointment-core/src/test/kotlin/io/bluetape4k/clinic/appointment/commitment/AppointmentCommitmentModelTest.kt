package io.bluetape4k.clinic.appointment.commitment

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitment
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecision
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecisionType
import io.bluetape4k.clinic.appointment.model.commitment.ConsentSubject
import io.bluetape4k.clinic.appointment.model.commitment.ConsentSubjectType
import io.bluetape4k.clinic.appointment.model.commitment.ProposalConsentSubject
import io.bluetape4k.clinic.appointment.service.ProposalHasher
import org.junit.jupiter.api.Test
import java.time.Instant

class AppointmentCommitmentModelTest {
    @Test
    fun `고객 요청은 제안 상태로 시작하고 정확한 제안 동의 후에만 확정한다`() {
        val proposal = proposal()
        val proposed =
            AppointmentCommitment(
                appointmentId = 10L,
                status = AppointmentCommitmentStatus.PROPOSED,
                origin = AppointmentOrigin.PATIENT,
                confirmedProposalId = null,
                effectivePolicySnapshotId = 31L,
                version = 1L,
            )
        val consent =
            ConsentDecision(
                subject =
                    ProposalConsentSubject(
                        proposalId = 81L,
                        proposalRevision = proposal.revision,
                        proposalHash = "proposal-hash",
                    ),
                decision = ConsentDecisionType.ACCEPTED,
                evidenceAuthority = "customer-app",
                evidenceId = "consent-1",
                evidenceHash = "evidence-hash",
                decidedAt = Instant.parse("2026-08-01T00:00:00Z"),
                actorRef = "patient:masked",
            )

        val confirmed =
            proposed.confirm(
                proposalId = 81L,
                proposal = proposal,
                proposalHash = "proposal-hash",
                consent = consent,
            )

        confirmed.status shouldBeEqualTo AppointmentCommitmentStatus.CONFIRMED
        confirmed.confirmedProposalId shouldBeEqualTo 81L
        confirmed.version shouldBeEqualTo 2L
    }

    @Test
    fun `다른 revision에 대한 고객 동의로 제안을 확정할 수 없다`() {
        val proposal = proposal()
        val commitment =
            AppointmentCommitment(
                appointmentId = 10L,
                status = AppointmentCommitmentStatus.PROPOSED,
                origin = AppointmentOrigin.PATIENT,
                confirmedProposalId = null,
                effectivePolicySnapshotId = 31L,
                version = 1L,
            )
        val staleConsent =
            ConsentDecision(
                subject =
                    ProposalConsentSubject(
                        proposalId = 81L,
                        proposalRevision = proposal.revision - 1,
                        proposalHash = "proposal-hash",
                    ),
                decision = ConsentDecisionType.ACCEPTED,
                evidenceAuthority = "customer-app",
                evidenceId = "consent-1",
                evidenceHash = "evidence-hash",
                decidedAt = Instant.parse("2026-08-01T00:00:00Z"),
                actorRef = "patient:masked",
            )

        assertFailsWith<IllegalArgumentException> {
            commitment.confirm(81L, proposal, "proposal-hash", staleConsent)
        }
    }

    @Test
    fun `상품 전환 동의는 예약 제안 동의로 사용할 수 없다`() {
        val proposal = proposal()
        val commitment =
            AppointmentCommitment(
                appointmentId = 10L,
                status = AppointmentCommitmentStatus.PROPOSED,
                origin = AppointmentOrigin.PATIENT,
                confirmedProposalId = null,
                effectivePolicySnapshotId = 31L,
                version = 1L,
            )
        val wrongSubject =
            ConsentDecision(
                subject =
                    object : ConsentSubject {
                        override val type: ConsentSubjectType = ConsentSubjectType.PRODUCT_VERSION_MIGRATION
                    },
                decision = ConsentDecisionType.ACCEPTED,
                evidenceAuthority = "product-service",
                evidenceId = "migration-consent",
                evidenceHash = "evidence-hash",
                decidedAt = Instant.parse("2026-08-01T00:00:00Z"),
                actorRef = "patient:masked",
            )

        assertFailsWith<IllegalArgumentException> {
            commitment.confirm(81L, proposal, "proposal-hash", wrongSubject)
        }
    }

    @Test
    fun `proposal hash는 같은 제안에 결정적이고 일정이 달라지면 변경된다`() {
        val proposal = proposal()

        ProposalHasher.hash(proposal) shouldBeEqualTo ProposalHasher.hash(proposal())
        val changed =
            AppointmentProposalDraft(
                appointmentId = proposal.appointmentId,
                revision = proposal.revision,
                startsAt = proposal.startsAt,
                endsAt = proposal.endsAt.plusSeconds(60),
                items = proposal.items,
                allocations = proposal.allocations,
                policySnapshotId = proposal.policySnapshotId,
                supersedesProposalId = proposal.supersedesProposalId,
            )
        (ProposalHasher.hash(proposal) == ProposalHasher.hash(changed)) shouldBeEqualTo
            false
    }

    private fun proposal(): AppointmentProposalDraft =
        AppointmentProposalDraft(
            appointmentId = 10L,
            revision = 3L,
            startsAt = Instant.parse("2026-08-10T01:00:00Z"),
            endsAt = Instant.parse("2026-08-10T02:00:00Z"),
            items = emptyList(),
            allocations = emptyList(),
            policySnapshotId = 31L,
            supersedesProposalId = null,
        )
}
