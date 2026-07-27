package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Immutable compiled scheduling-policy snapshots.
 *
 * A matching hash is reusable only inside the same tenant and clinic boundary;
 * snapshot rows are never updated after insertion.
 */
object EffectiveSchedulingPolicySnapshots : LongIdTable("effective_scheduling_policy_snapshots") {
    /** Positive tenant boundary. */
    val tenantGroupId = long("tenant_group_id")

    /** Positive clinic for which the snapshot was compiled. */
    val clinicId = long("clinic_id")

    /** UTC policy decision instant included in the canonical hash. */
    val decisionAt = timestamp("decision_at")

    /** UTC service instant included in the canonical hash. */
    val serviceAt = timestamp("service_at")

    /** Tenant generation observed and rechecked at persistence. */
    val tenantGeneration = long("tenant_generation")

    /** Clinic generation observed and rechecked at persistence. */
    val clinicGeneration = long("clinic_generation")

    /** Canonical source-version map JSON. */
    val sourceVersionsJson = text("source_versions_json")

    /** Canonical compiled-leaf source map JSON. */
    val sourceByPathJson = text("source_by_path_json")

    /** Canonical sorted disabled-feature array JSON. */
    val disabledFeaturesJson = text("disabled_features_json")

    /** Ordered customer-safe warning array JSON. */
    val warningsJson = text("warnings_json")

    /** Canonical fully compiled policy JSON. */
    val payloadJson = text("payload_json")

    /** Lowercase 64-character canonical snapshot SHA-256. */
    val snapshotHash = varchar("snapshot_hash", 64)

    /** Database insertion instant; no update path exists. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex("uq_effective_policy_hash", tenantGroupId, clinicId, snapshotHash)
        index(
            "idx_effective_policy_generation",
            false,
            tenantGroupId,
            clinicId,
            tenantGeneration,
            clinicGeneration,
        )
    }
}
