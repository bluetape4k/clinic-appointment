package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 동일 구매 Plan의 불변 revision header입니다.
 *
 * 상품 version 전환은 새 Plan을 만들지 않고 이 table에 새 row를 append합니다. [active]는
 * 새 제안 계산 기준이며 과거 revision과 완료 항목을 삭제하지 않습니다.
 */
object AppointmentPlanRevisions : LongIdTable("scheduling_appointment_plan_revisions") {
    val planId = reference("plan_id", AppointmentPlans, onDelete = ReferenceOption.CASCADE)
    val revision = long("revision")
    val productVersionId = varchar("product_version_id", 128)
    val snapshotHash = varchar("snapshot_hash", 64)
    val active = bool("active")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_plan_revision", planId, revision)
        index("idx_plan_revision_active", false, planId, active)
    }
}
