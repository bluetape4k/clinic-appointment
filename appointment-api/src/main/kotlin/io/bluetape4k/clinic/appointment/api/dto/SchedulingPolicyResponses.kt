package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyActivationResult
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyMutationResult
import io.bluetape4k.clinic.appointment.api.policy.TenantEffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyActivationCommandRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyApprovalRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.policy.CompiledSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.PolicyValueSource
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationTargets
import io.bluetape4k.clinic.appointment.model.policy.SourceVersion
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * API가 공개하는 tenant/clinic effective generation vector다.
 *
 * @property tenantGeneration tenant baseline이 활성화될 때 증가하는 non-negative 세대.
 * @property clinicGeneration clinic override가 활성화될 때 증가하는 non-negative 세대.
 * tenant route에서는 항상 `0`이다.
 */
@Schema(description = "Current tenant and clinic policy generations")
data class PolicyGenerationResponse(
    val tenantGeneration: Long,
    val clinicGeneration: Long,
)

/**
 * draft 생성·검증·retire 결과를 위한 공통 정책 정의 projection이다.
 *
 * payload, actor ID, approval metadata는 관리 응답에 반사하지 않는다. caller가 다음 CAS
 * 명령을 구성하는 데 필요한 definition/revision/lifecycle/head generation만 공개한다.
 *
 * @property definitionId scope 안에서 정책 정의를 식별하는 양수 database ID.
 * @property draftRevision immutable definition row의 양수 revision.
 * @property lifecycle 현재 정책 정의의 lifecycle 상태.
 * @property generation 응답 생성 시점에 관측한 tenant/clinic effective 세대.
 * @property scopeRevision 다음 scope CAS 명령에 전달할 non-negative head revision.
 * @property correlationId Gateway부터 이어지는 privacy-safe 요청 추적 ID.
 */
@Schema(description = "Scheduling-policy definition lifecycle and current scope head")
data class SchedulingPolicyMutationResponse(
    val definitionId: Long,
    val draftRevision: Long,
    val lifecycle: PolicyLifecycle,
    val generation: PolicyGenerationResponse,
    val scopeRevision: Long,
    val correlationId: String,
) {
    companion object {
        fun from(
            result: SchedulingPolicyMutationResult,
            generation: PolicyGenerationResponse,
            correlationId: String,
        ): SchedulingPolicyMutationResponse =
            SchedulingPolicyMutationResponse(
                definitionId = requireNotNull(result.definition.id),
                draftRevision = result.definition.revision,
                lifecycle = result.definition.lifecycle,
                generation = generation,
                scopeRevision = result.head.revision,
                correlationId = correlationId,
            )

        fun validated(
            definition: SchedulingPolicyDefinitionRecord,
            generation: PolicyGenerationResponse,
            scopeRevision: Long,
            correlationId: String,
        ): SchedulingPolicyMutationResponse =
            SchedulingPolicyMutationResponse(
                definitionId = requireNotNull(definition.id),
                draftRevision = definition.revision,
                lifecycle = definition.lifecycle,
                generation = generation,
                scopeRevision = scopeRevision,
                correlationId = correlationId,
            )
    }
}

/**
 * 정확한 draft revision에 기록된 승인 결과다.
 *
 * @property definitionId 승인한 정책 정의의 양수 database ID.
 * @property draftRevision 승인 증거가 고정된 양수 immutable revision.
 * @property approvedAt 기준 데이터베이스에 승인이 기록된 UTC 시각.
 * @property correlationId 승인 HTTP 요청의 privacy-safe 추적 ID.
 */
@Schema(description = "Recorded scheduling-policy approval evidence")
data class SchedulingPolicyApprovalResponse(
    val definitionId: Long,
    val draftRevision: Long,
    val approvedAt: Instant,
    val correlationId: String,
) {
    companion object {
        fun from(
            approval: SchedulingPolicyApprovalRecord,
            correlationId: String,
        ): SchedulingPolicyApprovalResponse =
            SchedulingPolicyApprovalResponse(
                definitionId = approval.definitionId,
                draftRevision = approval.draftRevision,
                approvedAt = approval.approvedAt,
                correlationId = correlationId,
            )
    }
}

/**
 * 예약되었거나 완료된 durable activation command projection이다.
 *
 * @property commandId 새로 만든 durable activation command의 양수 database ID.
 * @property definitionId command가 참조하는 정책 정의 ID.
 * @property draftRevision preview와 승인이 고정한 definition revision.
 * @property lifecycle schedule이면 `SCHEDULED`, 즉시 실행이면 실행 후 definition lifecycle.
 * @property generation command가 고정한 tenant/clinic generation vector.
 * @property status durable command의 현재 상태 이름.
 * @property effectiveFrom 정책 definition에 저장된 활성 시작 UTC 시각.
 * @property idempotentReplay 같은 keyed 명령의 기존 결과를 재사용했으면 `true`.
 * @property correlationId 현재 HTTP 요청의 privacy-safe 추적 ID.
 */
@Schema(description = "Durable scheduling-policy activation command")
data class SchedulingPolicyActivationResponse(
    val commandId: Long,
    val definitionId: Long,
    val draftRevision: Long,
    val lifecycle: PolicyLifecycle,
    val generation: PolicyGenerationResponse,
    val status: String,
    val effectiveFrom: Instant,
    val idempotentReplay: Boolean,
    val correlationId: String,
) {
    companion object {
        fun scheduled(
            command: SchedulingPolicyActivationCommandRecord,
            correlationId: String,
        ): SchedulingPolicyActivationResponse =
            SchedulingPolicyActivationResponse(
                commandId = requireNotNull(command.id),
                definitionId = command.definitionId,
                draftRevision = command.expectedDraftRevision,
                lifecycle = PolicyLifecycle.SCHEDULED,
                generation = PolicyGenerationResponse(
                    command.expectedTenantGeneration,
                    command.expectedClinicGeneration,
                ),
                status = command.status.name,
                effectiveFrom = command.effectiveFrom,
                idempotentReplay = false,
                correlationId = correlationId,
            )

        fun activated(
            result: SchedulingPolicyActivationResult,
            correlationId: String,
        ): SchedulingPolicyActivationResponse =
            SchedulingPolicyActivationResponse(
                commandId = requireNotNull(result.command.id),
                definitionId = requireNotNull(result.definition.id),
                draftRevision = result.definition.revision,
                lifecycle = result.definition.lifecycle,
                generation = PolicyGenerationResponse(
                    result.generation.tenantGeneration,
                    result.generation.clinicGeneration,
                ),
                status = result.command.status.name,
                effectiveFrom = result.definition.effectiveFrom,
                idempotentReplay = result.idempotentReplay,
                correlationId = correlationId,
            )
    }
}

/**
 * bounded preview의 누적 진행률이다.
 *
 * @property scannedCount 지금까지 읽은 미래 예약·의무 key의 non-negative 누계.
 * @property affectedCount 후보 정책에 의해 영향받는 key의 non-negative 누계.
 * @property cursorClinicId tenant preview가 마지막으로 처리한 clinic ID.
 * @property cursorScheduledAt clinic preview가 마지막으로 처리한 예약 시각.
 * @property cursorAggregateType tenant preview의 마지막 aggregate 종류.
 * @property cursorAggregateId tenant preview의 마지막 opaque aggregate ID.
 */
@Schema(description = "Monotonic scheduling-policy preview progress")
data class SchedulingPolicyPreviewProgressResponse(
    val scannedCount: Long,
    val affectedCount: Long,
    val cursorClinicId: Long?,
    val cursorScheduledAt: Instant?,
    val cursorAggregateType: String?,
    val cursorAggregateId: String?,
)

/**
 * durable preview job polling projection이다.
 *
 * [activationEvidenceToken]은 완전한 scan이 끝난 `COMPLETED`에서만 반환한다. partial,
 * stale, failed, cancelled row가 영속화 중 결함으로 token 값을 가지고 있더라도 이
 * projection은 fail-closed로 숨긴다.
 *
 * @property jobId scope-aware polling URL에 넣을 양수 durable job ID.
 * @property definitionId preview가 평가하는 정책 정의 ID.
 * @property status `PENDING|RUNNING|COMPLETED|STALE|FAILED|CANCELLED` 중 현재 상태.
 * @property pinnedRevision job 생성 시 고정한 immutable draft revision.
 * @property pinnedGeneration job 생성 시 고정한 tenant/clinic generation vector.
 * @property progress bounded scan의 단조 증가 진행률과 재개 cursor.
 * @property resultHash 완전한 결과의 canonical hash. `COMPLETED`가 아니면 `null`.
 * @property activationEvidenceToken approve/schedule/activate에 전달할 opaque token.
 * `COMPLETED`가 아니면 `null`.
 * @property errorCode 실패 또는 stale 종결 원인을 나타내는 안정 코드. 정상 상태면 `null`.
 * @property correlationId 현재 submit 또는 polling HTTP 요청의 privacy-safe 추적 ID.
 */
@Schema(description = "Scoped bounded scheduling-policy preview job")
data class SchedulingPolicyPreviewResponse(
    val jobId: Long,
    val definitionId: Long,
    val status: PolicyPreviewJobStatus,
    val pinnedRevision: Long,
    val pinnedGeneration: PolicyGenerationResponse,
    val progress: SchedulingPolicyPreviewProgressResponse,
    val resultHash: String?,
    val activationEvidenceToken: String?,
    val errorCode: String?,
    val correlationId: String,
) {
    companion object {
        fun from(
            job: SchedulingPolicyPreviewJobRecord,
            correlationId: String,
        ): SchedulingPolicyPreviewResponse =
            SchedulingPolicyPreviewResponse(
                jobId = requireNotNull(job.id),
                definitionId = job.definitionId,
                status = job.status,
                pinnedRevision = job.draftRevision,
                pinnedGeneration = PolicyGenerationResponse(
                    job.tenantGeneration,
                    job.clinicGeneration,
                ),
                progress = SchedulingPolicyPreviewProgressResponse(
                    scannedCount = job.scannedCount,
                    affectedCount = job.affectedCount,
                    cursorClinicId = job.cursorClinicId,
                    cursorScheduledAt = job.cursorScheduledAt,
                    cursorAggregateType = job.cursorAggregateType,
                    cursorAggregateId = job.cursorAggregateId,
                ),
                resultHash = job.resultHash.takeIf { job.status == PolicyPreviewJobStatus.COMPLETED },
                activationEvidenceToken = job.activationEvidenceToken
                    .takeIf { job.status == PolicyPreviewJobStatus.COMPLETED },
                errorCode = job.lastErrorCode,
                correlationId = correlationId,
            )
    }
}

/**
 * kind별 tenant/clinic source version projection이다.
 *
 * @property tenantVersion compile에 선택된 tenant baseline definition version.
 * @property clinicVersion compile에 선택된 clinic override version. tenant baseline 또는
 * override 부재 시 `null`.
 */
data class SchedulingPolicySourceVersionResponse(
    val tenantVersion: Long,
    val clinicVersion: Long?,
) {
    companion object {
        fun from(source: SourceVersion) =
            SchedulingPolicySourceVersionResponse(source.tenantVersion, source.clinicVersion)
    }
}

/**
 * tenant baseline 또는 clinic-resolved effective 정책의 공통 HTTP projection이다.
 *
 * tenant route는 [clinicId]가 `null`이고 clinic generation이 `0`인 별도 baseline
 * snapshot을 반환한다. clinic route는 영속화된 [EffectiveSchedulingPolicy] hash와
 * 완전히 resolve된 payload를 반환한다. 어느 경로도 가짜 clinic sentinel을 만들지 않는다.
 *
 * @property scope tenant baseline인지 clinic-resolved 결과인지 나타내는 정책 범위.
 * @property clinicId clinic-resolved 결과의 양수 clinic ID. tenant baseline이면 `null`.
 * @property decisionAt 의사결정 기준 정책을 선택한 정규화 UTC 시각.
 * @property serviceAt 시술 시점 기준 정책을 선택한 정규화 UTC 시각.
 * @property generation compile 전후 일치가 확인된 generation vector.
 * @property sourceVersions kind 이름별로 실제 선택된 tenant/clinic definition version.
 * @property payload 모든 정책 kind가 채워진 완전한 typed effective policy.
 * @property snapshotHash scope, 두 시각, generation, source version, payload를 묶은 SHA-256.
 * @property correlationId 현재 effective-read HTTP 요청의 privacy-safe 추적 ID.
 */
@Schema(description = "Effective scheduling policy at explicit decision and service instants")
data class EffectiveSchedulingPolicyResponse(
    val scope: PolicyScope,
    val clinicId: Long?,
    val decisionAt: Instant,
    val serviceAt: Instant,
    val generation: PolicyGenerationResponse,
    val sourceVersions: Map<String, SchedulingPolicySourceVersionResponse>,
    val profileReevaluationTargets: EffectiveProfileReevaluationTargetsResponse,
    val payload: CompiledSchedulingPolicy,
    val snapshotHash: String,
    val correlationId: String,
) {
    companion object {
        fun from(
            effective: TenantEffectiveSchedulingPolicy,
            correlationId: String,
            platformTargets: ProfileReevaluationTargets,
        ): EffectiveSchedulingPolicyResponse =
            EffectiveSchedulingPolicyResponse(
                scope = PolicyScope.TENANT_DEFAULT,
                clinicId = null,
                decisionAt = effective.decisionAt,
                serviceAt = effective.serviceAt,
                generation = PolicyGenerationResponse(
                    effective.tenantGeneration,
                    0L,
                ),
                sourceVersions = effective.sourceVersions
                    .toSortedMap(compareBy { it.name })
                    .mapKeys { it.key.name }
                    .mapValues { SchedulingPolicySourceVersionResponse.from(it.value) },
                profileReevaluationTargets =
                    EffectiveProfileReevaluationTargetsResponse.from(
                        effective.payload,
                        platformTargets,
                        sourceByPath = null,
                    ),
                payload = effective.payload,
                snapshotHash = effective.snapshotHash,
                correlationId = correlationId,
            )

        fun from(
            effective: EffectiveSchedulingPolicy,
            correlationId: String,
            platformTargets: ProfileReevaluationTargets,
        ): EffectiveSchedulingPolicyResponse =
            EffectiveSchedulingPolicyResponse(
                scope = PolicyScope.CLINIC_OVERRIDE,
                clinicId = effective.clinicId,
                decisionAt = effective.decisionAt,
                serviceAt = effective.serviceAt,
                generation = PolicyGenerationResponse(
                    effective.generation.tenantGeneration,
                    effective.generation.clinicGeneration,
                ),
                sourceVersions = effective.sourceVersions
                    .toSortedMap(compareBy { it.name })
                    .mapKeys { it.key.name }
                    .mapValues { SchedulingPolicySourceVersionResponse.from(it.value) },
                profileReevaluationTargets =
                    EffectiveProfileReevaluationTargetsResponse.from(
                        effective.payload,
                        platformTargets,
                        effective.sourceByPath,
                    ),
                payload = effective.payload,
                snapshotHash = effective.snapshotHash,
                correlationId = correlationId,
            )
    }
}

/**
 * 프로필 재평가 처리 목표의 실제 적용값과 조직별 출처를 함께 반환합니다.
 */
data class EffectiveProfileReevaluationTargetsResponse(
    val heldTargetSeconds: Long,
    val heldSource: PolicyValueSource,
    val proposedTargetSeconds: Long,
    val proposedSource: PolicyValueSource,
) {
    companion object {
        private const val HELD_PATH =
            "notificationAndSla.profileReevaluationHeldTargetSeconds"
        private const val PROPOSED_PATH =
            "notificationAndSla.profileReevaluationProposedTargetSeconds"

        fun from(
            payload: CompiledSchedulingPolicy,
            platformTargets: ProfileReevaluationTargets,
            sourceByPath: Map<String, PolicyValueSource>?,
        ): EffectiveProfileReevaluationTargetsResponse {
            val notification = requireNotNull(payload.notificationAndSla) {
                "notificationAndSla policy is required"
            }
            val heldSource =
                sourceByPath?.get(HELD_PATH)
                    ?: if (notification.profileReevaluationHeldTargetSeconds == null) {
                        PolicyValueSource.PLATFORM
                    } else {
                        PolicyValueSource.TENANT
                    }
            val proposedSource =
                sourceByPath?.get(PROPOSED_PATH)
                    ?: if (notification.profileReevaluationProposedTargetSeconds == null) {
                        PolicyValueSource.PLATFORM
                    } else {
                        PolicyValueSource.TENANT
                    }
            return EffectiveProfileReevaluationTargetsResponse(
                heldTargetSeconds =
                    notification.profileReevaluationHeldTargetSeconds
                        ?: requireNotNull(platformTargets.heldTarget).seconds,
                heldSource = heldSource,
                proposedTargetSeconds =
                    notification.profileReevaluationProposedTargetSeconds
                        ?: requireNotNull(platformTargets.proposedTarget).seconds,
                proposedSource = proposedSource,
            )
        }
    }
}

/** OpenAPI가 generic 성공 envelope를 구체 스키마로 표현하기 위한 타입들이다. */
data class SchedulingPolicyMutationApiResponse(
    val success: Boolean,
    val data: SchedulingPolicyMutationResponse,
    val error: String? = null,
)

data class SchedulingPolicyApprovalApiResponse(
    val success: Boolean,
    val data: SchedulingPolicyApprovalResponse,
    val error: String? = null,
)

data class SchedulingPolicyActivationApiResponse(
    val success: Boolean,
    val data: SchedulingPolicyActivationResponse,
    val error: String? = null,
)

data class SchedulingPolicyPreviewApiResponse(
    val success: Boolean,
    val data: SchedulingPolicyPreviewResponse,
    val error: String? = null,
)

data class EffectiveSchedulingPolicyApiResponse(
    val success: Boolean,
    val data: EffectiveSchedulingPolicyResponse,
    val error: String? = null,
)
