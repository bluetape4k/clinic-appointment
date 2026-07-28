package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 인증 actor scope별 command 선점과 replay 결과를 저장합니다.
 *
 * 같은 key라도 tenant, clinic, actor scope가 다르면 독립 command입니다. 같은 scope와
 * key에 다른 [commandHash]가 오면 안전한 replay가 아니므로 거부합니다.
 */
object AppointmentCommandIdempotencies : LongIdTable("scheduling_appointment_command_idempotencies") {
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)
    val actorScopeHash = varchar("actor_scope_hash", 128)
    val idempotencyKey = varchar("idempotency_key", 255)
    val commandHash = varchar("command_hash", 64)
    val resultType = varchar("result_type", 64).nullable()
    val resultId = long("result_id").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(
            "uq_appointment_command_idempotency",
            tenantGroupId,
            clinicId,
            actorScopeHash,
            idempotencyKey,
        )
    }
}
