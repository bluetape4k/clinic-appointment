package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.dto.UnavailablePeriod
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
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
 *
 * @param repo 장비 사용불가 Repository
 * @param appointmentRepository 충돌 예약 조회 Repository
 */
class EquipmentUnavailabilityService(
    private val repo: EquipmentUnavailabilityRepository = EquipmentUnavailabilityRepository(),
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
    private val equipmentRepository: EquipmentRepository = EquipmentRepository(),
) {
    companion object : KLogging()

    internal fun create(
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

    /** 검증된 tenant-clinic 범위의 장비에만 사용불가 스케줄을 생성합니다. */
    fun create(
        scope: TenantClinicScope,
        equipmentId: Long,
        unavailableDate: LocalDate?,
        isRecurring: Boolean,
        recurringDayOfWeek: DayOfWeek?,
        effectiveFrom: LocalDate,
        effectiveUntil: LocalDate?,
        startTime: LocalTime,
        endTime: LocalTime,
        reason: String?,
    ): EquipmentUnavailabilityRecord = transaction {
        requireNotNull(equipmentRepository.findByIdAndScope(equipmentId, scope)) {
            "Equipment not found in scope: ${scope.cacheKey()}, equipmentId=$equipmentId"
        }
        create(
            equipmentId = equipmentId,
            clinicId = scope.clinicId,
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

    internal fun findById(id: Long): EquipmentUnavailabilityRecord? = transaction {
        id.requirePositiveNumber("id")
        repo.findById(id)
    }

    internal fun findByIdAndTenant(id: Long, tenantGroupId: Long): EquipmentUnavailabilityRecord? = transaction {
        id.requirePositiveNumber("id")
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        repo.findByIdAndTenant(id, tenantGroupId)
    }

    /** 검증된 tenant-clinic 범위의 사용불가 스케줄만 조회합니다. */
    fun findById(scope: TenantClinicScope, id: Long): EquipmentUnavailabilityRecord? = transaction {
        id.requirePositiveNumber("id")
        repo.findByIdAndScope(id, scope)
    }

    internal fun findUnavailabilityRecords(
        equipmentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<EquipmentUnavailabilityRecord> = transaction {
        equipmentId.requirePositiveNumber("equipmentId")
        repo.findByEquipment(equipmentId, from, to)
    }

    /** 검증된 tenant-clinic 범위의 장비 사용불가 스케줄만 조회합니다. */
    fun findUnavailabilityRecords(
        scope: TenantClinicScope,
        equipmentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<EquipmentUnavailabilityRecord> = transaction {
        equipmentId.requirePositiveNumber("equipmentId")
        repo.findByEquipment(scope, equipmentId, from, to)
    }

    internal fun delete(id: Long) = transaction {
        id.requirePositiveNumber("id")
        log.debug { "Deleting EquipmentUnavailability id=$id" }
        repo.delete(id)
    }

    internal fun deleteByTenant(id: Long, tenantGroupId: Long): Boolean = transaction {
        id.requirePositiveNumber("id")
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        if (repo.findByIdAndTenant(id, tenantGroupId) == null) {
            false
        } else {
            log.debug { "Deleting EquipmentUnavailability id=$id for tenantGroupId=$tenantGroupId" }
            repo.delete(id)
            true
        }
    }

    /** 지정 scope의 사용불가 스케줄만 삭제합니다. */
    fun delete(scope: TenantClinicScope, id: Long): Boolean = transaction {
        repo.delete(id, scope)
    }

    internal fun addException(
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

    /** 지정 scope의 사용불가 스케줄에만 예외를 추가합니다. */
    fun addException(
        scope: TenantClinicScope,
        unavailabilityId: Long,
        originalDate: LocalDate,
        exceptionType: ExceptionType,
        rescheduledDate: LocalDate?,
        rescheduledStartTime: LocalTime?,
        rescheduledEndTime: LocalTime?,
        reason: String?,
    ): EquipmentUnavailabilityExceptionRecord = transaction {
        requireNotNull(repo.findByIdAndScope(unavailabilityId, scope)) {
            "EquipmentUnavailability not found in scope: ${scope.cacheKey()}, id=$unavailabilityId"
        }
        addException(
            unavailabilityId = unavailabilityId,
            originalDate = originalDate,
            exceptionType = exceptionType,
            rescheduledDate = rescheduledDate,
            rescheduledStartTime = rescheduledStartTime,
            rescheduledEndTime = rescheduledEndTime,
            reason = reason,
        )
    }

    internal fun deleteException(exceptionId: Long) = transaction {
        exceptionId.requirePositiveNumber("exceptionId")
        log.debug { "Deleting EquipmentUnavailabilityException id=$exceptionId" }
        repo.deleteException(exceptionId)
    }

    /** 지정 scope의 부모 스케줄에 속한 예외만 삭제합니다. */
    fun deleteException(scope: TenantClinicScope, unavailabilityId: Long, exceptionId: Long): Boolean = transaction {
        requireNotNull(repo.findByIdAndScope(unavailabilityId, scope)) {
            "EquipmentUnavailability not found in scope: ${scope.cacheKey()}, id=$unavailabilityId"
        }
        repo.deleteException(unavailabilityId, exceptionId)
    }

    internal fun findUnavailablePeriodsInRange(
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

    /** 검증된 tenant-clinic 범위의 장비 사용불가 기간만 전개합니다. */
    fun findUnavailablePeriodsInRange(
        scope: TenantClinicScope,
        equipmentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<UnavailablePeriod> {
        equipmentId.requirePositiveNumber("equipmentId")
        return transaction {
            val rules = repo.findByEquipment(scope, equipmentId, from, to)
            rules.flatMap { rule ->
                val exceptions = repo.findExceptions(rule.id)
                UnavailabilityExpander.expand(rule, exceptions, from..to)
            }
        }
    }

    internal fun findUnavailableOnDate(
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

    fun findUnavailableOnDate(
        scope: TenantClinicScope,
        date: LocalDate,
    ): Map<Long, List<UnavailablePeriod>> {
        return transaction {
            val rules = repo.findByClinicOnDate(scope, date)
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

    /** 지정 scope의 사용불가 스케줄과 겹치는 예약만 조회합니다. */
    fun detectConflicts(scope: TenantClinicScope, unavailabilityId: Long): List<AppointmentRecord> {
        unavailabilityId.requirePositiveNumber("unavailabilityId")
        return transaction {
            val record = repo.findByIdAndScope(unavailabilityId, scope)
                ?: return@transaction emptyList()
            val exceptions = repo.findExceptions(unavailabilityId)
            val rangeEnd = record.effectiveUntil ?: record.effectiveFrom.plusYears(1)
            val periods = UnavailabilityExpander.expand(record, exceptions, record.effectiveFrom..rangeEnd)
            log.debug { "Detecting scoped conflicts for unavailabilityId=$unavailabilityId, periods=${periods.size}" }
            appointmentRepository.findOverlappingByEquipment(scope, record.equipmentId, periods)
        }
    }

    /** 지정 scope의 장비를 기준으로 새 사용불가 스케줄의 충돌을 미리 조회합니다. */
    fun previewConflicts(
        scope: TenantClinicScope,
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
        return transaction {
            requireNotNull(equipmentRepository.findByIdAndScope(equipmentId, scope)) {
                "Equipment not found in scope: ${scope.cacheKey()}, equipmentId=$equipmentId"
            }
            val tempRecord = EquipmentUnavailabilityRecord(
                id = 0L,
                equipmentId = equipmentId,
                clinicId = scope.clinicId,
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
            log.debug { "Previewing scoped conflicts for equipmentId=$equipmentId, periods=${periods.size}" }
            appointmentRepository.findOverlappingByEquipment(scope, equipmentId, periods)
        }
    }
}
