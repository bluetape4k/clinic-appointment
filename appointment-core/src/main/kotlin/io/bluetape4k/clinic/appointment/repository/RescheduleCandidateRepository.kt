package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.clinic.appointment.model.dto.RescheduleCandidateRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.RescheduleCandidates
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Repository

@Repository
class RescheduleCandidateRepository : LongJdbcRepository<RescheduleCandidateRecord> {
    companion object : KLogging()

    override val table = RescheduleCandidates
    override fun extractId(entity: RescheduleCandidateRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): RescheduleCandidateRecord = toRescheduleCandidateRecord()

    /**
     * Persists a candidate inside the caller's already verified [scope] transaction.
     * The closure service guards the original appointment once before its candidate
     * loop, so this method deliberately does not add one ownership query per row.
     */
    fun save(record: RescheduleCandidateRecord, scope: TenantClinicScope): RescheduleCandidateRecord {
        require(scope.tenantGroupId > 0L && scope.clinicId > 0L) { "candidate scope must be positive" }
        val id = RescheduleCandidates.insertAndGetId {
            it[originalAppointmentId] = record.originalAppointmentId
            it[candidateDate] = record.candidateDate
            it[startTime] = record.startTime
            it[endTime] = record.endTime
            it[doctorId] = record.doctorId
            it[priority] = record.priority
        }.value
        return record.copy(id = id)
    }

    internal fun findBestCandidate(originalAppointmentId: Long): RescheduleCandidateRecord? =
        RescheduleCandidates
            .selectAll()
            .where {
                (RescheduleCandidates.originalAppointmentId eq originalAppointmentId) and
                    (RescheduleCandidates.selected eq false)
            }.orderBy(RescheduleCandidates.priority)
            .firstOrNull()?.toRescheduleCandidateRecord()

    fun findBestCandidate(originalAppointmentId: Long, scope: TenantClinicScope): RescheduleCandidateRecord? =
        RescheduleCandidates
            .innerJoin(Appointments)
            .selectAll()
            .where {
                (RescheduleCandidates.originalAppointmentId eq originalAppointmentId) and
                    (Appointments.clinicId eq scope.clinicId) and
                    (Appointments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId)) and
                    (RescheduleCandidates.selected eq false)
            }
            .orderBy(RescheduleCandidates.priority)
            .firstOrNull()
            ?.toRescheduleCandidateRecord()

    fun findByIdAndScope(candidateId: Long, originalAppointmentId: Long, scope: TenantClinicScope): RescheduleCandidateRecord? =
        RescheduleCandidates
            .innerJoin(Appointments)
            .selectAll()
            .where {
                (RescheduleCandidates.id eq candidateId) and
                    (RescheduleCandidates.originalAppointmentId eq originalAppointmentId) and
                    (Appointments.clinicId eq scope.clinicId) and
                    (Appointments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .firstOrNull()
            ?.toRescheduleCandidateRecord()

    fun findByOriginalAppointmentId(
        originalAppointmentId: Long,
        scope: TenantClinicScope,
    ): List<RescheduleCandidateRecord> =
        RescheduleCandidates
            .innerJoin(Appointments)
            .selectAll()
            .where {
                (RescheduleCandidates.originalAppointmentId eq originalAppointmentId) and
                    (Appointments.clinicId eq scope.clinicId) and
                    (Appointments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .orderBy(RescheduleCandidates.priority)
            .map { it.toRescheduleCandidateRecord() }

    internal fun markSelected(candidateId: Long): Int =
        RescheduleCandidates.update(where = { RescheduleCandidates.id eq candidateId }) {
            it[selected] = true
        }

    fun markSelected(candidateId: Long, originalAppointmentId: Long, scope: TenantClinicScope): Int {
        return RescheduleCandidates.update(
            where = {
                (RescheduleCandidates.id eq candidateId) and
                    (RescheduleCandidates.originalAppointmentId eq originalAppointmentId) and
                    (RescheduleCandidates.originalAppointmentId inSubQuery
                        Appointments
                            .select(Appointments.id)
                            .where {
                                (Appointments.id eq originalAppointmentId) and
                                    (Appointments.clinicId eq scope.clinicId) and
                                    (Appointments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
                            })
            },
        ) {
            it[selected] = true
        }
    }
}
