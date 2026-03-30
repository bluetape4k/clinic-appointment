package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time

object EquipmentUnavailabilities : LongIdTable("scheduling_equipment_unavailabilities") {
    val equipmentId = reference("equipment_id", Equipments, onDelete = ReferenceOption.CASCADE)
    val clinicId    = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)

    val unavailableDate    = date("unavailable_date").nullable()
    val isRecurring        = bool("is_recurring").default(false)
    val recurringDayOfWeek = enumerationByName<java.time.DayOfWeek>("recurring_day_of_week", 10).nullable()
    val effectiveFrom      = date("effective_from")
    val effectiveUntil     = date("effective_until").nullable()
    val startTime          = time("start_time")
    val endTime            = time("end_time")
    val reason             = varchar("reason", 500).nullable()

    init {
        index("idx_equ_unavail_equipment_from", false, equipmentId, effectiveFrom)
        index("idx_equ_unavail_clinic_from", false, clinicId, effectiveFrom)
        index("idx_equ_unavail_equipment_dow", false, equipmentId, recurringDayOfWeek)
    }
}
