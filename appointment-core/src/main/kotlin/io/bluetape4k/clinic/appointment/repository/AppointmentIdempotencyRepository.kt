package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.clinic.appointment.model.dto.AppointmentIdempotencyRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentIdempotencies
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/** 예약 생성 idempotency의 record CRUD와 scope 특수 조회를 제공하는 caller-owned repository. */
class AppointmentIdempotencyRepository : LongJdbcRepository<AppointmentIdempotencyRecord> {

    override val table = AppointmentIdempotencies

    override fun extractId(entity: AppointmentIdempotencyRecord): Long =
        entity.id.requireNotNull("id")

    override fun ResultRow.toEntity(): AppointmentIdempotencyRecord = toAppointmentIdempotencyRecord()

    fun save(record: AppointmentIdempotencyRecord): AppointmentIdempotencyRecord {
        val id = AppointmentIdempotencies.insertAndGetId {
            it[tenantGroupId] = record.tenantGroupId
            it[clinicId] = record.clinicId
            it[idempotencyKey] = record.idempotencyKey
            it[requestFingerprint] = record.requestFingerprint
            it[appointmentId] = record.appointmentId
            it[expiresAt] = record.expiresAt
        }.value
        return record.copy(id = id)
    }

    fun findByTenantGroupAndClinicAndKey(
        tenantGroupId: Long,
        clinicId: Long,
        idempotencyKey: String,
    ): AppointmentIdempotencyRecord? =
        AppointmentIdempotencies
            .selectAll()
            .where {
                (AppointmentIdempotencies.tenantGroupId eq tenantGroupId) and
                    (AppointmentIdempotencies.clinicId eq clinicId) and
                    (AppointmentIdempotencies.idempotencyKey eq idempotencyKey)
            }
            .firstOrNull()
            ?.toAppointmentIdempotencyRecord()

    fun deleteExpired(
        tenantGroupId: Long,
        clinicId: Long,
        idempotencyKey: String,
        now: Instant,
    ): Int =
        AppointmentIdempotencies.deleteWhere {
            (AppointmentIdempotencies.tenantGroupId eq tenantGroupId) and
                (AppointmentIdempotencies.clinicId eq clinicId) and
                (AppointmentIdempotencies.idempotencyKey eq idempotencyKey) and
                (AppointmentIdempotencies.expiresAt lessEq now)
        }

    private fun ResultRow.toAppointmentIdempotencyRecord() = AppointmentIdempotencyRecord(
        id = this[AppointmentIdempotencies.id].value,
        tenantGroupId = this[AppointmentIdempotencies.tenantGroupId].value,
        clinicId = this[AppointmentIdempotencies.clinicId].value,
        idempotencyKey = this[AppointmentIdempotencies.idempotencyKey],
        requestFingerprint = this[AppointmentIdempotencies.requestFingerprint],
        appointmentId = this[AppointmentIdempotencies.appointmentId].value,
        expiresAt = this[AppointmentIdempotencies.expiresAt],
        createdAt = this[AppointmentIdempotencies.createdAt],
    )
}
