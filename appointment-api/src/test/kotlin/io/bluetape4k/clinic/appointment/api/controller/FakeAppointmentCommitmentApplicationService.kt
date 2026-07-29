package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentCommitmentResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentProposalResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentProposalSummary
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentPolicySnapshotSummary
import io.bluetape4k.clinic.appointment.api.dto.commitment.ApproveProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CancelAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateAppointmentRequestV2
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateChangeProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DeclineProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectConfirmRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectCreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.ProposalDecisionRequest
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import java.time.Instant

/**
 * controller contract test가 transport와 actor 전달만 관찰하도록 하는 최소 test double이다.
 */
internal open class FakeAppointmentCommitmentApplicationService : AppointmentCommitmentApplicationService {
    var lastActor: ActorContext? = null
    var lastIdempotencyKey: String? = null
    var lastExpectedVersion: Long? = null

    override fun requestAppointment(
        actor: ActorContext,
        idempotencyKey: String,
        createOnly: Boolean,
        request: CreateAppointmentRequestV2,
    ): AppointmentProposalResponse {
        capture(actor, idempotencyKey)
        return proposalResponse
    }

    override fun directCreate(
        actor: ActorContext,
        idempotencyKey: String,
        createOnly: Boolean,
        request: DirectCreateAppointmentRequest,
    ): AppointmentCommitmentResponse {
        capture(actor, idempotencyKey)
        return commitmentResponse
    }

    override fun approveProposal(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: ApproveProposalRequest,
    ): AppointmentCommitmentResponse {
        capture(actor, idempotencyKey, expectedVersion)
        return commitmentResponse
    }

    override fun decideProposal(
        actor: ActorContext,
        appointmentId: Long,
        proposalId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: ProposalDecisionRequest,
    ): AppointmentCommitmentResponse {
        capture(actor, idempotencyKey, expectedVersion)
        return commitmentResponse
    }

    override fun declineProposal(
        actor: ActorContext,
        appointmentId: Long,
        proposalId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: DeclineProposalRequest,
    ): AppointmentCommitmentResponse {
        capture(actor, idempotencyKey, expectedVersion)
        return commitmentResponse
    }

    override fun directConfirm(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: DirectConfirmRequest,
    ): AppointmentCommitmentResponse {
        capture(actor, idempotencyKey, expectedVersion)
        return commitmentResponse
    }

    override fun createChangeProposal(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: CreateChangeProposalRequest,
    ): AppointmentProposalResponse {
        capture(actor, idempotencyKey, expectedVersion)
        return proposalResponse
    }

    override fun query(
        actor: ActorContext,
        appointmentId: Long,
    ): AppointmentCommitmentResponse {
        capture(actor)
        return commitmentResponse
    }

    override fun expireProposal(
        actor: ActorContext,
        appointmentId: Long,
        proposalId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
    ): AppointmentCommitmentResponse {
        capture(actor, idempotencyKey, expectedVersion)
        return commitmentResponse
    }

    override fun cancelAppointment(
        actor: ActorContext,
        appointmentId: Long,
        expectedVersion: Long,
        idempotencyKey: String,
        request: CancelAppointmentRequest,
    ): AppointmentCommitmentResponse {
        capture(actor, idempotencyKey, expectedVersion)
        return commitmentResponse
    }

    private fun capture(
        actor: ActorContext,
        idempotencyKey: String? = null,
        expectedVersion: Long? = null,
    ) {
        lastActor = actor
        lastIdempotencyKey = idempotencyKey
        lastExpectedVersion = expectedVersion
    }

    companion object {
        private val policySnapshot =
            AppointmentPolicySnapshotSummary(
                snapshotId = 41L,
                snapshotHash = "a".repeat(64),
                tenantGeneration = 1L,
                clinicGeneration = 0L,
                sourceVersions = emptyMap(),
            )

        val proposalResponse = AppointmentProposalResponse(
            appointmentId = 11L,
            commitmentId = 21L,
            proposalId = 31L,
            status = AppointmentCommitmentStatus.PROPOSED,
            version = 1L,
            expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
            policySnapshot = policySnapshot,
        )

        val commitmentResponse = AppointmentCommitmentResponse(
            appointmentId = 11L,
            commitmentId = 21L,
            status = AppointmentCommitmentStatus.CONFIRMED,
            version = 2L,
            currentProposal = AppointmentProposalSummary(
                proposalId = 31L,
                revision = 1L,
                startsAt = Instant.parse("2026-08-01T01:00:00Z"),
                endsAt = Instant.parse("2026-08-01T02:00:00Z"),
                expiresAt = Instant.parse("2026-08-01T00:00:00Z"),
                expired = false,
                representativeTreatmentName = "Laser treatment",
                policySnapshot = policySnapshot,
            ),
            confirmedProposalId = 31L,
            effectivePolicySnapshotId = 41L,
        )

    }
}
