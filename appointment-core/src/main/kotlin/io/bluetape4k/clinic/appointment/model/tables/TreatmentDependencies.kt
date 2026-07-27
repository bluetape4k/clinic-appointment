package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * 하나의 예약 계획이 소유하는 물리화된 DAG edge 테이블입니다.
 *
 * edge는 atomic aggregate insert 중 BOM 논리 키에서 영속 시술 식별자로 해석됩니다.
 * 서비스는 모든 endpoint가 [planId]에 속하고 결과 graph가 비순환인지 검증합니다.
 */
object TreatmentDependencies : LongIdTable("scheduling_treatment_dependencies") {
    /** 소유 계획입니다. endpoint FK 외에도 명시적 scope check에 사용합니다. */
    val planId = reference("plan_id", AppointmentPlans, onDelete = ReferenceOption.CASCADE)

    /** 먼저 완료되어야 하는 시술 회차입니다. */
    val predecessorTreatmentId = reference(
        "predecessor_treatment_id",
        PlannedTreatments,
        onDelete = ReferenceOption.CASCADE,
    )

    /** 선행 완료 시각에 의해 제약되는 시술 회차입니다. */
    val successorTreatmentId = reference(
        "successor_treatment_id",
        PlannedTreatments,
        onDelete = ReferenceOption.CASCADE,
    )

    /** 선행 완료 후 필요한 hard 하한 간격입니다. 정수 calendar day 단위입니다. */
    val minimumIntervalDays = integer("minimum_interval_days")

    /** 선행 완료 후 선호하는 soft 목표 간격입니다. 정수 calendar day 단위입니다. */
    val preferredIntervalDays = integer("preferred_interval_days")

    /** 선행 완료 후 허용되는 hard 상한 간격입니다. 정수 calendar day 단위입니다. */
    val maximumIntervalDays = integer("maximum_interval_days")

    init {
        uniqueIndex("uq_treatment_dependency", predecessorTreatmentId, successorTreatmentId)
        index("idx_treatment_dependency_successor", false, successorTreatmentId)
        index(
            "idx_treatment_dependency_plan",
            false,
            planId,
            predecessorTreatmentId,
            successorTreatmentId,
        )
    }
}
