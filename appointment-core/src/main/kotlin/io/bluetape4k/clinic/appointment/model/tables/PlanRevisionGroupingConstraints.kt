package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Plan revision의 방향 없는 방문 묶음 제약입니다.
 *
 * 실행 선후관계와 별도로 `MUST_SAME_VISIT`, `MAY_SAME_VISIT`,
 * `MUST_SEPARATE_VISIT` 의미를 보존합니다. 두 treatment key는 저장 전에 정렬해 같은
 * 쌍을 반대 순서로 중복 저장하지 못하게 합니다.
 */
object PlanRevisionGroupingConstraints : LongIdTable("scheduling_plan_revision_grouping_constraints") {
    /** 제약이 속한 불변 Plan revision입니다. */
    val planRevisionId = reference(
        "plan_revision_id",
        AppointmentPlanRevisions,
        onDelete = ReferenceOption.CASCADE,
    )

    /** 사전식 정렬에서 앞선 treatment key입니다. */
    val firstTreatmentKey = varchar("first_treatment_key", 128)

    /** 사전식 정렬에서 뒤의 treatment key입니다. */
    val secondTreatmentKey = varchar("second_treatment_key", 128)

    /** 두 항목의 같은 방문 배치 의미입니다. */
    val type = enumerationByName<VisitGroupingType>("grouping_type", 32)

    init {
        uniqueIndex(
            "uq_plan_revision_grouping",
            planRevisionId,
            firstTreatmentKey,
            secondTreatmentKey,
        )
    }
}
