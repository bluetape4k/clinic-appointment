package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentStatus
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 하나의 예약 계획이 소유하는 시술 의무 테이블입니다.
 *
 * 이 테이블은 불변 상품 BOM 사실과 변경 가능한 예약/이행 상태를 함께 물리화합니다.
 * 행 하나가 예약 1건을 뜻하지 않습니다. 여러 행이 예약 1건으로 이행될 수 있고,
 * 부분 이행 후 남은 행은 다시 예약될 수 있습니다.
 */
object PlannedTreatments : LongIdTable("scheduling_planned_treatments") {
    /** 소유 계획입니다. database cascade deletion이 orphan 의무를 방지합니다. */
    val planId = reference("plan_id", AppointmentPlans, onDelete = ReferenceOption.CASCADE)

    /** 안정적인 원본 BOM 항목 식별자입니다. 저장 길이는 128자로 제한합니다. */
    val bomItemId = varchar("bom_item_id", 128)

    /** [planId]/[bomItemId] 범위 안의 1부터 시작하는 회차 번호입니다. */
    val sequenceNo = integer("sequence_no")

    /** 원본 BOM 항목의 0부터 시작하는 결정적 위치입니다. */
    val bomOrder = integer("bom_order")

    /** 이력용 고객 표시 그룹명입니다. 저장 길이는 256자로 제한합니다. */
    val representativeTreatmentName = varchar("representative_treatment_name", 256)

    /** 순서 있는 세부진료 코드를 결정적으로 encoding한 JSON입니다. */
    val detailedTreatmentCodesJson = text("detailed_treatment_codes_json")

    /** 양수 분 단위의 예상 capacity 사용량입니다. */
    val durationMinutes = integer("duration_minutes")

    /** 정수 calendar day 단위의 선택적 내재 hard 하한 간격입니다. */
    val minimumIntervalDays = integer("minimum_interval_days").nullable()

    /** 정수 calendar day 단위의 선택적 내재 soft 목표 간격입니다. */
    val preferredIntervalDays = integer("preferred_interval_days").nullable()

    /** 정수 calendar day 단위의 선택적 내재 hard 상한 간격입니다. */
    val maximumIntervalDays = integer("maximum_interval_days").nullable()

    /** 담당자 역량 코드를 결정적으로 encoding한 JSON입니다. */
    val practitionerQualificationsJson = text("practitioner_qualifications_json")

    /** 필요한 장비 역량 코드를 결정적으로 encoding한 JSON입니다. */
    val equipmentTypesJson = text("equipment_types_json")

    /** 허용 room 역량 코드를 결정적으로 encoding한 JSON입니다. */
    val roomTypesJson = text("room_types_json")

    /** 선택적 포괄 UTC 하한입니다. `NULL`은 아직 제약되지 않았다는 뜻입니다. */
    val earliestStartAt = timestamp("earliest_start_at").nullable()

    /** 선택적 포괄 UTC 상한입니다. `NULL`은 현재 상한이 없다는 뜻입니다. */
    val latestStartAt = timestamp("latest_start_at").nullable()

    /** 현재 예약 상태와 외부 증거 기반 이행 수명주기입니다. */
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
