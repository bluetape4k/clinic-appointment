package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/** vacancy 생성, worker lease, active vacancy fencing을 저장하는 durable job table입니다. */
object WaitlistVacancyJobs : LongIdTable("scheduling_waitlist_vacancy_jobs") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val vacancyGeneration = long("vacancy_generation")
    val activeVacancyKey = varchar("active_vacancy_key", 128).nullable()
    val policyVersion = long("policy_version")
    val status = enumerationByName<VacancyJobState>("status", 24)
        .check("ck_waitlist_vacancy_job_status") { it inList VacancyJobState.entries }
    val leaseOwner = varchar("lease_owner", 160).nullable()
    val leaseVersion = long("lease_version").default(0L)
    val leaseExpiresAt = timestamp("lease_expires_at").nullable()
    val nextAttemptAt = timestamp("next_attempt_at")
    val vacancyStartsAt = timestamp("vacancy_starts_at")
    val vacancyEndsAt = timestamp("vacancy_ends_at")
    val offeredWaitlistEntryId = long("offered_waitlist_entry_id").nullable()
    val lastErrorCode = varchar("last_error_code", 96).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_waitlist_vacancy_active", tenantGroupId, clinicId, activeVacancyKey)
        index("idx_waitlist_vacancy_due", false, status, nextAttemptAt, leaseExpiresAt)
    }
}
