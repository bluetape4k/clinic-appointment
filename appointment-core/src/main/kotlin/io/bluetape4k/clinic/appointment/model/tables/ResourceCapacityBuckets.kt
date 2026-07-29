package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 공통 DB에서 동일 자원의 overlap 검증을 직렬화하기 위한 잠금 row입니다.
 *
 * repository는 [bucketStartAt]에 실제 예약 시각이 아닌 고정 mutex 시각을 저장해
 * 시작 시각이 다른 겹침도 동일 row에서 직렬화합니다. [maximumCapacity]는 기존
 * schema의 필수 컬럼을 채우는 mutex metadata입니다. 구매 당시 실제 상한은
 * `ResourceAllocations.maximumCapacity`에 allocation과 함께 보존합니다.
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
