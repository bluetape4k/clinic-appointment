package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class EquipmentUnavailabilityRepository {
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
    ): EquipmentUnavailabilityRecord {
        equipmentId.requirePositiveNumber("equipmentId")
        clinicId.requirePositiveNumber("clinicId")

        val id = EquipmentUnavailabilities.insertAndGetId {
            it[EquipmentUnavailabilities.equipmentId] = equipmentId
            it[EquipmentUnavailabilities.clinicId] = clinicId
            it[EquipmentUnavailabilities.unavailableDate] = unavailableDate
            it[EquipmentUnavailabilities.isRecurring] = isRecurring
            it[EquipmentUnavailabilities.recurringDayOfWeek] = recurringDayOfWeek
            it[EquipmentUnavailabilities.effectiveFrom] = effectiveFrom
            it[EquipmentUnavailabilities.effectiveUntil] = effectiveUntil
            it[EquipmentUnavailabilities.startTime] = startTime
            it[EquipmentUnavailabilities.endTime] = endTime
            it[EquipmentUnavailabilities.reason] = reason
        }.value

        log.debug { "Created EquipmentUnavailability id=$id for equipmentId=$equipmentId" }

        return EquipmentUnavailabilityRecord(
            id = id,
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

    internal fun findById(id: Long): EquipmentUnavailabilityRecord? {
        id.requirePositiveNumber("id")
        return EquipmentUnavailabilities
            .selectAll()
            .where { EquipmentUnavailabilities.id eq id }
            .map { it.toEquipmentUnavailabilityRecord() }
            .firstOrNull()
    }

    /**
     * Finds an equipment unavailability by ID only when the owning clinic belongs to [tenantGroupId].
     */
    internal fun findByIdAndTenant(id: Long, tenantGroupId: Long): EquipmentUnavailabilityRecord? {
        id.requirePositiveNumber("id")
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        return EquipmentUnavailabilities
            .selectAll()
            .where {
                (EquipmentUnavailabilities.id eq id) and
                    (EquipmentUnavailabilities.clinicId inSubQuery tenantClinicIds(tenantGroupId))
            }
            .map { it.toEquipmentUnavailabilityRecord() }
            .firstOrNull()
    }

    /** 검증된 tenant-clinic 범위에 속한 사용불가 스케줄만 조회합니다. */
    fun findByIdAndScope(id: Long, scope: TenantClinicScope): EquipmentUnavailabilityRecord? {
        id.requirePositiveNumber("id")
        return EquipmentUnavailabilities
            .selectAll()
            .where {
                (EquipmentUnavailabilities.id eq id) and
                    (EquipmentUnavailabilities.clinicId eq scope.clinicId) and
                    (EquipmentUnavailabilities.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .map { it.toEquipmentUnavailabilityRecord() }
            .firstOrNull()
    }

    internal fun findByEquipment(
        equipmentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<EquipmentUnavailabilityRecord> {
        equipmentId.requirePositiveNumber("equipmentId")
        return EquipmentUnavailabilities
            .selectAll()
            .where { EquipmentUnavailabilities.equipmentId eq equipmentId }
            .andWhere { EquipmentUnavailabilities.effectiveFrom lessEq to }
            .andWhere {
                (EquipmentUnavailabilities.effectiveUntil.isNull()) or
                    (EquipmentUnavailabilities.effectiveUntil greaterEq from)
            }
            .map { it.toEquipmentUnavailabilityRecord() }
    }

    /** 검증된 tenant-clinic 범위의 장비 사용불가 스케줄만 조회합니다. */
    fun findByEquipment(
        scope: TenantClinicScope,
        equipmentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<EquipmentUnavailabilityRecord> {
        equipmentId.requirePositiveNumber("equipmentId")
        return EquipmentUnavailabilities
            .selectAll()
            .where {
                (EquipmentUnavailabilities.equipmentId eq equipmentId) and
                    (EquipmentUnavailabilities.clinicId eq scope.clinicId) and
                    (EquipmentUnavailabilities.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .andWhere { EquipmentUnavailabilities.effectiveFrom lessEq to }
            .andWhere {
                (EquipmentUnavailabilities.effectiveUntil.isNull()) or
                    (EquipmentUnavailabilities.effectiveUntil greaterEq from)
            }
            .map { it.toEquipmentUnavailabilityRecord() }
    }

    internal fun findByClinicOnDate(
        clinicId: Long,
        date: LocalDate,
    ): List<EquipmentUnavailabilityRecord> {
        clinicId.requirePositiveNumber("clinicId")
        return EquipmentUnavailabilities
            .selectAll()
            .where { EquipmentUnavailabilities.clinicId eq clinicId }
            .andWhere { EquipmentUnavailabilities.effectiveFrom lessEq date }
            .andWhere {
                (EquipmentUnavailabilities.effectiveUntil.isNull()) or
                    (EquipmentUnavailabilities.effectiveUntil greaterEq date)
            }
            .map { it.toEquipmentUnavailabilityRecord() }
    }

    fun findByClinicOnDate(
        scope: TenantClinicScope,
        date: LocalDate,
    ): List<EquipmentUnavailabilityRecord> {
        return EquipmentUnavailabilities
            .selectAll()
            .where {
                (EquipmentUnavailabilities.clinicId eq scope.clinicId) and
                    (EquipmentUnavailabilities.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .andWhere { EquipmentUnavailabilities.effectiveFrom lessEq date }
            .andWhere {
                (EquipmentUnavailabilities.effectiveUntil.isNull()) or
                    (EquipmentUnavailabilities.effectiveUntil greaterEq date)
            }
            .map { it.toEquipmentUnavailabilityRecord() }
    }

    internal fun delete(id: Long) {
        id.requirePositiveNumber("id")
        EquipmentUnavailabilities.deleteWhere { EquipmentUnavailabilities.id eq id }
        log.debug { "Deleted EquipmentUnavailability id=$id" }
    }

    /** tenant-clinic 범위에 속한 스케줄만 삭제합니다. */
    fun delete(id: Long, scope: TenantClinicScope): Boolean {
        id.requirePositiveNumber("id")
        val deleted = EquipmentUnavailabilities.deleteWhere {
            (EquipmentUnavailabilities.id eq id) and
                (EquipmentUnavailabilities.clinicId eq scope.clinicId) and
                (EquipmentUnavailabilities.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
        }
        if (deleted > 0) {
            log.debug { "Deleted EquipmentUnavailability id=$id for scope=${scope.cacheKey()}" }
        }
        return deleted > 0
    }

    internal fun addException(
        unavailabilityId: Long,
        originalDate: LocalDate,
        exceptionType: ExceptionType,
        rescheduledDate: LocalDate?,
        rescheduledStartTime: LocalTime?,
        rescheduledEndTime: LocalTime?,
        reason: String?,
    ): EquipmentUnavailabilityExceptionRecord {
        unavailabilityId.requirePositiveNumber("unavailabilityId")

        val id = EquipmentUnavailabilityExceptions.insertAndGetId {
            it[EquipmentUnavailabilityExceptions.unavailabilityId] = unavailabilityId
            it[EquipmentUnavailabilityExceptions.originalDate] = originalDate
            it[EquipmentUnavailabilityExceptions.exceptionType] = exceptionType
            it[EquipmentUnavailabilityExceptions.rescheduledDate] = rescheduledDate
            it[EquipmentUnavailabilityExceptions.rescheduledStartTime] = rescheduledStartTime
            it[EquipmentUnavailabilityExceptions.rescheduledEndTime] = rescheduledEndTime
            it[EquipmentUnavailabilityExceptions.reason] = reason
        }.value

        log.debug { "Added EquipmentUnavailabilityException id=$id for unavailabilityId=$unavailabilityId" }

        return EquipmentUnavailabilityExceptionRecord(
            id = id,
            unavailabilityId = unavailabilityId,
            originalDate = originalDate,
            exceptionType = exceptionType,
            rescheduledDate = rescheduledDate,
            rescheduledStartTime = rescheduledStartTime,
            rescheduledEndTime = rescheduledEndTime,
            reason = reason,
        )
    }

    internal fun findExceptions(unavailabilityId: Long): List<EquipmentUnavailabilityExceptionRecord> {
        unavailabilityId.requirePositiveNumber("unavailabilityId")
        return EquipmentUnavailabilityExceptions
            .selectAll()
            .where { EquipmentUnavailabilityExceptions.unavailabilityId eq unavailabilityId }
            .map { it.toEquipmentUnavailabilityExceptionRecord() }
    }

    internal fun deleteException(exceptionId: Long) {
        exceptionId.requirePositiveNumber("exceptionId")
        EquipmentUnavailabilityExceptions.deleteWhere {
            EquipmentUnavailabilityExceptions.id eq exceptionId
        }
        log.debug { "Deleted EquipmentUnavailabilityException id=$exceptionId" }
    }

    /** 부모 스케줄이 지정한 예외만 삭제합니다. */
    internal fun deleteException(unavailabilityId: Long, exceptionId: Long): Boolean {
        unavailabilityId.requirePositiveNumber("unavailabilityId")
        exceptionId.requirePositiveNumber("exceptionId")
        val deleted = EquipmentUnavailabilityExceptions.deleteWhere {
            (EquipmentUnavailabilityExceptions.id eq exceptionId) and
                (EquipmentUnavailabilityExceptions.unavailabilityId eq unavailabilityId)
        }
        if (deleted > 0) {
            log.debug {
                "Deleted EquipmentUnavailabilityException id=$exceptionId for unavailabilityId=$unavailabilityId"
            }
        }
        return deleted > 0
    }
}
