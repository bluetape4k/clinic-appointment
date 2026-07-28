package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitment
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import org.junit.jupiter.api.Test
import java.time.Instant

class ResourceAllocationRepositoryTest {

    private val commitmentRepository = AppointmentCommitmentRepository()
    private val repository = ResourceAllocationRepository()

    @Test
    fun `겹치는 전담 자원을 거부하고 기존 활성 allocation을 보존한다`() {
        withCommitmentTables { seed ->
            val firstProposalId = createProposal(seed.appointmentId, 1L)
            val secondProposalId = createProposal(seed.appointmentId, 2L)
            val request = allocation("doctor-1", ResourceAllocationMode.EXCLUSIVE)

            repository.replaceConfirmedAllocations(
                tenantGroupId = 1L,
                clinicId = seed.clinicId,
                proposalId = firstProposalId,
                replacingProposalId = null,
                requests = listOf(request),
            )
            assertFailsWith<ResourceAllocationConflictException> {
                repository.replaceConfirmedAllocations(
                    tenantGroupId = 1L,
                    clinicId = seed.clinicId,
                    proposalId = secondProposalId,
                    replacingProposalId = null,
                    requests = listOf(request),
                )
            }

            repository.findByProposal(firstProposalId).single().status shouldBeEqualTo
                ResourceAllocationStatus.ACTIVE
            repository.findByProposal(secondProposalId) shouldHaveSize 0
        }
    }

    @Test
    fun `확정 proposal 교체에서는 기존 allocation을 집계에서 제외하고 성공 후 해제한다`() {
        withCommitmentTables { seed ->
            val firstProposalId = createProposal(seed.appointmentId, 1L)
            val secondProposalId = createProposal(seed.appointmentId, 2L)
            val request = allocation("doctor-1", ResourceAllocationMode.EXCLUSIVE)
            repository.replaceConfirmedAllocations(1L, seed.clinicId, firstProposalId, null, listOf(request))

            repository.replaceConfirmedAllocations(
                1L,
                seed.clinicId,
                secondProposalId,
                firstProposalId,
                listOf(request),
            )

            repository.findByProposal(firstProposalId).single().status shouldBeEqualTo
                ResourceAllocationStatus.RELEASED
            repository.findByProposal(secondProposalId).single().status shouldBeEqualTo
                ResourceAllocationStatus.ACTIVE
        }
    }

    @Test
    fun `capacity bucket 사용량 합계가 상한을 넘으면 전체 요청을 거부한다`() {
        withCommitmentTables { seed ->
            val firstProposalId = createProposal(seed.appointmentId, 1L)
            val secondProposalId = createProposal(seed.appointmentId, 2L)
            repository.replaceConfirmedAllocations(
                1L,
                seed.clinicId,
                firstProposalId,
                null,
                listOf(allocation("laser-capacity", ResourceAllocationMode.CAPACITY_BUCKET, units = 2, maximum = 3)),
            )

            assertFailsWith<ResourceAllocationConflictException> {
                repository.replaceConfirmedAllocations(
                    1L,
                    seed.clinicId,
                    secondProposalId,
                    null,
                    listOf(allocation("laser-capacity", ResourceAllocationMode.CAPACITY_BUCKET, units = 2, maximum = 3)),
                )
            }
            repository.findByProposal(secondProposalId) shouldHaveSize 0
        }
    }

    private fun createProposal(appointmentId: Long, revision: Long): Long {
        val commitment = commitmentRepository.findByAppointmentId(appointmentId)
            ?: commitmentRepository.create(
                AppointmentCommitment(
                    appointmentId,
                    AppointmentCommitmentStatus.PROPOSED,
                    AppointmentOrigin.CLINIC,
                    null,
                    7L,
                    1L,
                ),
            )
        val startsAt = Instant.parse("2026-08-10T01:00:00Z")
        val draft = AppointmentProposalDraft(
            appointmentId,
            revision,
            startsAt,
            startsAt.plusSeconds(3_600),
            emptyList(),
            emptyList(),
            7L,
            null,
        )
        return commitmentRepository.appendProposal(
            commitment.id,
            draft,
            revision.toString().repeat(64).take(64),
            draft.endsAt,
            "진료",
            "clinic",
        ).id
    }

    private fun allocation(
        resourceId: String,
        mode: ResourceAllocationMode,
        units: Int = 1,
        maximum: Int = 1,
    ) = ResourceAllocationRequest(
        allocation = ResourceAllocationDraft(
            resourceType = if (mode == ResourceAllocationMode.CAPACITY_BUCKET) {
                ResourceType.CAPACITY_BUCKET
            } else {
                ResourceType.PRACTITIONER
            },
            resourceId = resourceId,
            startsAt = Instant.parse("2026-08-10T01:00:00Z"),
            endsAt = Instant.parse("2026-08-10T02:00:00Z"),
            capacityUnits = units,
            allocationMode = mode,
            appointmentItemKey = null,
        ),
        maximumCapacity = maximum,
    )
}
