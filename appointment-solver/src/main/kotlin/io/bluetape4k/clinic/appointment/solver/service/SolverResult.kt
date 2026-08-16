package io.bluetape4k.clinic.appointment.solver.service

import ai.timefold.solver.core.api.score.HardSoftScore
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import java.io.Serializable
import java.time.LocalDate

/**
 * Solver 실행 결과.
 *
 * @property score Solver가 계산한 최종 점수
 * @property appointments 최적화된 예약 목록
 * @property isFeasible Hard Constraint 위반이 없는지 여부
 * @property solveTimeMillis Solver 실행 시간 (밀리초)
 * @property entityCount 최적화 대상 엔티티 수
 * @property pinnedCount 고정(pinned) 엔티티 수
 * @property dateRange snapshot을 읽은 최적화 날짜 범위. 이전 결과는 `null`일 수 있으며
 *   fence 검증 경로에서 거부됩니다.
 * @property planningFactVersion snapshot의 planning fact canonical version. 이전 결과는 빈
 *   문자열일 수 있으며 fence 검증 경로에서 거부됩니다.
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
    val dateRange: ClosedRange<LocalDate>? = null,
    val planningFactVersion: String = "",
) : Serializable {
    companion object {
        private const val serialVersionUID = 2L
    }
}
