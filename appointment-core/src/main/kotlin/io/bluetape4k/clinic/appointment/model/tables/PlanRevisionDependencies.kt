package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * 같은 Plan revision의 treatment key 사이 완료 기준 실행 의존성과 간격입니다.
 */
object PlanRevisionDependencies : LongIdTable("scheduling_plan_revision_dependencies") {
    val planRevisionId = reference(
        "plan_revision_id",
        AppointmentPlanRevisions,
        onDelete = ReferenceOption.CASCADE,
    )
    val predecessorTreatmentKey = varchar("predecessor_treatment_key", 128)
    val successorTreatmentKey = varchar("successor_treatment_key", 128)
    val type = enumerationByName<ExecutionDependencyType>("dependency_type", 16)
    val minimumIntervalDays = integer("minimum_interval_days")
    val preferredIntervalDays = integer("preferred_interval_days").nullable()
    val maximumIntervalDays = integer("maximum_interval_days").nullable()

    init {
        uniqueIndex(
            "uq_plan_revision_dependency",
            planRevisionId,
            predecessorTreatmentKey,
            successorTreatmentKey,
        )
    }
}
