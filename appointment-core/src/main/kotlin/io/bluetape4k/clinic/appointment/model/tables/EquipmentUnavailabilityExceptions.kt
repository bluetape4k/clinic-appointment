package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time

enum class ExceptionType { SKIP, RESCHEDULE }

object EquipmentUnavailabilityExceptions : LongIdTable("scheduling_equipment_unavailability_exceptions") {
    val unavailabilityId       = reference("unavailability_id", EquipmentUnavailabilities, onDelete = ReferenceOption.CASCADE)
    val originalDate           = date("original_date")
    val exceptionType          = enumerationByName<ExceptionType>("exception_type", 20)
    val rescheduledDate        = date("rescheduled_date").nullable()
    val rescheduledStartTime   = time("rescheduled_start_time").nullable()
    val rescheduledEndTime     = time("rescheduled_end_time").nullable()
    val reason                 = varchar("reason", 500).nullable()

    init {
        index("idx_equ_unavail_exc_parent_date", false, unavailabilityId, originalDate)
    }
}
