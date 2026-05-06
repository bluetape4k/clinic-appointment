package io.bluetape4k.clinic.appointment.solver.domain

import ai.timefold.solver.core.api.domain.entity.PlanningEntity
import ai.timefold.solver.core.api.domain.entity.PlanningPin
import ai.timefold.solver.core.api.domain.variable.PlanningVariable
import io.bluetape4k.clinic.appointment.solver.move.AppointmentDifficultyComparator
import java.time.LocalDate
import java.time.LocalTime

/**
 * Solver의 Planning Entity.
 *
 * [doctorId], [appointmentDate], [startTime] 세 가지가 Planning Variable이며,
 * Solver가 최적 값을 탐색합니다.
 *
 * [pinned]가 true인 예약(CONFIRMED 등)은 Solver가 이동하지 않습니다.
 *
 * @property id 예약 ID
 * @property clinicId 병원 ID
 * @property treatmentTypeId 진료 유형 ID
 * @property equipmentId 필요한 장비 ID
 * @property patientName 환자명
 * @property durationMinutes 예약 소요 시간(분)
 * @property requiredProviderType 필요한 진료 제공자 유형
 * @property requiresEquipment 장비 필요 여부
 * @property originalDoctorId 원래 배정된 의사 ID
 * @property requestedDate 환자가 요청한 예약 날짜
 * @property pinned Solver 이동 금지 여부
 * @property doctorId Solver가 배정하는 의사 ID
 * @property appointmentDate Solver가 배정하는 예약 날짜
 * @property startTime Solver가 배정하는 시작 시간
 */
@PlanningEntity(comparatorClass = AppointmentDifficultyComparator::class)
class AppointmentPlanning(
    val id: Long = 0L,
    val clinicId: Long = 0L,
    val treatmentTypeId: Long = 0L,
    val equipmentId: Long? = null,
    val patientName: String = "",
    val durationMinutes: Int = 0,
    val requiredProviderType: String = "DOCTOR",
    val requiresEquipment: Boolean = false,
    val originalDoctorId: Long? = null,
    val requestedDate: LocalDate? = null,

    @field:PlanningPin
    val pinned: Boolean = false,

    @field:PlanningVariable(valueRangeProviderRefs = ["doctorRange"])
    var doctorId: Long? = null,

    @field:PlanningVariable(valueRangeProviderRefs = ["dateRange"])
    var appointmentDate: LocalDate? = null,

    @field:PlanningVariable(valueRangeProviderRefs = ["timeSlotRange"])
    var startTime: LocalTime? = null,
) {
    val endTime: LocalTime?
        get() = startTime?.plusMinutes(durationMinutes.toLong())

    /** Timefold requires no-arg constructor */
    @Suppress("unused")
    constructor() : this(id = 0L)

    override fun toString(): String =
        "AppointmentPlanning(id=$id, doctor=$doctorId, date=$appointmentDate, time=$startTime~$endTime, pinned=$pinned)"
}
