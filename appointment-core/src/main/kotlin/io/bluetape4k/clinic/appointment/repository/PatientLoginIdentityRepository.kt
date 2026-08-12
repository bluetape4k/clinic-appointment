package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.PatientLoginIdentityRecord
import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifierKey
import io.bluetape4k.clinic.appointment.model.tables.PatientLoginIdentities
import io.bluetape4k.clinic.appointment.model.tables.PatientAccounts
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId

/** 환자 login identity repository입니다. tenant 조건을 생략한 조회를 제공하지 않습니다. */
class PatientLoginIdentityRepository : LongJdbcRepository<PatientLoginIdentityRecord> {
    override val table = PatientLoginIdentities
    override fun extractId(entity: PatientLoginIdentityRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): PatientLoginIdentityRecord = toPatientLoginIdentityRecord()

    fun save(record: PatientLoginIdentityRecord): PatientLoginIdentityRecord {
        val id = PatientLoginIdentities.insertAndGetId {
            it[patientAccountId] = record.patientAccountId
            it[tenantGroupId] = record.tenantGroupId
            it[key] = record.key
            it[normalizedValue] = record.normalizedValue
        }.value
        return record.copy(id = id)
    }

    fun findActiveByIdentifier(
        tenantGroupId: Long,
        key: PatientLoginIdentifierKey,
        normalizedValue: String,
    ): PatientLoginIdentityRecord? =
        PatientLoginIdentities
            .innerJoin(PatientAccounts)
            .selectAll()
            .where {
                (PatientLoginIdentities.tenantGroupId eq tenantGroupId) and
                    (PatientLoginIdentities.key eq key) and
                    (PatientLoginIdentities.normalizedValue eq normalizedValue) and
                    (PatientAccounts.tenantGroupId eq tenantGroupId) and
                    (PatientAccounts.active eq true)
            }
            .firstOrNull()
            ?.toPatientLoginIdentityRecord()

    fun findByAccountId(tenantGroupId: Long, patientAccountId: Long): List<PatientLoginIdentityRecord> =
        PatientLoginIdentities
            .selectAll()
            .where {
                (PatientLoginIdentities.tenantGroupId eq tenantGroupId) and
                    (PatientLoginIdentities.patientAccountId eq patientAccountId)
            }
            .map { it.toPatientLoginIdentityRecord() }
}
