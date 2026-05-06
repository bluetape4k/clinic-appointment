package io.bluetape4k.clinic.appointment.solver.domain

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 장비 사용불가 기간을 나타내는 Solver ProblemFact.
 *
 * [ScheduleSolution]에 [ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty]로 등록되어
 * Hard Constraint H11에서 참조됩니다.
 *
 * @property equipmentId 장비 ID
 * @property date 사용불가 날짜
 * @property startTime 사용불가 시작 시간
 * @property endTime 사용불가 종료 시간
 */
data class EquipmentUnavailabilityFact(
    val equipmentId: Long,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
