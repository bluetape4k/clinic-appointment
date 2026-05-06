package io.bluetape4k.clinic.appointment.solver.domain

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty
import ai.timefold.solver.core.api.domain.solution.PlanningScore
import ai.timefold.solver.core.api.domain.solution.PlanningSolution
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty
import ai.timefold.solver.core.api.domain.solution.ProblemFactProperty
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore
import io.bluetape4k.clinic.appointment.model.dto.BreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicClosureRecord
import io.bluetape4k.clinic.appointment.model.dto.ClinicDefaultBreakTimeRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorAbsenceRecord
import io.bluetape4k.clinic.appointment.model.dto.DoctorScheduleRecord
import io.bluetape4k.clinic.appointment.model.dto.HolidayRecord
import io.bluetape4k.clinic.appointment.model.dto.OperatingHoursRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentEquipmentRecord
import io.bluetape4k.clinic.appointment.solver.domain.EquipmentUnavailabilityFact
import java.time.LocalDate
import java.time.LocalTime

/**
 * Solver의 Planning Solution.
 *
 * 전체 문제 정의(Problem Facts)와 Planning Entity 목록,
 * 그리고 Value Range Provider를 포함합니다.
 *
 * @property clinic Solver 대상 병원 정보
 * @property doctors Solver 대상 의사 목록
 * @property treatments Solver 대상 진료 유형 목록
 * @property equipments Solver 대상 장비 목록
 * @property operatingHours 병원 운영 시간 목록
 * @property doctorSchedules 의사 근무 시간 목록
 * @property doctorAbsences 의사 부재 목록
 * @property breakTimes 병원 휴게 시간 목록
 * @property defaultBreakTimes 병원 기본 휴게 시간 목록
 * @property closures 병원 휴진 목록
 * @property holidays 휴일 목록
 * @property treatmentEquipments 진료 유형별 필요 장비 목록
 * @property equipmentUnavailabilities 장비 사용불가 목록
 * @property doctorIds 의사 ID Value Range
 * @property dateRange 예약 날짜 Value Range
 * @property timeSlots 예약 시작 시간 Value Range
 * @property appointments Solver가 최적화할 예약 엔티티 목록
 * @property score Solver 점수
 */
@PlanningSolution
class ScheduleSolution(

    @field:ProblemFactProperty
    val clinic: ClinicFact = ClinicFact(0, 30, 1, false),

    @field:ProblemFactCollectionProperty
    val doctors: List<DoctorFact> = emptyList(),

    @field:ProblemFactCollectionProperty
    val treatments: List<TreatmentFact> = emptyList(),

    @field:ProblemFactCollectionProperty
    val equipments: List<EquipmentFact> = emptyList(),

    @field:ProblemFactCollectionProperty
    val operatingHours: List<OperatingHoursRecord> = emptyList(),

    @field:ProblemFactCollectionProperty
    val doctorSchedules: List<DoctorScheduleRecord> = emptyList(),

    @field:ProblemFactCollectionProperty
    val doctorAbsences: List<DoctorAbsenceRecord> = emptyList(),

    @field:ProblemFactCollectionProperty
    val breakTimes: List<BreakTimeRecord> = emptyList(),

    @field:ProblemFactCollectionProperty
    val defaultBreakTimes: List<ClinicDefaultBreakTimeRecord> = emptyList(),

    @field:ProblemFactCollectionProperty
    val closures: List<ClinicClosureRecord> = emptyList(),

    @field:ProblemFactCollectionProperty
    val holidays: List<HolidayRecord> = emptyList(),

    @field:ProblemFactCollectionProperty
    val treatmentEquipments: List<TreatmentEquipmentRecord> = emptyList(),

    @field:ProblemFactCollectionProperty
    val equipmentUnavailabilities: List<EquipmentUnavailabilityFact> = emptyList(),

    // --- Value Range Providers ---

    @field:ValueRangeProvider(id = "doctorRange")
    @field:ProblemFactCollectionProperty
    val doctorIds: List<Long> = emptyList(),

    @field:ValueRangeProvider(id = "dateRange")
    @field:ProblemFactCollectionProperty
    val dateRange: List<LocalDate> = emptyList(),

    @field:ValueRangeProvider(id = "timeSlotRange")
    @field:ProblemFactCollectionProperty
    val timeSlots: List<LocalTime> = emptyList(),

    // --- Planning Entities ---

    @field:PlanningEntityCollectionProperty
    val appointments: List<AppointmentPlanning> = emptyList(),

    @field:PlanningScore
    var score: HardSoftScore? = null,
) {
    /** Timefold requires no-arg constructor */
    @Suppress("unused")
    constructor() : this(clinic = ClinicFact(0, 30, 1, false))
}
