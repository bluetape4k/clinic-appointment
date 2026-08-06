package io.bluetape4k.clinic.appointment.api.stats

import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.model.tables.appointmentState
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

/** Kafka 통계 consumer가 tenant/date/status별로 누적하는 read-model table입니다. */
object AppointmentStatsProjectionTable : Table("scheduling_appointment_stats_projection") {
    val tenantGroupId = long("tenant_group_id")
    val clinicId = long("clinic_id")
    val eventDate = date("event_date")
    val status = appointmentState("status")
    val appointmentCount = long("appointment_count")
    val lastEventVersion = long("last_event_version")
    val lastEventId = varchar("last_event_id", 128)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(
        tenantGroupId,
        clinicId,
        eventDate,
        status,
        name = "pk_appointment_stats_projection",
    )

    init {
        index(
            "idx_appointment_stats_projection_scope_date",
            false,
            tenantGroupId,
            clinicId,
            eventDate,
        )
        index(
            "idx_appointment_stats_projection_scope_status_date",
            false,
            tenantGroupId,
            clinicId,
            status,
            eventDate,
        )
    }
}
