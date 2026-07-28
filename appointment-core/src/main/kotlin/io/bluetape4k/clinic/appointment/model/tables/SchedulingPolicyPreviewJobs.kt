package io.bluetape4k.clinic.appointment.model.tables

import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * owner-fenced checkpoint를 가진 durable bounded impact-preview job table입니다.
 */
object SchedulingPolicyPreviewJobs : LongIdTable("scheduling_policy_preview_jobs") {
    /** scan되는 모든 row의 양수 tenant boundary입니다. */
    val tenantGroupId = long("tenant_group_id")

    /** tenant baseline 전체 또는 clinic override인 preview 범위입니다. */
    val scope = enumerationByName<PolicyScope>("scope", 32)

    /** clinic override의 양수 병원 경계입니다. tenant baseline에서는 `null`입니다. */
    val clinicId = long("clinic_id").nullable()

    /** tenant `0`, clinic override 양수 ID인 방언 독립 queue/index key입니다. */
    val clinicScopeKey = long("clinic_scope_key")

    /** preview 대상 draft definition입니다. */
    val definitionId = long("definition_id")

    /** 정확한 draft revision입니다. mismatch가 발생하면 job은 stale입니다. */
    val draftRevision = long("draft_revision")

    /** resume마다 기대하는 tenant generation입니다. */
    val tenantGeneration = long("tenant_generation")

    /** resume마다 기대하는 clinic generation입니다. */
    val clinicGeneration = long("clinic_generation")

    /**
     * tenant preview가 고정한 tenant head `clinicGenerationEpoch`의 정규 SHA-256입니다.
     *
     * clinic override 세대가 바뀌면 같은 트랜잭션에서 epoch가 증가합니다. 따라서 worker는
     * 병원·scope-head 전체를 스캔하지 않고 tenant head 한 행만 읽어 freshness를 확인합니다.
     * clinic preview는 정확한 [clinicGeneration] 하나로 충분하므로 `null`입니다.
     */
    val clinicGenerationDigest = varchar("clinic_generation_digest", 64).nullable()

    /** deterministic resume을 위한 양수 fixed partition count입니다. */
    val partitionCount = integer("partition_count")

    /** 저장된 zero-based partition cursor입니다. */
    val cursorPartition = integer("cursor_partition").default(0)

    /**
     * 현재 partition에서 마지막으로 처리한 양수 appointment ID입니다.
     *
     * `null`은 [cursorPartition]을 증가시킨 직후를 포함해 partition의 첫 row를 아직
     * 처리하지 않았다는 뜻입니다. resume logic은 `null`을 zero처럼 취급하지 않고 해당
     * partition 시작부터 진행해야 합니다.
     */
    val cursorLastAppointmentId = long("cursor_last_appointment_id").nullable()

    /** tenant 전체 scan을 재개할 때 마지막으로 처리한 양수 병원 ID입니다. */
    val cursorClinicId = long("cursor_clinic_id").nullable()

    /** 복합 impact cursor의 마지막 UTC scheduled instant입니다. */
    val cursorScheduledAt = timestamp("cursor_scheduled_at").nullable()

    /** 복합 impact cursor의 안정적인 aggregate enum 이름입니다. */
    val cursorAggregateType = varchar("cursor_aggregate_type", 32).nullable()

    /** 복합 impact cursor의 양수 database ID 문자열입니다. */
    val cursorAggregateId = varchar("cursor_aggregate_id", 64).nullable()

    /** 검사한 appointment 수입니다. 단조 증가합니다. */
    val scannedCount = long("scanned_count").default(0L)

    /** 영향을 받은 appointment 수입니다. 단조 증가하며 scanned count를 넘을 수 없습니다. */
    val affectedCount = long("affected_count").default(0L)

    /** 현재 durable preview lifecycle입니다. */
    val status = enumerationByName<PolicyPreviewJobStatus>("status", 24)

    /** 이 시각 이후 partial evidence를 사용할 수 없는 UTC hard deadline입니다. */
    val deadlineAt = timestamp("deadline_at")

    /** worker가 claim할 수 있는 가장 이른 UTC instant입니다. */
    val nextAttemptAt = timestamp("next_attempt_at")

    /** preview의 포함 UTC horizon 시작 시각입니다. */
    val horizonFrom = timestamp("horizon_from")

    /** preview의 제외 UTC horizon 종료 시각입니다. 재시작 후에도 변경하지 않습니다. */
    val horizonUntil = timestamp("horizon_until")

    /**
     * 현재 worker의 opaque identity입니다.
     *
     * 이 값과 [leaseUntil]은 [status]가 [PolicyPreviewJobStatus.RUNNING]일 때만
     * 둘 다 non-null입니다. 다른 모든 상태에서는 두 컬럼이 모두 `null`이어야 합니다.
     */
    val leaseOwner = varchar("lease_owner", 160).nullable()

    /**
     * [leaseOwner]의 exclusive UTC fencing deadline입니다.
     *
     * [status]가 [PolicyPreviewJobStatus.RUNNING]일 때만 non-null입니다. 이 instant
     * 이후의 worker는 checkpoint authority를 잃습니다.
     */
    val leaseUntil = timestamp("lease_until").nullable()

    /**
     * 성공한 전체 scan의 canonical lowercase SHA-256입니다.
     *
     * [status]가 [PolicyPreviewJobStatus.COMPLETED]일 때만 non-null입니다. 중간 checkpoint나
     * stale/cancelled/failed 결과는 hash를 남기지 않습니다.
     */
    val resultHash = varchar("result_hash", 64).nullable()

    /**
     * 현재 revision·generation에서 activation에 사용할 수 있는 opaque 증적입니다.
     *
     * [status]가 [PolicyPreviewJobStatus.COMPLETED]일 때만 non-null입니다. 운영 로그, metric,
     * URL path, correlation metadata에 기록하면 안 됩니다.
     */
    val activationEvidenceToken = varchar("activation_evidence_token", 192).nullable()

    /**
     * sanitized stable retry 또는 terminal error code입니다. 실패가 기록되지 않았으면 `null`입니다.
     *
     * raw exception text, appointment data, policy/request payload, credential,
     * authentication claim을 포함하면 안 됩니다.
     */
    val lastErrorCode = varchar("last_error_code", 96).nullable()

    /** database insertion instant입니다. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    /** 마지막 transition 또는 checkpoint의 UTC instant입니다. */
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    init {
        index("idx_policy_preview_due", false, status, nextAttemptAt, leaseUntil)
        index("idx_policy_preview_scope", false, tenantGroupId, scope, clinicScopeKey, id)
        uniqueIndex("uq_policy_preview_evidence_token", activationEvidenceToken)
    }
}
