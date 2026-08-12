package io.bluetape4k.clinic.appointment.api.dto.commitment

import io.bluetape4k.clinic.appointment.api.service.PersistedPolicySnapshotReference
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import java.io.Serializable
import java.time.Instant

/**
 * 고객 가예약 생성 결과이다.
 *
 * @property appointmentId 이후 API path에서 사용하는 예약 식별자.
 * @property commitmentId v2 업무 상태를 소유하는 commitment 식별자.
 * @property proposalId 현재 제안된 불변 일정 식별자.
 * @property status 제안형 정책은 `PROPOSED`, 선점형 정책은 `HELD`이다.
 * @property version 다음 mutation의 `If-Match`에 사용할 aggregate version.
 * @property expiresAt 고객·병원이 proposal을 처리해야 하는 UTC 만료 시각.
 * @property policySnapshot proposal 계산에 고정된 정책 세대·원본 버전·hash이다.
 */
data class AppointmentProposalResponse(
    val appointmentId: Long,
    val commitmentId: Long,
    val proposalId: Long,
    val status: AppointmentCommitmentStatus,
    val version: Long,
    val expiresAt: Instant,
    val policySnapshot: AppointmentPolicySnapshotSummary,
    /** 구매 Plan snapshot에서 읽은 환자 표시용 상품명입니다. 없으면 `null`입니다. */
    val productName: String? = null,
    /** 이번 방문이 단일 회차로 식별될 때의 1부터 시작하는 회차입니다. */
    val sessionNumber: Int? = null,
    /** Plan revision에서 계산한 전체 회차 수입니다. */
    val totalSessions: Int? = null,
    /** tenant·clinic scope에서 읽은 병원 표시명입니다. */
    val clinicDisplayName: String? = null,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 확정·변경·거절 뒤 반환하는 commitment query projection이다.
 *
 * nullable legacy appointment column을 노출하지 않고 commitment와 proposal read model만
 * 사용한다.
 *
 * @property effectivePolicySnapshotId 현재 proposal 또는 확정 결정에 고정된 정책
 * snapshot ID이다. 새 proposal 수락 시 그 proposal의 snapshot으로 원자 교체된다.
 */
data class AppointmentCommitmentResponse(
    val appointmentId: Long,
    val commitmentId: Long,
    val status: AppointmentCommitmentStatus,
    val version: Long,
    val currentProposal: AppointmentProposalSummary,
    val confirmedProposalId: Long?,
    val effectivePolicySnapshotId: Long?,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * 외부에 공개 가능한 불변 proposal 요약이다.
 *
 * 내부 actor audit ref와 자원 allocation 세부사항은 노출하지 않는다. 정책 snapshot은
 * 고객·운영자가 이후 정책 변경과 무관하게 당시 결정을 감사할 수 있도록 공개한다.
 */
data class AppointmentProposalSummary(
    val proposalId: Long,
    val revision: Long,
    val startsAt: Instant,
    val endsAt: Instant,
    val expiresAt: Instant,
    val expired: Boolean,
    val representativeTreatmentName: String,
    val policySnapshot: AppointmentPolicySnapshotSummary,
    val productName: String? = null,
    val sessionNumber: Int? = null,
    val totalSessions: Int? = null,
    val clinicDisplayName: String? = null,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/**
 * proposal 또는 확정 결정에 고정된 불변 정책 참조이다.
 *
 * @property snapshotId 정책 snapshot row의 양수 식별자.
 * @property snapshotHash 세대·원본 버전·payload를 묶은 canonical SHA-256.
 * @property tenantGeneration snapshot 생성 시 검증한 tenant 정책 세대.
 * @property clinicGeneration snapshot 생성 시 검증한 clinic 재정의 세대.
 * @property sourceVersions 정책 종류별 실제 tenant/clinic definition 버전.
 */
data class AppointmentPolicySnapshotSummary(
    val snapshotId: Long,
    val snapshotHash: String,
    val tenantGeneration: Long,
    val clinicGeneration: Long,
    val sourceVersions: Map<String, AppointmentPolicySourceVersionSummary>,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

/** 하나의 정책 종류를 만든 정확한 tenant와 선택적 clinic definition 버전이다. */
data class AppointmentPolicySourceVersionSummary(
    val tenantVersion: Long,
    val clinicVersion: Long?,
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}

internal fun AppointmentCommitmentRecord.toProposalResponse(
    proposal: AppointmentProposalRecord,
    policySnapshot: PersistedPolicySnapshotReference,
    display: AppointmentCommitmentDisplay = AppointmentCommitmentDisplay.EMPTY,
): AppointmentProposalResponse =
    AppointmentProposalResponse(
        appointmentId = appointmentId,
        commitmentId = id,
        proposalId = proposal.id,
        status = status,
        version = version,
        expiresAt = proposal.expiresAt,
        policySnapshot = policySnapshot.toSummary(),
        productName = display.productName,
        sessionNumber = display.sessionNumber,
        totalSessions = display.totalSessions,
        clinicDisplayName = display.clinicDisplayName,
    )

internal fun AppointmentCommitmentRecord.toResponse(
    proposal: AppointmentProposalRecord,
    policySnapshot: PersistedPolicySnapshotReference,
    display: AppointmentCommitmentDisplay = AppointmentCommitmentDisplay.EMPTY,
): AppointmentCommitmentResponse =
    AppointmentCommitmentResponse(
        appointmentId = appointmentId,
        commitmentId = id,
        status = status,
        version = version,
        currentProposal = proposal.toSummary(policySnapshot, display),
        confirmedProposalId = confirmedProposalId,
        effectivePolicySnapshotId = effectivePolicySnapshotId,
    )

private fun AppointmentProposalRecord.toSummary(
    policySnapshot: PersistedPolicySnapshotReference,
    display: AppointmentCommitmentDisplay,
): AppointmentProposalSummary =
    AppointmentProposalSummary(
        proposalId = id,
        revision = revision,
        startsAt = proposedStartAt,
        endsAt = proposedEndAt,
        expiresAt = expiresAt,
        expired = expiredAt != null,
        representativeTreatmentName = representativeTreatmentName,
        policySnapshot = policySnapshot.toSummary(),
        productName = display.productName,
        sessionNumber = display.sessionNumber,
        totalSessions = display.totalSessions,
        clinicDisplayName = display.clinicDisplayName,
    )

/** commitment/proposal 응답에 공통으로 붙는 scope-검증된 표시 metadata입니다. */
internal data class AppointmentCommitmentDisplay(
    val productName: String? = null,
    val sessionNumber: Int? = null,
    val totalSessions: Int? = null,
    val clinicDisplayName: String? = null,
) {
    internal companion object {
        val EMPTY = AppointmentCommitmentDisplay()
    }
}

private fun PersistedPolicySnapshotReference.toSummary(): AppointmentPolicySnapshotSummary =
    AppointmentPolicySnapshotSummary(
        snapshotId = id,
        snapshotHash = snapshotHash,
        tenantGeneration = tenantGeneration,
        clinicGeneration = clinicGeneration,
        sourceVersions =
            sourceVersions
                .toSortedMap(compareBy { it.name })
                .mapKeys { it.key.name }
                .mapValues { (_, source) ->
                    AppointmentPolicySourceVersionSummary(
                        tenantVersion = source.tenantVersion,
                        clinicVersion = source.clinicVersion,
                    )
                },
    )
