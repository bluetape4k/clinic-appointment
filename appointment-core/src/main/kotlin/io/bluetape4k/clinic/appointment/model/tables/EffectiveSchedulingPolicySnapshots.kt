package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * compile된 scheduling-policy snapshot을 immutable하게 저장하는 table입니다.
 *
 * 같은 hash라도 동일 tenant와 clinic boundary 안에서만 재사용 가능합니다. snapshot row는
 * insertion 이후 update하지 않습니다.
 */
object EffectiveSchedulingPolicySnapshots : LongIdTable("effective_scheduling_policy_snapshots") {
    /** 양수 tenant boundary입니다. */
    val tenantGroupId = long("tenant_group_id")

    /** snapshot이 compile된 양수 clinic입니다. */
    val clinicId = long("clinic_id")

    /** canonical hash에 포함되는 UTC policy decision instant입니다. */
    val decisionAt = timestamp("decision_at")

    /** canonical hash에 포함되는 UTC service instant입니다. */
    val serviceAt = timestamp("service_at")

    /** persistence 시 관찰하고 재검증한 tenant generation입니다. */
    val tenantGeneration = long("tenant_generation")

    /** persistence 시 관찰하고 재검증한 clinic generation입니다. */
    val clinicGeneration = long("clinic_generation")

    /** canonical source-version map JSON입니다. */
    val sourceVersionsJson = text("source_versions_json")

    /** canonical compiled-leaf source map JSON입니다. */
    val sourceByPathJson = text("source_by_path_json")

    /** canonical sorted disabled-feature array JSON입니다. */
    val disabledFeaturesJson = text("disabled_features_json")

    /** 순서를 보존하는 고객-safe warning array JSON입니다. */
    val warningsJson = text("warnings_json")

    /** fully compiled policy의 canonical JSON입니다. */
    val payloadJson = text("payload_json")

    /** canonical snapshot의 lowercase 64-character SHA-256입니다. */
    val snapshotHash = varchar("snapshot_hash", 64)

    /** database insertion instant입니다. update path는 없습니다. */
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
