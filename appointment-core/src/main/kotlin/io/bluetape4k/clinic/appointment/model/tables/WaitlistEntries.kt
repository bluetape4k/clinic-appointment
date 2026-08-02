package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistEntryState
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.regexp
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestamp

/** 대기열 요청의 현재 상태와 후보 검색용 시간·우선순위 snapshot을 저장합니다. */
object WaitlistEntries : LongIdTable("scheduling_waitlist_entries") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val memberId = varchar("member_id", 255)
    val treatmentTypeId = reference("treatment_type_id", TreatmentTypes, onDelete = ReferenceOption.RESTRICT)
    val doctorId = reference("doctor_id", Doctors, onDelete = ReferenceOption.SET_NULL).nullable()
    val preferredDateFrom = date("preferred_date_from")
    val preferredDateTo = date("preferred_date_to")
    val preferredStartTime = time("preferred_start_time")
    val preferredEndTime = time("preferred_end_time")
    val priorityRank = integer("priority_rank")
    val status = enumerationByName<WaitlistEntryState>("status", 32)
        .check("ck_waitlist_entry_status") { it inList WaitlistEntryState.entries }
    val waitingSince = timestamp("waiting_since")
    val version = long("version").default(0L)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        check("ck_waitlist_entry_member_opaque") {
            memberId regexp "^[^@\\s]{1,255}$"
        }
        check("ck_waitlist_entry_date_range") { preferredDateFrom lessEq preferredDateTo }
        check("ck_waitlist_entry_time_range") { preferredStartTime less preferredEndTime }
        index(
            "idx_waitlist_entry_candidate",
            false,
            tenantGroupId,
            clinicId,
            treatmentTypeId,
            status,
            preferredDateFrom,
            preferredDateTo,
            priorityRank,
            waitingSince,
            id,
        )
        index(
            "idx_waitlist_entry_doctor_candidate",
            false,
            tenantGroupId,
            clinicId,
            doctorId,
            treatmentTypeId,
            status,
            preferredDateFrom,
            preferredDateTo,
            priorityRank,
            waitingSince,
            id,
        )
    }
}
