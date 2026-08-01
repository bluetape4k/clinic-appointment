package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitment
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentModelVersion
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecision
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecisionType
import io.bluetape4k.clinic.appointment.model.commitment.ProposalConsentSubject
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class AppointmentCommitmentRepositoryTest {
    private val repository = AppointmentCommitmentRepository()

    @Test
    fun `repository는 caller transaction 밖에서 사용할 수 없다`() {
        assertFailsWith<IllegalStateException> {
            repository.findByAppointmentId(1L)
        }
    }

    @Test
    fun `commitment proposal consent를 append하고 version CAS로 확정한다`() {
        withCommitmentTables { seed ->
            val commitment =
                repository.create(
                    AppointmentCommitment(
                        appointmentId = seed.appointmentId,
                        status = AppointmentCommitmentStatus.PROPOSED,
                        origin = AppointmentOrigin.PATIENT,
                        confirmedProposalId = null,
                        effectivePolicySnapshotId = 7L,
                        version = 1L,
                    ),
                )
            val draft = proposal(seed.appointmentId)
            val proposal =
                repository.appendProposal(
                    commitmentId = commitment.id,
                    draft = draft,
                    proposalHash = "p".repeat(64),
                    expiresAt = draft.startsAt.minusSeconds(60),
                    representativeTreatmentName = "복합 진료",
                    createdByActor = "patient",
                )
            repository.appendConsent(
                commitmentId = commitment.id,
                decision =
                    ConsentDecision(
                        subject = ProposalConsentSubject(proposal.id, proposal.revision, proposal.proposalHash),
                        decision = ConsentDecisionType.ACCEPTED,
                        evidenceAuthority = "customer-app",
                        evidenceId = "consent-1",
                        evidenceHash = "e".repeat(64),
                        decidedAt = Instant.parse("2026-08-02T00:00:00Z"),
                        actorRef = "patient:masked",
                        evidenceType = "SIGNED_FORM",
                        termsHash = "a".repeat(64),
                    ),
            )

            val savedDecision =
                repository
                    .findLatestProposalDecision(
                        commitmentId = commitment.id,
                        proposalId = proposal.id,
                        proposalRevision = proposal.revision,
                        proposalHash = proposal.proposalHash,
                    ).shouldNotBeNull()
            savedDecision.evidenceType shouldBeEqualTo "SIGNED_FORM"
            savedDecision.termsHash shouldBeEqualTo "a".repeat(64)

            repository.confirmByVersion(commitment.id, expectedVersion = 1L, proposal.id).shouldBeTrue()
            repository.confirmByVersion(commitment.id, expectedVersion = 1L, proposal.id).shouldBeFalse()

            val found = repository.findByAppointmentId(seed.appointmentId).shouldNotBeNull()
            found.status shouldBeEqualTo AppointmentCommitmentStatus.CONFIRMED
            found.confirmedProposalId shouldBeEqualTo proposal.id
            found.version shouldBeEqualTo 2L
        }
    }

    @Test
    fun `confirm CAS는 기존 reliability decision stamp가 바뀌면 실패한다`() {
        withCommitmentTables { seed ->
            val expectedStamp = BookingReliabilityDecisionStamp(
                decisionId = 11L,
                policyVersionId = 7L,
                policyHash = "a".repeat(64),
                evaluationDigest = "b".repeat(64),
                expiresAt = Instant.parse("2026-08-10T00:00:00Z"),
            )
            val replacementStamp = expectedStamp.copy(
                decisionId = 12L,
                evaluationDigest = "c".repeat(64),
            )
            val commitment = repository.create(
                AppointmentCommitment(
                    appointmentId = seed.appointmentId,
                    status = AppointmentCommitmentStatus.PROPOSED,
                    origin = AppointmentOrigin.PATIENT,
                    confirmedProposalId = null,
                    effectivePolicySnapshotId = 7L,
                    version = 1L,
                    bookingReliabilityStamp = expectedStamp,
                ),
            )
            val proposal = repository.appendProposal(
                commitmentId = commitment.id,
                draft = proposal(seed.appointmentId),
                proposalHash = "p".repeat(64),
                expiresAt = Instant.parse("2026-08-10T00:30:00Z"),
                representativeTreatmentName = "복합 진료",
                createdByActor = "patient",
            )

            repository.confirmByVersion(
                commitmentId = commitment.id,
                expectedVersion = 1L,
                proposalId = proposal.id,
                bookingReliabilityStamp = replacementStamp,
                expectedBookingReliabilityStamp = replacementStamp,
            ).shouldBeFalse()

            repository.findByAppointmentId(seed.appointmentId)
                .shouldNotBeNull()
                .status shouldBeEqualTo AppointmentCommitmentStatus.PROPOSED
        }
    }

    @Test
    fun `commitment v2 예약은 projection 완성 여부와 무관하게 legacy 조회에서 제외한다`() {
        withCommitmentTables { seed ->
            val incompleteAppointmentId =
                Appointments
                    .insertAndGetId {
                        it[clinicId] = seed.clinicId
                        it[modelVersion] = AppointmentModelVersion.COMMITMENT_V2
                        it[patientName] = "Pending Patient"
                    }.value
            val seedRow = Appointments.selectAll()
                .where { Appointments.id eq seed.appointmentId }
                .single()
            val confirmedAppointmentId =
                Appointments
                    .insertAndGetId {
                        it[clinicId] = seed.clinicId
                        it[doctorId] = seedRow[Appointments.doctorId]
                        it[treatmentTypeId] = seedRow[Appointments.treatmentTypeId]
                        it[modelVersion] = AppointmentModelVersion.COMMITMENT_V2
                        it[patientName] = "Confirmed Patient"
                        it[patientReferenceFingerprint] = "c".repeat(64)
                        it[appointmentDate] = LocalDate.of(2026, 8, 10)
                        it[startTime] = java.time.LocalTime.of(11, 0)
                        it[endTime] = java.time.LocalTime.of(11, 30)
                    }.value
            val appointmentRepository = AppointmentRepository()

            appointmentRepository.findByIdAndTenant(incompleteAppointmentId, 1L) shouldBeEqualTo null
            appointmentRepository.findByIdAndTenant(confirmedAppointmentId, 1L) shouldBeEqualTo null
            appointmentRepository.isCommitmentV2(incompleteAppointmentId, 1L).shouldBeTrue()
            appointmentRepository.isCommitmentV2(confirmedAppointmentId, 1L).shouldBeTrue()
            appointmentRepository.isCommitmentV2(seed.appointmentId, 1L).shouldBeFalse()
            appointmentRepository
                .findByClinicAndDateRange(
                    clinicId = seed.clinicId,
                    dateRange = LocalDate.of(2026, 8, 10)..LocalDate.of(2026, 8, 10),
                ).map { it.id } shouldBeEqualTo listOf(seed.appointmentId)
        }
    }

    @Test
    fun `appointment에는 commitment 하나와 proposal revision 하나씩만 허용한다`() {
        withCommitmentTables { seed ->
            val commitment =
                repository.create(
                    AppointmentCommitment(
                        seed.appointmentId,
                        AppointmentCommitmentStatus.PROPOSED,
                        AppointmentOrigin.CLINIC,
                        null,
                        7L,
                        1L,
                    ),
                )
            assertFailsWith<ExposedSQLException> {
                repository.create(
                    AppointmentCommitment(
                        seed.appointmentId,
                        AppointmentCommitmentStatus.PROPOSED,
                        AppointmentOrigin.CLINIC,
                        null,
                        7L,
                        1L,
                    ),
                )
            }
            val draft = proposal(seed.appointmentId)
            repository.appendProposal(commitment.id, draft, "a".repeat(64), draft.endsAt, "진료", "clinic")
            assertFailsWith<ExposedSQLException> {
                repository.appendProposal(commitment.id, draft, "b".repeat(64), draft.endsAt, "진료", "clinic")
            }
        }
    }

    private fun proposal(appointmentId: Long) =
        AppointmentProposalDraft(
            appointmentId = appointmentId,
            revision = 1L,
            startsAt = Instant.parse("2026-08-10T01:00:00Z"),
            endsAt = Instant.parse("2026-08-10T02:00:00Z"),
            items = emptyList(),
            allocations = emptyList(),
            policySnapshotId = 7L,
            supersedesProposalId = null,
        )
}
