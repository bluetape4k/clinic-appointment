package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.PatientAccountRecord
import io.bluetape4k.clinic.appointment.model.tables.PatientAccounts
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/** 환자 계정 repository입니다. 모든 method는 caller의 Exposed transaction을 요구합니다. */
class PatientAccountRepository : LongJdbcRepository<PatientAccountRecord> {
    override val table = PatientAccounts
    override fun extractId(entity: PatientAccountRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): PatientAccountRecord = toPatientAccountRecord()

    fun findActiveById(tenantGroupId: Long, accountId: Long): PatientAccountRecord? =
        PatientAccounts
            .selectAll()
            .where {
                (PatientAccounts.id eq accountId) and
                    (PatientAccounts.tenantGroupId eq tenantGroupId) and
                    (PatientAccounts.active eq true)
            }
            .firstOrNull()
            ?.toPatientAccountRecord()

    fun findActiveBySubject(tenantGroupId: Long, patientSubject: String): PatientAccountRecord? =
        PatientAccounts
            .selectAll()
            .where {
                (PatientAccounts.tenantGroupId eq tenantGroupId) and
                    (PatientAccounts.patientSubject eq patientSubject) and
                    (PatientAccounts.active eq true)
            }
            .firstOrNull()
            ?.toPatientAccountRecord()
}
