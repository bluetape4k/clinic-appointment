package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * proposal별 실제 자원 점유와 해제 이력을 저장합니다.
 *
 * [status]가 `ACTIVE`인 row만 overlap과 capacity 집계에 포함됩니다. proposal 교체 시
 * 기존 row를 삭제하지 않고 `RELEASED`로 바꿔 rollback과 감사 의미를 보존합니다.
 */
object ResourceAllocations : LongIdTable("scheduling_resource_allocations") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val proposalId = reference("proposal_id", AppointmentProposals, onDelete = ReferenceOption.CASCADE)
    val appointmentItemKey = varchar("appointment_item_key", 128).nullable()
    val resourceType = enumerationByName<ResourceType>("resource_type", 32)
    val resourceId = varchar("resource_id", 128)
    val startsAt = timestamp("starts_at")
    val endsAt = timestamp("ends_at")
    val capacityUnits = integer("capacity_units")
    val allocationMode = enumerationByName<ResourceAllocationMode>("allocation_mode", 32)
    val status = enumerationByName<ResourceAllocationStatus>("allocation_status", 16)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val releasedAt = timestamp("released_at").nullable()

    init {
        index(
            "idx_resource_allocation_overlap",
            false,
            tenantGroupId,
            clinicId,
            resourceType,
            resourceId,
            status,
            startsAt,
            endsAt,
        )
        index("idx_resource_allocation_proposal", false, proposalId, status)
    }
}
