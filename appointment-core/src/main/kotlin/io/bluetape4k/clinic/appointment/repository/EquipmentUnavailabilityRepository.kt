package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityExceptionRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentUnavailabilityRecord
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
import io.bluetape4k.clinic.appointment.model.tables.ExceptionType
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
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

    fun findById(id: Long): EquipmentUnavailabilityRecord? {
        id.requirePositiveNumber("id")
        return EquipmentUnavailabilities
            .selectAll()
            .where { EquipmentUnavailabilities.id eq id }
            .map { it.toEquipmentUnavailabilityRecord() }
            .firstOrNull()
    }

    fun findByEquipment(
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

    fun findByClinicOnDate(
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

    fun delete(id: Long) {
        id.requirePositiveNumber("id")
        EquipmentUnavailabilities.deleteWhere { EquipmentUnavailabilities.id eq id }
        log.debug { "Deleted EquipmentUnavailability id=$id" }
    }

    fun addException(
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

    fun findExceptions(unavailabilityId: Long): List<EquipmentUnavailabilityExceptionRecord> {
        unavailabilityId.requirePositiveNumber("unavailabilityId")
        return EquipmentUnavailabilityExceptions
            .selectAll()
            .where { EquipmentUnavailabilityExceptions.unavailabilityId eq unavailabilityId }
            .map { it.toEquipmentUnavailabilityExceptionRecord() }
    }

    fun deleteException(exceptionId: Long) {
        exceptionId.requirePositiveNumber("exceptionId")
        EquipmentUnavailabilityExceptions.deleteWhere {
            EquipmentUnavailabilityExceptions.id eq exceptionId
        }
        log.debug { "Deleted EquipmentUnavailabilityException id=$exceptionId" }
    }
}
