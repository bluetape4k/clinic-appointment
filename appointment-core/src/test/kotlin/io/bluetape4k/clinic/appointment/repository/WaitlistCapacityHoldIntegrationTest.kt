package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitment
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCapacityHolds
import io.bluetape4k.clinic.appointment.model.tables.WaitlistEntries
import io.bluetape4k.clinic.appointment.model.tables.WaitlistOffers
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.NewHold
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCapacityHoldState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistScope
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class WaitlistCapacityHoldIntegrationTest {
    private val commitmentRepository = AppointmentCommitmentRepository()
    private val resourceRepository = ResourceAllocationRepository()

    @Test
    fun `offered hold blocks a confirmed allocation on the same resource`() {
        withCommitmentTables { seed ->
            val scope = WaitlistScope(1L, seed.clinicId, io.bluetape4k.clinic.appointment.model.identity.MemberId("member-1"))
            val offerId = createOffer(scope)
            val hold = newHold(resourceId = "doctor-held")
            resourceRepository.lockAndValidateWaitlistCapacity(scope, hold)
            resourceRepository.reserveWaitlistCapacityHold(scope, offerId, hold)

            val proposalId = createProposal(seed.appointmentId, revision = 1L)
            assertFailsWith<ResourceAllocationConflictException> {
                resourceRepository.createConfirmedAllocations(
                    tenantGroupId = scope.tenantGroupId,
                    clinicId = scope.clinicId,
                    proposalId = proposalId,
                    replacingProposalId = null,
                    requests = listOf(allocation("doctor-held")),
                )
            }
            resourceRepository.findByProposal(proposalId).size shouldBeEqualTo 0
        }
    }

    @Test
    fun `accepted hold is consumed only after replacement allocation succeeds`() {
        withCommitmentTables { seed ->
            val scope = WaitlistScope(1L, seed.clinicId, io.bluetape4k.clinic.appointment.model.identity.MemberId("member-2"))
            val offerId = createOffer(scope)
            val hold = newHold(resourceId = "doctor-accepted")
            resourceRepository.lockAndValidateWaitlistCapacity(scope, hold)
            val saved = resourceRepository.reserveWaitlistCapacityHold(scope, offerId, hold)
            WaitlistCapacityHolds.update({ WaitlistCapacityHolds.id eq saved.id }) {
                it[status] = WaitlistCapacityHoldState.ACCEPTED
            }

            val proposalId = createProposal(seed.appointmentId, revision = 1L)
            assertFailsWith<ResourceAllocationConflictException> {
                resourceRepository.createConfirmedAllocations(
                    tenantGroupId = scope.tenantGroupId,
                    clinicId = scope.clinicId,
                    proposalId = proposalId,
                    replacingProposalId = null,
                    requests = listOf(allocation("doctor-accepted")),
                )
            }
            val beforeConsume = WaitlistCapacityHolds
                .selectAll()
                .where { WaitlistCapacityHolds.id eq saved.id }
                .single()
            beforeConsume[WaitlistCapacityHolds.status] shouldBeEqualTo WaitlistCapacityHoldState.ACCEPTED

            resourceRepository.consumeWaitlistCapacityHold(scope, saved.id, expectedVersion = 0L)
                .shouldBeEqualTo(true)
            WaitlistCapacityHolds
                .selectAll()
                .where { WaitlistCapacityHolds.id eq saved.id }
                .single()[WaitlistCapacityHolds.status] shouldBeEqualTo WaitlistCapacityHoldState.CONSUMED
        }
    }

    @Test
    fun `released or expired hold returns capacity without deleting audit row`() {
        withCommitmentTables { seed ->
            val scope = WaitlistScope(1L, seed.clinicId, io.bluetape4k.clinic.appointment.model.identity.MemberId("member-3"))
            val offerId = createOffer(scope)
            val hold = newHold(resourceId = "doctor-release")
            resourceRepository.lockAndValidateWaitlistCapacity(scope, hold)
            val saved = resourceRepository.reserveWaitlistCapacityHold(scope, offerId, hold)

            resourceRepository.releaseWaitlistCapacityHold(
                scope = scope,
                holdId = saved.id,
                terminal = WaitlistCapacityHoldState.RELEASED,
                releasedAt = Instant.parse("2026-08-01T09:00:00Z"),
            ).shouldBeEqualTo(true)

            WaitlistCapacityHolds
                .selectAll()
                .where { WaitlistCapacityHolds.id eq saved.id }
                .single()[WaitlistCapacityHolds.status] shouldBeEqualTo WaitlistCapacityHoldState.RELEASED
            WaitlistCapacityHolds
                .selectAll()
                .where {
                    (WaitlistCapacityHolds.resourceId eq "doctor-release") and
                        (WaitlistCapacityHolds.status eq WaitlistCapacityHoldState.OFFERED)
                }
                .count() shouldBeEqualTo 0L
        }
    }

    private fun createOffer(scope: WaitlistScope): Long {
        val treatmentTypeId = TreatmentTypes.selectAll().orderBy(TreatmentTypes.id to SortOrder.ASC).limit(1).single()[TreatmentTypes.id]
        val entryId = WaitlistEntries.insertAndGetId {
            it[tenantGroupId] = scope.tenantGroupId
            it[clinicId] = scope.clinicId
            it[memberId] = scope.memberId.value
            it[WaitlistEntries.treatmentTypeId] = treatmentTypeId
            it[preferredDateFrom] = LocalDate.of(2026, 8, 1)
            it[preferredDateTo] = LocalDate.of(2026, 8, 1)
            it[preferredStartTime] = LocalTime.of(9, 0)
            it[preferredEndTime] = LocalTime.of(10, 0)
            it[priorityRank] = 1
            it[status] = io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState.OFFERED
            it[waitingSince] = Instant.parse("2026-08-01T08:00:00Z")
            it[version] = 0L
            it[createdAt] = Instant.parse("2026-08-01T08:00:00Z")
            it[updatedAt] = Instant.parse("2026-08-01T08:00:00Z")
        }.value
        return WaitlistOffers.insertAndGetId {
            it[tenantGroupId] = scope.tenantGroupId
            it[clinicId] = scope.clinicId
            it[memberId] = scope.memberId.value
            it[waitlistEntryId] = entryId
            it[vacancyKey] = "vacancy-$entryId"
            it[activeEntryKey] = "entry-$entryId"
            it[activeVacancyKey] = "vacancy-$entryId"
            it[WaitlistOffers.resourceType] = ResourceType.PRACTITIONER
            it[WaitlistOffers.resourceId] = "doctor-1"
            it[WaitlistOffers.capacityUnits] = 1
            it[WaitlistOffers.maximumCapacity] = 1
            it[doctorId] = 1L
            it[WaitlistOffers.treatmentTypeId] = treatmentTypeId.value
            it[startsAt] = Instant.parse("2026-08-01T09:00:00Z")
            it[endsAt] = Instant.parse("2026-08-01T10:00:00Z")
            it[expiresAt] = Instant.parse("2026-08-01T08:30:00Z")
            it[status] = io.bluetape4k.clinic.appointment.model.waitlist.WaitlistOfferState.OFFERED
            it[bookingReliabilityDecisionId] = 70L
            it[bookingReliabilityPolicyVersionId] = 80L
            it[bookingReliabilityPolicyHash] = "a".repeat(64)
            it[bookingReliabilityEvaluationDigest] = "b".repeat(64)
            it[candidateRank] = 1
            it[selectionReasonCode] = WaitlistReasonCode.noEligibleCandidate.code
            it[version] = 0L
            it[createdAt] = Instant.parse("2026-08-01T08:00:00Z")
            it[updatedAt] = Instant.parse("2026-08-01T08:00:00Z")
        }.value
    }

    private fun newHold(resourceId: String) = NewHold(
        vacancyKey = "vacancy-$resourceId",
        activeVacancyKey = "active-$resourceId",
        resourceType = ResourceType.PRACTITIONER,
        resourceId = resourceId,
        startsAt = Instant.parse("2026-08-01T09:00:00Z"),
        endsAt = Instant.parse("2026-08-01T10:00:00Z"),
        capacityUnits = 1,
        maximumCapacity = 1,
        holdExpiresAt = Instant.parse("2026-08-01T08:30:00Z"),
    )

    private fun allocation(resourceId: String) = ResourceAllocationRequest(
        allocation = ResourceAllocationDraft(
            resourceType = ResourceType.PRACTITIONER,
            resourceId = resourceId,
            startsAt = Instant.parse("2026-08-01T09:00:00Z"),
            endsAt = Instant.parse("2026-08-01T10:00:00Z"),
            capacityUnits = 1,
            maximumCapacity = 1,
            allocationMode = ResourceAllocationMode.EXCLUSIVE,
            appointmentItemKey = null,
        ),
        maximumCapacity = 1,
    )

    private fun createProposal(appointmentId: Long, revision: Long): Long {
        val commitment = commitmentRepository.findByAppointmentId(appointmentId)
            ?: commitmentRepository.create(
                AppointmentCommitment(
                    appointmentId = appointmentId,
                    status = AppointmentCommitmentStatus.PROPOSED,
                    origin = AppointmentOrigin.CLINIC,
                    confirmedProposalId = null,
                    effectivePolicySnapshotId = 7L,
                    version = 1L,
                ),
            )
        val startsAt = Instant.parse("2026-08-01T09:00:00Z")
        return commitmentRepository.appendProposal(
            commitmentId = commitment.id,
            draft = AppointmentProposalDraft(
                appointmentId = appointmentId,
                revision = revision,
                startsAt = startsAt,
                endsAt = startsAt.plusSeconds(3_600),
                items = emptyList(),
                allocations = emptyList(),
                policySnapshotId = 7L,
                supersedesProposalId = null,
                bookingReliabilityStamp = null,
            ),
            proposalHash = "p".repeat(64),
            expiresAt = startsAt.plusSeconds(1_800),
            representativeTreatmentName = "treatment",
            createdByActor = ActorRef("SYSTEM").value,
        ).id
    }
}
