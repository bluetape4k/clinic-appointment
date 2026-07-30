package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 예약 한 건에 대한 비식별 재평가 결과 감사 행입니다.
 */
object ProfileReevaluationOutcomes : LongIdTable("scheduling_profile_reevaluation_outcomes") {
    val jobId = long("job_id")
    val targetRevision = long("target_revision")
    val appointmentId = long("appointment_id")
    val outcomeType = enumerationByName<ProfileReevaluationOutcomeType>("outcome_type", 32)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "uq_profile_reevaluation_outcome",
            jobId,
            targetRevision,
            appointmentId,
        )
    }
}
