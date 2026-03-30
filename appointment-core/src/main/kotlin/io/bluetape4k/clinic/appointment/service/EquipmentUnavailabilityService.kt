package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.dto.UnavailablePeriod
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import io.bluetape4k.clinic.appointment.repository.EquipmentUnavailabilityRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
            checkNotNull(unavailableDate) { "unavailableDate is required for non-recurring rules" }
        } else {
            checkNotNull(recurringDayOfWeek) { "recurringDayOfWeek is required for recurring rules" }
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
}
