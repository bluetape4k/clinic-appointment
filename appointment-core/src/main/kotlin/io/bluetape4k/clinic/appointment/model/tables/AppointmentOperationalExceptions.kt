package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionStatus
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 상담·운영 handoff가 필요한 append-only 예외입니다.
 */
object AppointmentOperationalExceptions : LongIdTable("scheduling_appointment_operational_exceptions") {
    val appointmentPlanId = reference(
        "appointment_plan_id",
        AppointmentPlans,
        onDelete = ReferenceOption.CASCADE,
    )
    val appointmentId = optReference("appointment_id", Appointments, onDelete = ReferenceOption.SET_NULL)
    val type = enumerationByName<AppointmentOperationalExceptionType>("exception_type", 48)
    val reasonCode = varchar("reason_code", 128)
    val status = enumerationByName<AppointmentOperationalExceptionStatus>("exception_status", 24)
    val openedAt = timestamp("opened_at")
    val resolvedAt = timestamp("resolved_at").nullable()

    init {
        index("idx_operational_exception_open", false, appointmentPlanId, status, openedAt)
    }
}
