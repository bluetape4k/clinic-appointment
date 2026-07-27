package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 정확한 policy draft revision에 대한 append-only approval evidence table입니다.
 *
 * 이후 definition revision이 생겨도 이 row들은 삭제하지 않습니다. activation에 사용할 수
 * 없게 stale이 될 뿐이며, 감사 trail을 보존합니다.
 */
object SchedulingPolicyApprovals : LongIdTable("scheduling_policy_approvals") {
    /** 정확한 revision이 review된 definition입니다. */
    val definitionId = reference(
        "definition_id",
        SchedulingPolicyDefinitions,
        onDelete = ReferenceOption.RESTRICT,
    )

    /** actor가 review한 양수 revision입니다. */
    val draftRevision = long("draft_revision")

    /** approver의 stable trusted Gateway subject입니다. */
    val actorId = varchar("actor_id", 160)

    /** authority와 separation-of-duties 평가에 사용된 role입니다. */
    val actorRole = enumerationByName<ActorRole>("actor_role", 24)

    /** 제한된 authentication-assurance label입니다. token이나 credential이 아닙니다. */
    val assuranceLevel = varchar("assurance_level", 64)

    /** approval evidence가 기록된 UTC instant입니다. */
    val approvedAt = timestamp("approved_at")

    init {
        uniqueIndex(
            "uq_policy_approval",
            definitionId,
            draftRevision,
            actorId,
        )
    }
}
