package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.dto.UnavailablePeriod
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentUnavailabilityRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 장비 사용불가 기간을 관리하는 서비스.
 *
 * JDBC Exposed 트랜잭션을 사용하며, Spring Bean이 아닌 일반 클래스입니다.
 */
class EquipmentUnavailabilityService(
    private val repo: EquipmentUnavailabilityRepository = EquipmentUnavailabilityRepository(),
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
) {
    companion object : KLogging()

    fun create(
        equipmentId: Long,
        clinicId: Long,
        unavailableDate: LocalDate?,
        isRecurring: Boolean,
        recurringDayOfWeek: DayOfWeek?,
        effectiveFrom: LocalDate,
        effectiveUntil: LocalDate?,
        startTime: LocalTime,
        endTime: LocalTime,
        reason: String?,
    ): EquipmentUnavailabilityRecord = transaction {
        equipmentId.requirePositiveNumber("equipmentId")
        clinicId.requirePositiveNumber("clinicId")
        check(startTime < endTime) { "startTime must be before endTime: $startTime >= $endTime" }
        if (!isRecurring) {
            unavailableDate.requireNotNull("unavailableDate")
        } else {
            recurringDayOfWeek.requireNotNull("recurringDayOfWeek")
        }
        effectiveUntil?.let { until ->
            check(effectiveFrom <= until) { "effectiveFrom must be <= effectiveUntil: $effectiveFrom > $until" }
        }
        log.debug { "Creating EquipmentUnavailability for equipmentId=$equipmentId, clinicId=$clinicId" }
        repo.create(
            equipmentId = equipmentId,
            clinicId = clinicId,
            unavailableDate = unavailableDate,
            isRecurring = isRecurring,
            recurringDayOfWeek = recurringDayOfWeek,
            effectiveFrom = effectiveFrom,
            effectiveUntil = effectiveUntil,
            startTime = startTime,
            endTime = endTime,
            reason = reason,
        )
    }

    fun findById(id: Long): EquipmentUnavailabilityRecord? = transaction {
        id.requirePositiveNumber("id")
        repo.findById(id)
    }

    fun findUnavailabilityRecords(
        equipmentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<EquipmentUnavailabilityRecord> = transaction {
        equipmentId.requirePositiveNumber("equipmentId")
        repo.findByEquipment(equipmentId, from, to)
    }

    fun delete(id: Long) = transaction {
        id.requirePositiveNumber("id")
        log.debug { "Deleting EquipmentUnavailability id=$id" }
        repo.delete(id)
    }

    fun addException(
        unavailabilityId: Long,
        originalDate: LocalDate,
        exceptionType: ExceptionType,
        rescheduledDate: LocalDate?,
        rescheduledStartTime: LocalTime?,
        rescheduledEndTime: LocalTime?,
        reason: String?,
    ): EquipmentUnavailabilityExceptionRecord = transaction {
        log.debug { "Adding exception for unavailabilityId=$unavailabilityId, date=$originalDate, type=$exceptionType" }
        repo.addException(
            unavailabilityId = unavailabilityId,
            originalDate = originalDate,
            exceptionType = exceptionType,
            rescheduledDate = rescheduledDate,
            rescheduledStartTime = rescheduledStartTime,
            rescheduledEndTime = rescheduledEndTime,
            reason = reason,
        )
    }

    fun deleteException(exceptionId: Long) = transaction {
        exceptionId.requirePositiveNumber("exceptionId")
        log.debug { "Deleting EquipmentUnavailabilityException id=$exceptionId" }
        repo.deleteException(exceptionId)
    }

    fun findUnavailablePeriodsInRange(
        equipmentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<UnavailablePeriod> {
        equipmentId.requirePositiveNumber("equipmentId")
        return transaction {
            val rules = repo.findByEquipment(equipmentId, from, to)
            rules.flatMap { rule ->
                val exceptions = repo.findExceptions(rule.id)
                UnavailabilityExpander.expand(rule, exceptions, from..to)
            }
        }
    }

    fun findUnavailableOnDate(
        clinicId: Long,
        date: LocalDate,
    ): Map<Long, List<UnavailablePeriod>> {
        clinicId.requirePositiveNumber("clinicId")
        return transaction {
            val rules = repo.findByClinicOnDate(clinicId, date)
            rules
                .groupBy { it.equipmentId }
                .mapValues { (_, ruleList) ->
                    ruleList.flatMap { rule ->
                        val exceptions = repo.findExceptions(rule.id)
                        UnavailabilityExpander.expand(rule, exceptions, date..date)
                    }
                }
        }
    }

    /**
     * 등록된 사용불가 스케줄과 충돌하는 기존 예약을 감지합니다.
     *
     * @param unavailabilityId 장비 사용불가 스케줄 ID
     * @return 충돌하는 예약 목록
     */
    fun detectConflicts(unavailabilityId: Long): List<AppointmentRecord> {
        unavailabilityId.requirePositiveNumber("unavailabilityId")
        return transaction {
            val record = repo.findById(unavailabilityId)
                ?: return@transaction emptyList()
            val exceptions = repo.findExceptions(unavailabilityId)
            val rangeEnd = record.effectiveUntil ?: record.effectiveFrom.plusYears(1)
            val periods = UnavailabilityExpander.expand(record, exceptions, record.effectiveFrom..rangeEnd)
            log.debug { "Detecting conflicts for unavailabilityId=$unavailabilityId, periods=${periods.size}" }
            appointmentRepository.findOverlappingByEquipment(record.equipmentId, periods)
        }
    }

    /**
     * 새로운 사용불가 스케줄 등록 전 충돌 미리보기.
     *
     * @param equipmentId 장비 ID
     * @param unavailableDate 사용불가 날짜 (isRecurring=false 시 필수)
     * @param isRecurring 반복 여부
     * @param recurringDayOfWeek 반복 요일 (isRecurring=true 시 필수)
     * @param effectiveFrom 유효 시작일
     * @param effectiveUntil 유효 종료일
     * @param startTime 사용불가 시작 시간
     * @param endTime 사용불가 종료 시간
     * @return 충돌하는 예약 목록
     */
    fun previewConflicts(
        equipmentId: Long,
        unavailableDate: LocalDate?,
        isRecurring: Boolean,
        recurringDayOfWeek: DayOfWeek?,
        effectiveFrom: LocalDate,
        effectiveUntil: LocalDate?,
        startTime: LocalTime,
        endTime: LocalTime,
    ): List<AppointmentRecord> {
        equipmentId.requirePositiveNumber("equipmentId")
        val tempRecord = EquipmentUnavailabilityRecord(
            id = 0L,
            equipmentId = equipmentId,
            clinicId = 0L,
            unavailableDate = unavailableDate,
            isRecurring = isRecurring,
            recurringDayOfWeek = recurringDayOfWeek,
            effectiveFrom = effectiveFrom,
            effectiveUntil = effectiveUntil,
            startTime = startTime,
            endTime = endTime,
            reason = null,
        )
        val rangeEnd = effectiveUntil ?: effectiveFrom.plusYears(1)
        val periods = UnavailabilityExpander.expand(tempRecord, emptyList(), effectiveFrom..rangeEnd)
        log.debug { "Previewing conflicts for equipmentId=$equipmentId, periods=${periods.size}" }
        return transaction {
            appointmentRepository.findOverlappingByEquipment(equipmentId, periods)
        }
    }
}
