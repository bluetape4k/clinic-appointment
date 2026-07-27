package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Append-only approval evidence for an exact policy draft revision.
 *
 * A later definition revision does not delete these rows; it merely makes them
 * stale for activation, preserving the audit trail.
 */
object SchedulingPolicyApprovals : LongIdTable("scheduling_policy_approvals") {
    /** Definition whose exact revision was reviewed. */
    val definitionId = reference(
        "definition_id",
        SchedulingPolicyDefinitions,
        onDelete = ReferenceOption.RESTRICT,
    )

    /** Positive revision reviewed by the actor. */
    val draftRevision = long("draft_revision")

    /** Stable trusted Gateway subject of the approver. */
    val actorId = varchar("actor_id", 160)

    /** Role used for authority and separation-of-duties evaluation. */
    val actorRole = enumerationByName<ActorRole>("actor_role", 24)

    /** Bounded authentication-assurance label, never a token or credential. */
    val assuranceLevel = varchar("assurance_level", 64)

    /** UTC instant at which approval evidence was recorded. */
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
