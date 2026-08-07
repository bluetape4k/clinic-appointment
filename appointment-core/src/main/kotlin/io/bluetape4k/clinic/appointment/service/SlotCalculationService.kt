package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.clinic.appointment.model.dto.UnavailablePeriod
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.HolidayRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.clinic.appointment.model.service.AvailableSlot
import io.bluetape4k.clinic.appointment.model.service.SlotQuery
import io.bluetape4k.clinic.appointment.model.service.TimeRange
import io.bluetape4k.clinic.appointment.model.service.computeEffectiveRanges
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * 예약 가능한 슬롯을 계산하는 서비스.
 *
 * JDBC Exposed 트랜잭션을 사용하며, Spring Bean이 아닌 일반 클래스입니다.
 *
 * @param clinicRepository 병원 운영 정보 Repository
 * @param doctorRepository 의사 스케줄 Repository
 * @param treatmentTypeRepository 진료 유형 Repository
 * @param appointmentRepository 기존 예약 Repository
 * @param holidayRepository 휴일 Repository
 * @param equipmentUnavailabilityService 장비 사용불가 서비스
 */
class SlotCalculationService(
    private val clinicRepository: ClinicRepository = ClinicRepository(),
    private val doctorRepository: DoctorRepository = DoctorRepository(),
    private val treatmentTypeRepository: TreatmentTypeRepository = TreatmentTypeRepository(),
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
    private val holidayRepository: HolidayRepository = HolidayRepository(),
    private val equipmentUnavailabilityService: EquipmentUnavailabilityService = EquipmentUnavailabilityService(),
) {
    companion object: KLogging()

    /**
     * 주어진 조건에 맞는 예약 가능 슬롯 목록을 반환합니다.
     *
     * 다음 조건을 모두 확인하여 유효한 슬롯만 반환합니다:
     * - 병원이 휴무일이 아님 (또는 휴일 운영)
     * - 병원 휴진(전일/부분)이 아님
     * - 의사의 운영 시간 범위 내
     * - 휴시간/특정 의사 휴무 제외
     * - 의사의 진료 유형과 일치
     * - 동시 환자 수 제한 미초과
     * - 필요 장비 가용
     *
     * @param query 슬롯 조회 조건 (병원, 의사, 진료 유형, 날짜 등)
     * @return 예약 가능 슬롯 목록
     */
    fun findAvailableSlots(query: SlotQuery): List<AvailableSlot> =
        transaction {
            // 1. Clinic 조회
            val clinic = clinicRepository.findByIdAndTenant(query.scope.clinicId, query.scope.tenantGroupId)
                ?: return@transaction emptyList()

            // 1-1. 해당 날짜가 공휴일인지 확인
            if (!clinic.openOnHolidays && holidayRepository.existsByDate(query.scope, query.date)) {
                return@transaction emptyList()
            }

            // 2. 종일 휴진 여부를 위해 ClinicClosures 조회
            val closures = clinicRepository.findClosures(query.scope, query.date)
            if (closures.any { it.isFullDay }) {
                return@transaction emptyList()
            }

            // 3. clinic + dayOfWeek의 OperatingHours 조회
            val dayOfWeek = query.date.dayOfWeek
            val opHours = clinicRepository.findOperatingHours(query.scope, dayOfWeek)
                ?: return@transaction emptyList()

            // 4. clinic + dayOfWeek의 BreakTimes 조회
            val dayBreakRanges = clinicRepository.findBreakTimes(query.scope, dayOfWeek)
                .map { TimeRange(it.startTime, it.endTime) }

            // 4-1. 병원 기본 휴식시간 (모든 영업일에 동일 적용, 복수 설정 가능)
            val defaultBreakRanges = clinicRepository.findDefaultBreakTimes(query.scope)
                .map { TimeRange(it.startTime, it.endTime) }

            val breakTimeRanges = dayBreakRanges + defaultBreakRanges

            // 5. 부분 휴진 조회(isFullDay=false)
            val partialClosureRanges = closures
                .filter { !it.isFullDay }
                .mapNotNull { closure ->
                    val start = closure.startTime
                    val end = closure.endTime
                    if (start != null && end != null) TimeRange(start, end) else null
                }

            // 6. DoctorSchedule 조회
            val doctorSchedule = doctorRepository.findSchedule(query.scope, query.doctorId, dayOfWeek)
                ?: return@transaction emptyList()

            // 7. DoctorAbsences 조회
            val absences = doctorRepository.findAbsences(query.scope, query.doctorId, query.date)
            if (absences.any { it.startTime == null }) {
                return@transaction emptyList()
            }
            val doctorAbsenceRanges = absences.mapNotNull { absence ->
                val start = absence.startTime
                val end = absence.endTime
                if (start != null && end != null) TimeRange(start, end) else null
            }

            // 8. 유효 시간 범위 계산
            val effectiveRanges = computeEffectiveRanges(
                clinicOpen = opHours.openTime,
                clinicClose = opHours.closeTime,
                doctorStart = doctorSchedule.startTime,
                doctorEnd = doctorSchedule.endTime,
                breakTimes = breakTimeRanges,
                partialClosures = partialClosureRanges,
                doctorAbsences = doctorAbsenceRanges
            )
            if (effectiveRanges.isEmpty()) return@transaction emptyList()

            // 9. TreatmentType 조회
            val treatment = treatmentTypeRepository.findByIdAndScope(query.treatmentTypeId, query.scope)
                ?: return@transaction emptyList()

            val duration = query.requestedDurationMinutes ?: treatment.defaultDurationMinutes

            // 9-1. 의사를 조회하고 provider type 검증
            val doctor = doctorRepository.findByIdAndScope(query.doctorId, query.scope)
                ?: return@transaction emptyList()
            if (doctor.providerType != treatment.requiredProviderType) {
                return@transaction emptyList()
            }

            // 12. maxConcurrent 해석
            val maxConcurrent = resolveMaxConcurrent(
                clinic.maxConcurrentPatients, doctor.maxConcurrentPatients, treatment.maxConcurrentPatients
            )

            // 10. 유효 시간 범위에서 slotDurationMinutes 간격으로 slot 후보 생성
            val slotCandidates = mutableListOf<TimeRange>()
            for (range in effectiveRanges) {
                var current = range.start
                while (true) {
                    val slotEnd = current.plusMinutes(duration.toLong())
                    if (slotEnd > range.end) break
                    slotCandidates.add(TimeRange(current, slotEnd))
                    current = current.plusMinutes(clinic.slotDurationMinutes.toLong())
                }
            }

            // 14. 진료에 장비가 필요하면 필요한 장비 ID와 수량 조회
            val requiredEquipment = if (treatment.requiresEquipment) {
                treatmentTypeRepository.findRequiredEquipmentIds(query.treatmentTypeId, query.scope)
            } else emptyList()

            val equipmentQuantities = treatmentTypeRepository.findEquipmentQuantities(requiredEquipment, query.scope)

            // 장비 사용불가 기간 조회 (진료 유형이 장비를 필요로 하는 경우)
            val equipmentUnavailablePeriods: List<UnavailablePeriod> =
                if (treatment.requiresEquipment && requiredEquipment.isNotEmpty()) {
                    equipmentUnavailabilityService.findUnavailableOnDate(query.scope, query.date)
                        .filterKeys { it in requiredEquipment }
                        .values.flatten()
                } else emptyList()

            // 각 slot 후보 처리
            val availableSlots = mutableListOf<AvailableSlot>()
            for (candidate in slotCandidates) {
                // 11. 겹치는 기존 예약 건수 계산
                val overlappingCount = appointmentRepository.countOverlapping(
                    query.scope, query.doctorId, query.date, candidate.start, candidate.end
                )

                // 13. 기존 건수가 maxConcurrent보다 작은 slot만 필터링
                if (overlappingCount >= maxConcurrent) continue

                // 장비 사용불가 시간과 겹치는 슬롯 제외
                val blockedByEquipment = equipmentUnavailablePeriods.any { unavail ->
                    candidate.start < unavail.endTime && unavail.startTime < candidate.end
                }
                if (blockedByEquipment) continue

                // 14-15. 장비 가용성 확인
                val availableEquipmentIds = if (treatment.requiresEquipment && requiredEquipment.isNotEmpty()) {
                    val available = mutableListOf<Long>()
                    for (eqId in requiredEquipment) {
                        val quantity = equipmentQuantities[eqId] ?: 0
                        val usedCount =
                            appointmentRepository.countEquipmentUsage(
                                query.scope, eqId, query.date, candidate.start, candidate.end
                            )
                        if (usedCount < quantity) available.add(eqId)
                    }
                    if (available.isEmpty()) continue
                    available
                } else emptyList()


                val availableSlot = AvailableSlot(
                    date = query.date,
                    startTime = candidate.start,
                    endTime = candidate.end,
                    doctorId = query.doctorId,
                    equipmentIds = availableEquipmentIds,
                    remainingCapacity = maxConcurrent - overlappingCount
                )
                log.debug { "Add available slot. availableSlot=$availableSlot" }
                availableSlots.add(availableSlot)
            }
            availableSlots
        }
}
