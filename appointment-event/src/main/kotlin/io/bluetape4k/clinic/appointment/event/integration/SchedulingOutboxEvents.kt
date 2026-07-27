package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Generic durable scheduling events waiting for downstream publication.
 *
 * New writers always populate [aggregateType] and [aggregateId]. The columns
 * remain nullable only for rolling compatibility with pre-V9 writers, and
 * [SchedulingEventRepository.readOutboxDualWriteConvergence] must report zero
 * missing identities before a later migration makes them mandatory.
 *
 * Event-driven plan rows preserve [causationEventId], [clinicId], and [planId].
 * Command-driven tenant policy rows legitimately leave all three `null`; they
 * use [correlationId] for trace continuity without inventing event lineage.
 */
object SchedulingOutboxEvents : LongIdTable("scheduling_outbox_events") {
    /** Stable deterministic event identity used for publisher deduplication. */
    val eventId = varchar("event_id", 128)

    /**
     * Real upstream event identity, or `null` for command-driven events.
     *
     * Correlation IDs and this event's own ID must never be substituted for a
     * missing cause because that would falsify event lineage.
     */
    val causationEventId = varchar("causation_event_id", 128).nullable()

    /** Bounded request/workflow trace identifier; it is not a causation ID. */
    val correlationId = varchar("correlation_id", 128)

    /** Closed consumer-facing event contract name. */
    val eventType = varchar("event_type", 128)

    /** Required tenant ownership boundary for every outbox row. */
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)

    /**
     * Clinic ownership boundary for clinic-scoped events.
     *
     * It is `null` only for tenant-scoped aggregates. Plan writers always
     * preserve the real clinic foreign key.
     */
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.RESTRICT).nullable()

    /**
     * Legacy plan foreign key retained for backward-compatible consumers.
     *
     * It is non-null for `APPOINTMENT_PLAN` events and `null` for non-plan
     * aggregates. Generic routing uses [aggregateType] and [aggregateId].
     */
    val planId = reference("plan_id", AppointmentPlans, onDelete = ReferenceOption.RESTRICT).nullable()

    /**
     * Generic aggregate category, such as `APPOINTMENT_PLAN` or
     * `SCHEDULING_POLICY`.
     *
     * New writers must populate it. `null` identifies a legacy writer that has
     * not converged and therefore blocks policy publication/cutover.
     */
    val aggregateType = varchar("aggregate_type", 64).nullable()

    /**
     * Stable aggregate-local identity encoded as bounded text.
     *
     * New writers must populate it. Plan events use the decimal plan ID and
     * policy events use the decimal immutable definition ID.
     */
    val aggregateId = varchar("aggregate_id", 160).nullable()

    /** Positive wire-schema version of [payloadJson]. */
    val schemaVersion = integer("schema_version")

    /**
     * Redacted event payload JSON.
     *
     * It may contain stable IDs, generations, hashes, and bounded actor audit
     * references, but never credentials, bearer tokens, patient references,
     * raw policy payloads, or idempotency keys.
     */
    val payloadJson = text("payload_json")

    /** Current publisher lifecycle. */
    val status = enumerationByName<SchedulingOutboxStatus>("status", 32)

    /** Number of completed publication attempts; it starts at zero. */
    val attemptCount = integer("attempt_count").default(0)

    /** Earliest UTC retry instant, or `null` while no retry is scheduled. */
    val nextAttemptAt = timestamp("next_attempt_at").nullable()

    /** Database insertion instant in UTC. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    /** UTC successful publication instant, or `null` until published. */
    val publishedAt = timestamp("published_at").nullable()

    init {
        uniqueIndex("uq_outbox_event_id", eventId)
        index("idx_outbox_plan_id", false, planId)
        index("idx_outbox_status_created_at", false, status, createdAt)
        index("idx_outbox_status_next_attempt", false, status, nextAttemptAt)
        index("idx_outbox_aggregate", false, aggregateType, aggregateId, createdAt)
    }
}

/** Durable publisher lifecycle for one generic scheduling event. */
enum class SchedulingOutboxStatus {
    /** Waiting for the first or next publication attempt. */
    PENDING,

    /** Successfully published; [SchedulingOutboxEvents.publishedAt] is set. */
    PUBLISHED,

    /** Retry policy was exhausted or an operator must intervene. */
    FAILED,
}
