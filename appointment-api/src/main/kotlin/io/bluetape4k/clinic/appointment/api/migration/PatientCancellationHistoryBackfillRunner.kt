package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.clinic.appointment.model.tables.AppointmentCancellationDetails
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant

/**
 * V28 legacy row를 고정된 500행 이하 transaction으로 보완합니다.
 *
 * checkpoint에는 migration version·dialect·마지막 detail PK만 저장하며, patient 식별자나
 * tenant 식별자를 저장하지 않습니다. scope 불일치·fingerprint 부재 row는 추정하지 않고
 * residual로 남겨 readiness가 endpoint를 계속 fail-closed하도록 합니다.
 */
class PatientCancellationHistoryBackfillRunner(
    private val database: Database,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val now: () -> Instant = Instant::now,
) {
    init {
        require(batchSize in 1..MAX_BATCH_SIZE) { "patient history backfill batch size must be 1..$MAX_BATCH_SIZE" }
    }

    @Scheduled(fixedDelayString = "\${appointment.patient-history.backfill-interval:5s}")
    fun runBatch(): PatientHistoryBackfillBatchResult = transaction(database) {
        PatientHistoryBackfillCheckpoint.upsert(
            PatientHistoryBackfillCheckpoint.scope,
            onUpdate = { it[PatientHistoryBackfillCheckpoint.scope] = PatientHistoryBackfillCheckpoint.GLOBAL_SCOPE },
        ) {
            it[scope] = PatientHistoryBackfillCheckpoint.GLOBAL_SCOPE
            it[migrationVersion] = MIGRATION_VERSION
            it[dialect] = currentDialect()
            it[lastDetailId] = 0L
            it[updatedAt] = now()
        }
        val checkpoint = PatientHistoryBackfillCheckpoint
            .selectAll()
            .where { PatientHistoryBackfillCheckpoint.scope eq PatientHistoryBackfillCheckpoint.GLOBAL_SCOPE }
            .forUpdate()
            .single()
        val lastDetailId = checkpoint[PatientHistoryBackfillCheckpoint.lastDetailId]
        check(checkpoint[PatientHistoryBackfillCheckpoint.migrationVersion] == MIGRATION_VERSION) {
            "patient history backfill checkpoint version is unsupported"
        }
        check(checkpoint[PatientHistoryBackfillCheckpoint.dialect] == currentDialect()) {
            "patient history backfill checkpoint dialect does not match the current database"
        }
        val join = Join(
            AppointmentCancellationDetails,
            Appointments,
            JoinType.INNER,
            AppointmentCancellationDetails.appointmentId,
            Appointments.id,
        ).join(
            Clinics,
            JoinType.INNER,
            Appointments.clinicId,
            Clinics.id,
        )
        val rows = join.selectAll()
            .where {
                (AppointmentCancellationDetails.patientScopeFingerprint.isNull()) and
                    (AppointmentCancellationDetails.id greater lastDetailId)
            }
            .orderBy(AppointmentCancellationDetails.id to SortOrder.ASC)
            .limit(batchSize)
            .toList()
        if (rows.isEmpty()) return@transaction PatientHistoryBackfillBatchResult(0, 0, lastDetailId, true)

        var updated = 0
        rows.forEach { row ->
            val detailTenant = row[AppointmentCancellationDetails.tenantGroupId].value
            val detailClinic = row[AppointmentCancellationDetails.clinicId].value
            val appointmentClinic = row[Appointments.clinicId].value
            val clinicTenant = row[Clinics.tenantGroupId].value
            val fingerprint = row[Appointments.patientReferenceFingerprint]
            if (detailClinic == appointmentClinic && detailTenant == clinicTenant && fingerprint != null) {
                updated += AppointmentCancellationDetails.update({
                    (AppointmentCancellationDetails.id eq row[AppointmentCancellationDetails.id]) and
                        AppointmentCancellationDetails.patientScopeFingerprint.isNull()
                }) {
                    it[patientScopeFingerprint] = fingerprint
                }
            }
        }
        val newLastDetailId = rows.last()[AppointmentCancellationDetails.id].value
        PatientHistoryBackfillCheckpoint.update({
            PatientHistoryBackfillCheckpoint.scope eq PatientHistoryBackfillCheckpoint.GLOBAL_SCOPE
        }) {
            it[PatientHistoryBackfillCheckpoint.lastDetailId] = newLastDetailId
            it[PatientHistoryBackfillCheckpoint.updatedAt] = now()
        }
        PatientHistoryBackfillBatchResult(rows.size, updated, newLastDetailId, rows.size < batchSize)
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 500
        const val MAX_BATCH_SIZE = 500
        const val MIGRATION_VERSION = 30
    }

    private fun currentDialect(): String {
        val name = org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().db.dialect.name.lowercase()
        return when {
            "postgres" in name -> "postgresql"
            "mysql" in name -> "mysql"
            "h2" in name -> "h2"
            else -> name.take(16)
        }
    }
}

data class PatientHistoryBackfillBatchResult(
    val scanned: Int,
    val updated: Int,
    val lastDetailId: Long,
    val exhausted: Boolean,
)
