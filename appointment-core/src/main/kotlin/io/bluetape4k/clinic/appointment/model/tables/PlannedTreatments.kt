package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentStatus
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Treatment obligations owned by one appointment plan.
 */
object PlannedTreatments : LongIdTable("scheduling_planned_treatments") {
    val planId = reference("plan_id", AppointmentPlans, onDelete = ReferenceOption.CASCADE)
    val bomItemId = varchar("bom_item_id", 128)
    val sequenceNo = integer("sequence_no")
    val bomOrder = integer("bom_order")
    val representativeTreatmentName = varchar("representative_treatment_name", 256)
    val detailedTreatmentCodesJson = text("detailed_treatment_codes_json")
    val durationMinutes = integer("duration_minutes")
    val minimumIntervalDays = integer("minimum_interval_days").nullable()
    val preferredIntervalDays = integer("preferred_interval_days").nullable()
    val maximumIntervalDays = integer("maximum_interval_days").nullable()
    val practitionerQualificationsJson = text("practitioner_qualifications_json")
    val equipmentTypesJson = text("equipment_types_json")
    val roomTypesJson = text("room_types_json")
    val earliestStartAt = timestamp("earliest_start_at").nullable()
    val latestStartAt = timestamp("latest_start_at").nullable()
    val status = enumerationByName<PlannedTreatmentStatus>("status", 32)

    init {
        uniqueIndex("uq_planned_treatment_sequence", planId, bomItemId, sequenceNo)
        index(
            "idx_treatment_plan_status_window",
            false,
            planId,
            status,
            earliestStartAt,
            latestStartAt,
        )
    }
}
