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

    @Test
    fun `같은 자원 그룹의 첫 요청이 shared여도 뒤의 capacity 초과를 거부한다`() {
        withCommitmentTables { seed ->
            // Given: 기존 capacity가 상한 3 중 2를 사용 중
            val existingProposalId = createProposal(seed.appointmentId, 1L)
            val mixedProposalId = createProposal(seed.appointmentId, 2L)
            repository.replaceConfirmedAllocations(
                tenantGroupId = 1L,
                clinicId = seed.clinicId,
                proposalId = existingProposalId,
                replacingProposalId = null,
                requests =
                    listOf(
                        allocation(
                            resourceId = "mixed-order-capacity",
                            mode = ResourceAllocationMode.CAPACITY_BUCKET,
                            units = 2,
                            maximum = 3,
                        ),
                    ),
            )

            // When: 같은 자원 key의 비겹침 shared 요청을 먼저 두고 capacity 2를 뒤에 둠
            val failure =
                assertFailsWith<ResourceAllocationConflictException> {
                    repository.replaceConfirmedAllocations(
                        tenantGroupId = 1L,
                        clinicId = seed.clinicId,
                        proposalId = mixedProposalId,
                        replacingProposalId = null,
                        requests =
                            listOf(
                                allocation(
                                    resourceId = "mixed-order-capacity",
                                    mode = ResourceAllocationMode.SHARED,
                                    maximum = 3,
                                    startsAt = Instant.parse("2026-08-10T04:00:00Z"),
                                    endsAt = Instant.parse("2026-08-10T05:00:00Z"),
                                    resourceType = ResourceType.CAPACITY_BUCKET,
                                ),
                                allocation(
                                    resourceId = "mixed-order-capacity",
                                    mode = ResourceAllocationMode.CAPACITY_BUCKET,
                                    units = 2,
                                    maximum = 3,
                                    resourceType = ResourceType.CAPACITY_BUCKET,
                                ),
                            ),
                    )
                }

            // Then: 입력 순서와 무관하게 capacity 요청만 합산하고 전체 insert를 rollback
            failure.message shouldBeEqualTo "resource capacity bucket is exhausted"
            repository.findByProposal(mixedProposalId) shouldHaveSize 0
            repository.findByProposal(existingProposalId).single().status shouldBeEqualTo
                ResourceAllocationStatus.ACTIVE
        }
    }

    @Test
    fun `패키지 항목의 종료 시각이 달라도 실제 겹치는 capacity 초과를 거부한다`() {
        withCommitmentTables { seed ->
            // Given: 짧은 첫 항목과는 겹치지 않지만 긴 둘째 항목과 겹치는 기존 점유
            val existingProposalId = createProposal(seed.appointmentId, 1L)
            val packageProposalId = createProposal(seed.appointmentId, 2L)
            repository.replaceConfirmedAllocations(
                tenantGroupId = 1L,
                clinicId = seed.clinicId,
                proposalId = existingProposalId,
                replacingProposalId = null,
                requests =
                    listOf(
                        allocation(
                            resourceId = "laser-package-capacity",
                            mode = ResourceAllocationMode.CAPACITY_BUCKET,
                            units = 3,
                            maximum = 3,
                            startsAt = Instant.parse("2026-08-10T02:30:00Z"),
                            endsAt = Instant.parse("2026-08-10T03:30:00Z"),
                        ),
                    ),
            )

            // When: 같은 시작점이지만 종료 시각이 다른 패키지 항목 두 개를 함께 확정
            val failure =
                assertFailsWith<ResourceAllocationConflictException> {
                    repository.replaceConfirmedAllocations(
                        tenantGroupId = 1L,
                        clinicId = seed.clinicId,
                        proposalId = packageProposalId,
                        replacingProposalId = null,
                        requests =
                            listOf(
                                allocation(
                                    resourceId = "laser-package-capacity",
                                    mode = ResourceAllocationMode.CAPACITY_BUCKET,
                                    units = 1,
                                    maximum = 3,
                                    startsAt = Instant.parse("2026-08-10T01:00:00Z"),
                                    endsAt = Instant.parse("2026-08-10T02:00:00Z"),
                                ),
                                allocation(
                                    resourceId = "laser-package-capacity",
                                    mode = ResourceAllocationMode.CAPACITY_BUCKET,
                                    units = 1,
                                    maximum = 3,
                                    startsAt = Instant.parse("2026-08-10T01:00:00Z"),
                                    endsAt = Instant.parse("2026-08-10T03:00:00Z"),
                                ),
                            ),
                    )
                }

            // Then: 대표 구간이 아니라 실제 구간별 합계로 전체 insert가 rollback됨
            failure.message shouldBeEqualTo "resource capacity bucket is exhausted"
            repository.findByProposal(packageProposalId) shouldHaveSize 0
            repository.findByProposal(existingProposalId).single().status shouldBeEqualTo
                ResourceAllocationStatus.ACTIVE
        }
    }

    @Test
    fun `겹치는 상품 버전의 capacity 상한은 더 보수적인 기존 snapshot을 따른다`() {
        withCommitmentTables { seed ->
            // Given: 최대 2인 이전 구매 snapshot이 capacity를 모두 사용 중
            val existingProposalId = createProposal(seed.appointmentId, 1L)
            val newerProposalId = createProposal(seed.appointmentId, 2L)
            repository.replaceConfirmedAllocations(
                tenantGroupId = 1L,
                clinicId = seed.clinicId,
                proposalId = existingProposalId,
                replacingProposalId = null,
                requests =
                    listOf(
                        allocation(
                            resourceId = "versioned-capacity",
                            mode = ResourceAllocationMode.CAPACITY_BUCKET,
                            units = 2,
                            maximum = 2,
                        ),
                    ),
            )

            // When: 시작점이 다른 새 상품 버전이 상한 3으로 겹치는 예약을 시도
            val failure =
                assertFailsWith<ResourceAllocationConflictException> {
                    repository.replaceConfirmedAllocations(
                        tenantGroupId = 1L,
                        clinicId = seed.clinicId,
                        proposalId = newerProposalId,
                        replacingProposalId = null,
                        requests =
                            listOf(
                                allocation(
                                    resourceId = "versioned-capacity",
                                    mode = ResourceAllocationMode.CAPACITY_BUCKET,
                                    units = 1,
                                    maximum = 3,
                                    startsAt = Instant.parse("2026-08-10T01:30:00Z"),
                                    endsAt = Instant.parse("2026-08-10T02:30:00Z"),
                                ),
                            ),
                    )
                }

            // Then: 기존 구매 snapshot 상한 2를 느슨하게 덮어쓰지 않음
            failure.message shouldBeEqualTo "resource capacity bucket is exhausted"
            repository.findByProposal(newerProposalId) shouldHaveSize 0
            repository.findByProposal(existingProposalId).single().status shouldBeEqualTo
                ResourceAllocationStatus.ACTIVE
        }
    }

    private fun createProposal(
        appointmentId: Long,
        revision: Long,
    ): Long {
        val commitment =
            commitmentRepository.findByAppointmentId(appointmentId)
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
        val draft =
            AppointmentProposalDraft(
                appointmentId,
                revision,
                startsAt,
                startsAt.plusSeconds(3_600),
                emptyList(),
                emptyList(),
                7L,
                null,
            )
        return commitmentRepository
            .appendProposal(
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
        startsAt: Instant = Instant.parse("2026-08-10T01:00:00Z"),
        endsAt: Instant = Instant.parse("2026-08-10T02:00:00Z"),
        resourceType: ResourceType =
            if (mode == ResourceAllocationMode.CAPACITY_BUCKET) {
                ResourceType.CAPACITY_BUCKET
            } else {
                ResourceType.PRACTITIONER
            },
    ) = ResourceAllocationRequest(
        allocation =
            ResourceAllocationDraft(
                resourceType = resourceType,
                resourceId = resourceId,
                startsAt = startsAt,
                endsAt = endsAt,
                capacityUnits = units,
                maximumCapacity = maximum,
                allocationMode = mode,
                appointmentItemKey = null,
            ),
        maximumCapacity = maximum,
    )
}
