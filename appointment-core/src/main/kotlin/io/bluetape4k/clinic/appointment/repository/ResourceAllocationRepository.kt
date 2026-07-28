package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRecord
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.model.tables.ResourceCapacityBuckets
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.time.Instant

/**
 * caller transaction 안에서 proposal별 실제 자원 점유를 검증·교체합니다.
 *
 * 잠금 순서는 tenant, clinic, resource type, resource ID, bucket 시작 시각으로
 * 정규화합니다. 기존 확정 proposal을 교체할 때 그 proposal의 allocation은 overlap
 * 합계에서 제외하지만, 새 allocation 전체 검증과 insert가 끝나기 전에는 해제하지
 * 않습니다.
 */
class ResourceAllocationRepository {

    /**
     * 새 proposal의 모든 자원 점유를 검증해 insert한 뒤 이전 proposal을 해제합니다.
     *
     * 메서드는 transaction을 열지 않습니다. 예외를 caller가 잡고 transaction을 계속
     * 사용할 수 있도록 모든 검증을 write보다 먼저 수행합니다.
     *
     * @throws ResourceAllocationConflictException 전담 overlap, 공유/전담 충돌, capacity
     * 초과 또는 같은 bucket의 정책 상한 불일치가 있으면 발생합니다.
     */
    fun replaceConfirmedAllocations(
        tenantGroupId: Long,
        clinicId: Long,
        proposalId: Long,
        replacingProposalId: Long?,
        requests: List<ResourceAllocationRequest>,
    ): List<ResourceAllocationRecord> {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        proposalId.requirePositiveNumber("proposalId")
        replacingProposalId?.requirePositiveNumber("replacingProposalId")
        require(requests.isNotEmpty()) { "resource allocation requests must not be empty" }
        require(replacingProposalId != proposalId) {
            "proposalId and replacingProposalId must be different"
        }

        val lockGroups = requests.groupBy { request ->
            AllocationLockKey(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                resourceType = request.allocation.resourceType,
                resourceId = request.allocation.resourceId,
                bucketStartAt = request.allocation.startsAt,
            )
        }
        lockGroups.entries
            .sortedBy(Map.Entry<AllocationLockKey, List<ResourceAllocationRequest>>::key)
            .forEach { (key, groupedRequests) ->
                val maximumCapacities = groupedRequests.map(ResourceAllocationRequest::maximumCapacity).toSet()
                if (maximumCapacities.size != 1) {
                    throw ResourceAllocationConflictException(
                        "same resource bucket must use one maximum capacity",
                    )
                }
                lockCapacityBucket(key, maximumCapacities.single())
            }

        validateInternalConflicts(requests)
        validateExistingConflicts(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            replacingProposalId = replacingProposalId,
            lockGroups = lockGroups,
        )

        val insertedRows = ResourceAllocations.batchInsert(requests) { request ->
            val allocation = request.allocation
            this[ResourceAllocations.tenantGroupId] = tenantGroupId
            this[ResourceAllocations.clinicId] = clinicId
            this[ResourceAllocations.proposalId] = proposalId
            this[ResourceAllocations.appointmentItemKey] = allocation.appointmentItemKey
            this[ResourceAllocations.resourceType] = allocation.resourceType
            this[ResourceAllocations.resourceId] = allocation.resourceId
            this[ResourceAllocations.startsAt] = allocation.startsAt
            this[ResourceAllocations.endsAt] = allocation.endsAt
            this[ResourceAllocations.capacityUnits] = allocation.capacityUnits
            this[ResourceAllocations.allocationMode] = allocation.allocationMode
            this[ResourceAllocations.status] = ResourceAllocationStatus.ACTIVE
        }
        replacingProposalId?.let { oldProposalId ->
            ResourceAllocations.update(
                where = {
                    (ResourceAllocations.proposalId eq oldProposalId) and
                        (ResourceAllocations.status eq ResourceAllocationStatus.ACTIVE)
                },
            ) {
                it[status] = ResourceAllocationStatus.RELEASED
                it[releasedAt] = Instant.now()
            }
        }
        return insertedRows.map(::mapAllocation)
    }

    /** proposal의 allocation 이력을 생성 순서로 반환합니다. */
    fun findByProposal(proposalId: Long): List<ResourceAllocationRecord> {
        proposalId.requirePositiveNumber("proposalId")
        return ResourceAllocations
            .selectAll()
            .where { ResourceAllocations.proposalId eq proposalId }
            .orderBy(ResourceAllocations.id)
            .map(::mapAllocation)
    }

    private fun lockCapacityBucket(
        key: AllocationLockKey,
        maximumCapacity: Int,
    ) {
        ResourceCapacityBuckets.insertIgnore {
            it[tenantGroupId] = key.tenantGroupId
            it[clinicId] = key.clinicId
            it[resourceType] = key.resourceType
            it[resourceId] = key.resourceId
            it[bucketStartAt] = key.bucketStartAt
            it[ResourceCapacityBuckets.maximumCapacity] = maximumCapacity
        }
        val row = ResourceCapacityBuckets
            .selectAll()
            .where {
                (ResourceCapacityBuckets.tenantGroupId eq key.tenantGroupId) and
                    (ResourceCapacityBuckets.clinicId eq key.clinicId) and
                    (ResourceCapacityBuckets.resourceType eq key.resourceType) and
                    (ResourceCapacityBuckets.resourceId eq key.resourceId) and
                    (ResourceCapacityBuckets.bucketStartAt eq key.bucketStartAt)
            }
            .forUpdate()
            .single()
        if (row[ResourceCapacityBuckets.maximumCapacity] != maximumCapacity) {
            throw ResourceAllocationConflictException(
                "resource bucket maximum capacity differs from the persisted policy snapshot",
            )
        }
    }

    private fun validateInternalConflicts(requests: List<ResourceAllocationRequest>) {
        requests.forEachIndexed { index, first ->
            requests.drop(index + 1).forEach { second ->
                val firstAllocation = first.allocation
                val secondAllocation = second.allocation
                if (
                    firstAllocation.resourceType == secondAllocation.resourceType &&
                    firstAllocation.resourceId == secondAllocation.resourceId &&
                    firstAllocation.startsAt < secondAllocation.endsAt &&
                    firstAllocation.endsAt > secondAllocation.startsAt &&
                    (
                        firstAllocation.allocationMode == ResourceAllocationMode.EXCLUSIVE ||
                            secondAllocation.allocationMode == ResourceAllocationMode.EXCLUSIVE
                        )
                ) {
                    throw ResourceAllocationConflictException(
                        "request contains overlapping exclusive allocations",
                    )
                }
            }
        }
    }

    private fun validateExistingConflicts(
        tenantGroupId: Long,
        clinicId: Long,
        replacingProposalId: Long?,
        lockGroups: Map<AllocationLockKey, List<ResourceAllocationRequest>>,
    ) {
        lockGroups.forEach { (_, groupedRequests) ->
            val representative = groupedRequests.first()
            val allocation = representative.allocation
            val query = ResourceAllocations
                .selectAll()
                .where {
                    (ResourceAllocations.tenantGroupId eq tenantGroupId) and
                        (ResourceAllocations.clinicId eq clinicId) and
                        (ResourceAllocations.resourceType eq allocation.resourceType) and
                        (ResourceAllocations.resourceId eq allocation.resourceId) and
                        (ResourceAllocations.status eq ResourceAllocationStatus.ACTIVE) and
                        (ResourceAllocations.startsAt less allocation.endsAt) and
                        (ResourceAllocations.endsAt greater allocation.startsAt)
                }
            replacingProposalId?.let { oldProposalId ->
                query.andWhere { ResourceAllocations.proposalId neq oldProposalId }
            }
            val existing = query.toList()
            when (allocation.allocationMode) {
                ResourceAllocationMode.CAPACITY_BUCKET -> {
                    val requestedUnits = groupedRequests.sumOf { it.allocation.capacityUnits }
                    val existingUnits = existing.sumOf { it[ResourceAllocations.capacityUnits] }
                    if (existingUnits + requestedUnits > representative.maximumCapacity) {
                        throw ResourceAllocationConflictException("resource capacity bucket is exhausted")
                    }
                }

                ResourceAllocationMode.EXCLUSIVE -> {
                    if (existing.isNotEmpty()) {
                        throw ResourceAllocationConflictException("exclusive resource overlaps an active allocation")
                    }
                }

                ResourceAllocationMode.SHARED -> {
                    if (existing.any {
                            it[ResourceAllocations.allocationMode] == ResourceAllocationMode.EXCLUSIVE
                        }
                    ) {
                        throw ResourceAllocationConflictException(
                            "shared resource overlaps an exclusive allocation",
                        )
                    }
                }
            }
        }
    }

    private fun mapAllocation(row: ResultRow) = ResourceAllocationRecord(
        id = row[ResourceAllocations.id].value,
        tenantGroupId = row[ResourceAllocations.tenantGroupId].value,
        clinicId = row[ResourceAllocations.clinicId].value,
        proposalId = row[ResourceAllocations.proposalId].value,
        allocation = ResourceAllocationDraft(
            resourceType = row[ResourceAllocations.resourceType],
            resourceId = row[ResourceAllocations.resourceId],
            startsAt = row[ResourceAllocations.startsAt],
            endsAt = row[ResourceAllocations.endsAt],
            capacityUnits = row[ResourceAllocations.capacityUnits],
            allocationMode = row[ResourceAllocations.allocationMode],
            appointmentItemKey = row[ResourceAllocations.appointmentItemKey],
        ),
        status = row[ResourceAllocations.status],
    )

    private data class AllocationLockKey(
        val tenantGroupId: Long,
        val clinicId: Long,
        val resourceType: ResourceType,
        val resourceId: String,
        val bucketStartAt: Instant,
    ) : Comparable<AllocationLockKey>, Serializable {

        override fun compareTo(other: AllocationLockKey): Int =
            compareValuesBy(
                this,
                other,
                AllocationLockKey::tenantGroupId,
                AllocationLockKey::clinicId,
                { it.resourceType.name },
                AllocationLockKey::resourceId,
                AllocationLockKey::bucketStartAt,
            )

        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

/**
 * 자원 점유 또는 capacity 상한 충돌로 확정 transaction을 진행할 수 없음을 나타냅니다.
 */
class ResourceAllocationConflictException(message: String) : IllegalStateException(message)
