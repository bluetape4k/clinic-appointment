package io.bluetape4k.clinic.appointment.api.stats

import org.jetbrains.exposed.v1.core.Table

/** 동일 aggregate의 통계 projection upsert를 DB row lock으로 직렬화하는 metadata table입니다. */
object AppointmentStatsProjectionAggregateLockTable : Table("scheduling_appointment_stats_projection_aggregate_locks") {
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val aggregateId = varchar("aggregate_id", 128)

    override val primaryKey = PrimaryKey(
        tenantGroupId,
        clinicId,
        aggregateId,
        name = "pk_appointment_stats_projection_aggregate_locks",
    )
}
