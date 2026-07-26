package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Materialized DAG edges owned by one appointment plan.
 */
object TreatmentDependencies : LongIdTable("scheduling_treatment_dependencies") {
    val planId = reference("plan_id", AppointmentPlans, onDelete = ReferenceOption.CASCADE)
    val predecessorTreatmentId = reference(
        "predecessor_treatment_id",
        PlannedTreatments,
        onDelete = ReferenceOption.CASCADE,
    )
    val successorTreatmentId = reference(
        "successor_treatment_id",
        PlannedTreatments,
        onDelete = ReferenceOption.CASCADE,
    )
    val minimumIntervalDays = integer("minimum_interval_days")
    val preferredIntervalDays = integer("preferred_interval_days")
    val maximumIntervalDays = integer("maximum_interval_days")

    init {
        uniqueIndex("uq_treatment_dependency", predecessorTreatmentId, successorTreatmentId)
        index(
            "idx_treatment_dependency_plan",
            false,
            planId,
            predecessorTreatmentId,
            successorTreatmentId,
        )
    }
}
