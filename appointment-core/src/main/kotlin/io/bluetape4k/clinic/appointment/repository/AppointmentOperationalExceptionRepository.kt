package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalException
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentOperationalExceptions
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * caller transaction 안에서 운영 예외를 append하고 명시적으로 인지·해결합니다.
 */
class AppointmentOperationalExceptionRepository {

    /** 예외 사실을 append하고 생성된 양수 ID를 반환합니다. */
    fun append(exception: AppointmentOperationalException): Long {
        requireCurrentExposedTransaction("AppointmentOperationalExceptionRepository")
        return AppointmentOperationalExceptions.insertAndGetId {
            it[appointmentPlanId] = exception.appointmentPlanId
            it[appointmentId] = exception.appointmentId
            it[type] = exception.type
            it[reasonCode] = exception.reasonCode
            it[status] = exception.status
            it[openedAt] = exception.openedAt
            it[resolvedAt] = exception.resolvedAt
        }.value
    }

    /** 열린 예외를 운영자가 인지한 상태로 이동합니다. */
    fun acknowledge(exceptionId: Long): Boolean {
        requireCurrentExposedTransaction("AppointmentOperationalExceptionRepository")
        exceptionId.requirePositiveNumber("exceptionId")
        return AppointmentOperationalExceptions.update(
            where = {
                (AppointmentOperationalExceptions.id eq exceptionId) and
                    (AppointmentOperationalExceptions.status eq AppointmentOperationalExceptionStatus.OPEN)
            },
        ) {
            it[status] = AppointmentOperationalExceptionStatus.ACKNOWLEDGED
        } == 1
    }

    /** 열린 또는 인지된 예외를 해결하고 결과 시각을 기록합니다. */
    fun resolve(exceptionId: Long, resolvedAt: Instant): Boolean {
        requireCurrentExposedTransaction("AppointmentOperationalExceptionRepository")
        exceptionId.requirePositiveNumber("exceptionId")
        return AppointmentOperationalExceptions.update(
            where = {
                (AppointmentOperationalExceptions.id eq exceptionId) and
                    (AppointmentOperationalExceptions.status neq AppointmentOperationalExceptionStatus.RESOLVED)
            },
        ) {
            it[status] = AppointmentOperationalExceptionStatus.RESOLVED
            it[AppointmentOperationalExceptions.resolvedAt] = resolvedAt
        } == 1
    }
}
