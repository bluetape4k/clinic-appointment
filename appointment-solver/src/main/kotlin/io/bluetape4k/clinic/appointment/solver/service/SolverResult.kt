package io.bluetape4k.clinic.appointment.solver.service

import ai.timefold.solver.core.api.score.HardSoftScore
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import java.io.Serializable

/**
 * Solver 실행 결과.
 *
 * @property score Solver가 계산한 최종 점수
 * @property appointments 최적화된 예약 목록
 * @property isFeasible Hard Constraint 위반이 없는지 여부
 * @property solveTimeMillis Solver 실행 시간 (밀리초)
 * @property entityCount 최적화 대상 엔티티 수
 * @property pinnedCount 고정(pinned) 엔티티 수
 */
data class SolverResult(
    val score: HardSoftScore,
    val appointments: List<AppointmentRecord>,
    val isFeasible: Boolean,
    val solveTimeMillis: Long = 0L,
    val entityCount: Int = 0,
    val pinnedCount: Int = 0,
    val scope: TenantClinicScope,
    val sourceVersions: Map<Long, Long> = emptyMap(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
