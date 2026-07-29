package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.clinic.appointment.api.commitment.ConfirmedAppointmentProjectionTarget
import io.bluetape4k.clinic.appointment.api.commitment.CurrentPolicySnapshot
import io.bluetape4k.clinic.appointment.api.commitment.ProposalCandidateSlot
import io.bluetape4k.clinic.appointment.api.commitment.VisitProposalInput
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.policy.EffectiveSchedulingPolicyService
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentVisitIdentityDraft
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.policy.CompiledSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.policy.SourceVersion
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

/**
 * commitment v2 application service가 정책 row ID를 얻는 server-side 경계이다.
 *
 * `EffectiveSchedulingPolicy.id`는 canonical hash라 FK가 아니므로, production 구현은
 * [EffectiveSchedulingPolicyService]로 현재 정책을 영속화한 뒤 [SchedulingPolicyRepository]에서
 * 같은 hash의 snapshot row를 다시 읽어 command 입력에 사용할 양수 row ID를 결합한다.
 */
internal interface AppointmentCommitmentPolicySnapshotResolver {
    fun resolve(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
    ): CurrentPolicySnapshot

    /**
     * 과거 proposal에 고정된 snapshot ID를 현재 정책 재계산 없이 조회한다.
     *
     * @return 정확한 tenant·clinic 범위의 불변 snapshot 참조. 행이 없거나 범위가 다르면
     * 구현체는 fail-closed 오류를 반환해야 한다.
     */
    fun resolvePersisted(
        tenantGroupId: Long,
        clinicId: Long,
        snapshotId: Long,
    ): PersistedPolicySnapshotReference
}

/**
 * 이미 발행된 proposal의 동의 검증에 필요한 최소 불변 정책 참조이다.
 *
 * @property id proposal row에 고정된 양수 snapshot 식별자.
 * @property snapshotHash 영속 snapshot 전체 계약의 lowercase SHA-256.
 * @property payload proposal을 만들 때 적용한 완전한 컴파일 정책. 현재 활성 정책으로
 * 다시 계산하지 않고 승인·동의 조건을 동일하게 재검증하는 데 사용한다.
 */
internal data class PersistedPolicySnapshotReference(
    val id: Long,
    val snapshotHash: String,
    val tenantGeneration: Long,
    val clinicGeneration: Long,
    val sourceVersions: Map<SchedulingPolicyKind, SourceVersion>,
    val payload: CompiledSchedulingPolicy,
)

/**
 * 유효 정책 서비스와 snapshot repository를 결합하는 production 정책 resolver이다.
 */
internal class EffectiveAppointmentCommitmentPolicySnapshotResolver(
    private val database: Database,
    private val effectiveSchedulingPolicyService: EffectiveSchedulingPolicyService,
    private val schedulingPolicyRepository: SchedulingPolicyRepository,
) : AppointmentCommitmentPolicySnapshotResolver {
    override fun resolve(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
    ): CurrentPolicySnapshot {
        val policy = effectiveSchedulingPolicyService.getEffective(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            decisionAt = decisionAt,
            serviceAt = serviceAt,
        )
        val snapshot =
            transaction(database) {
                schedulingPolicyRepository.findSnapshot(tenantGroupId, clinicId, policy.snapshotHash)
            } ?: throw AppointmentCommitmentApiException(
                AppointmentCommitmentApiError.INTERNAL_ERROR,
                "effective policy snapshot row is not readable",
            )
        return CurrentPolicySnapshot(snapshot.id, policy)
    }

    override fun resolvePersisted(
        tenantGroupId: Long,
        clinicId: Long,
        snapshotId: Long,
    ): PersistedPolicySnapshotReference {
        val snapshot =
            transaction(database) {
                schedulingPolicyRepository.findSnapshot(tenantGroupId, clinicId, snapshotId)
            } ?: throw AppointmentCommitmentApiException(
                AppointmentCommitmentApiError.INTERNAL_ERROR,
                "persisted policy snapshot row is not readable in appointment scope",
            )
        return PersistedPolicySnapshotReference(
            id = snapshot.id,
            snapshotHash = snapshot.snapshotHash,
            tenantGeneration = snapshot.tenantGeneration,
            clinicGeneration = snapshot.clinicGeneration,
            sourceVersions = SNAPSHOT_MAPPER.readValue(snapshot.sourceVersionsJson, SOURCE_VERSIONS_TYPE),
            payload = SNAPSHOT_MAPPER.readValue(snapshot.payloadJson, CompiledSchedulingPolicy::class.java),
        )
    }

    private companion object {
        val SNAPSHOT_MAPPER: JsonMapper =
            JsonMapper.builder()
                .addModule(KotlinModule.Builder().build())
                .build()
        val SOURCE_VERSIONS_TYPE = object : TypeReference<Map<SchedulingPolicyKind, SourceVersion>>() {}
    }
}

/**
 * commitment v2 제안 계산에 필요한 고객 identity, 실제 inventory slot, projection mapping을
 * 제공하는 server-side adapter 경계이다.
 *
 * 이 interface는 request DTO의 시간·동의 참조만으로 만들 수 없는 값을 외부 권위 시스템이나
 * 병원 inventory에서 해석한다. production adapter가 없는 배포에서는 fail-closed 구현을
 * 사용해 고객 이름·연락처, 담당자, 장비, 공간을 임의로 합성하지 않는다.
 */
internal interface AppointmentCommitmentPlanningResolver {
    fun resolveIdentity(
        actor: ActorContext,
        access: ResolvedAppointmentPlanAccess,
    ): AppointmentVisitIdentityDraft

    fun resolveCandidateSlots(request: AppointmentCommitmentCandidateSlotRequest): List<ProposalCandidateSlot>

    fun resolveStoredProposalResourceRequests(
        clinicId: Long,
        proposal: AppointmentProposalRecord,
        items: List<AppointmentItemDraft>,
    ): List<ResourceAllocationRequest>

    fun resolveProjectionTarget(
        clinicId: Long,
        proposal: VisitProposalInput,
    ): ConfirmedAppointmentProjectionTarget
}

/**
 * 현재 production inventory/customer adapter가 연결되지 않은 환경의 보수적 기본값이다.
 */
internal class FailClosedAppointmentCommitmentPlanningResolver : AppointmentCommitmentPlanningResolver {
    override fun resolveIdentity(
        actor: ActorContext,
        access: ResolvedAppointmentPlanAccess,
    ): AppointmentVisitIdentityDraft =
        unavailable()

    override fun resolveCandidateSlots(request: AppointmentCommitmentCandidateSlotRequest): List<ProposalCandidateSlot> =
        unavailable()

    override fun resolveStoredProposalResourceRequests(
        clinicId: Long,
        proposal: AppointmentProposalRecord,
        items: List<AppointmentItemDraft>,
    ): List<ResourceAllocationRequest> =
        unavailable()

    override fun resolveProjectionTarget(
        clinicId: Long,
        proposal: VisitProposalInput,
    ): ConfirmedAppointmentProjectionTarget =
        unavailable()

    private fun unavailable(): Nothing =
        throw AppointmentCommitmentApiException(
            AppointmentCommitmentApiError.SCOPE_FORBIDDEN,
            "commitment planning resolver is not configured",
        )
}

/**
 * 실제 후보 slot resolver에 전달하는 검증 완료 proposal 계산 문맥이다.
 *
 * @property tenantGroupId Gateway actor와 구매 Plan에서 확인한 SaaS tenant ID이다.
 * @property clinicId Plan 소유권과 actor scope가 일치하는 병원 ID이다.
 * @property planRevisionId 구매 당시 고정된 실행 BOM revision ID이다.
 * @property preferredStartAt 고객이 요청한 UTC 시작 시각이다.
 * @property preferredEndAt 고객이 요청한 UTC 종료 시각이며 시작보다 뒤여야 한다.
 */
internal data class AppointmentCommitmentCandidateSlotRequest(
    val tenantGroupId: Long,
    val clinicId: Long,
    val planRevisionId: Long,
    val preferredStartAt: Instant,
    val preferredEndAt: Instant,
)
