package io.bluetape4k.clinic.appointment.api.migration

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** 환자 scope backfill의 version·dialect·마지막 detail PK만 저장하는 비식별 checkpoint입니다. */
object PatientHistoryBackfillCheckpoint : Table("scheduling_patient_history_backfill_checkpoint") {
    val scope = varchar("scope", 64)
    val migrationVersion = integer("migration_version").default(30)
    val dialect = varchar("dialect", 16)
    val lastDetailId = long("last_detail_id").default(0L)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(scope, name = "pk_patient_history_backfill_checkpoint")

    const val GLOBAL_SCOPE = "patient-cancellation-history"
}
