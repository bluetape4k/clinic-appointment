package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 공통 DB에서 capacity 합계를 직렬화하기 위한 잠금 row입니다.
 *
 * 동일 tenant·clinic·resource·bucket 시작 시각은 하나의 row만 가지며 repository가
 * 정렬된 순서로 `FOR UPDATE` 잠금을 획득합니다.
 */
object ResourceCapacityBuckets : LongIdTable("scheduling_resource_capacity_buckets") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val resourceType = enumerationByName<ResourceType>("resource_type", 32)
    val resourceId = varchar("resource_id", 128)
    val bucketStartAt = timestamp("bucket_start_at")
    val maximumCapacity = integer("maximum_capacity")

    init {
        uniqueIndex(
            "uq_resource_capacity_bucket",
            tenantGroupId,
            clinicId,
            resourceType,
            resourceId,
            bucketStartAt,
        )
    }
}
