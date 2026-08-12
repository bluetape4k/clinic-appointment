package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.clinic.appointment.commitment.CancellationReasonRegistry
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentItemDraft
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentProposalDraft
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecisionType
import io.bluetape4k.clinic.appointment.model.dto.AppointmentCommitmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentProposalRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentVisitIdentityDraft
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationRequest
import io.bluetape4k.clinic.appointment.model.policy.AdminBookingMode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionStamp
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * API command에서 사용하는 commitment v2 방문 identity 이름입니다.
 *
 * 실제 영속 계약은 core의 [AppointmentVisitIdentityDraft]가 소유하며 controller가
 * 인증 actor 정보와 이 값을 혼합하지 않도록 별도 이름으로 노출합니다.
 */
internal typealias AppointmentVisitIdentity = AppointmentVisitIdentityDraft

/**
 * Gateway 인증을 마친 adapter가 commitment command에 전달하는 감사·멱등 경계입니다.
 *
 * 이 값은 request body에서 직접 역직렬화하면 안 됩니다. Task 7의 controller가 Gateway
 * principal과 header를 검증한 뒤 생성해야 하며 application service는 raw token이나
 * 고객 개인정보를 보관·로그하지 않습니다.
 *
 * @property tenantGroupId SaaS 데이터 격리의 양수 tenant 식별자입니다.
 * @property clinicId command가 변경할 양수 병원 식별자입니다.
 * @property actorScopeHash 원본 actor claim 대신 멱등 범위를 고정하는 비가역 hash입니다.
 * @property actorAuditRef 제한된 감사용 비민감 actor 참조입니다.
 * @property idempotencyKeyHash raw `Idempotency-Key`를 adapter가 보호된 service salt로
 * HMAC-SHA-256 처리한 소문자 64자 값입니다. 원문 key를 저장하거나 로그하면 안 됩니다.
 * @property commandHash request와 expected version을 포함한 canonical SHA-256입니다.
 * @property correlationId 요청 trace를 잇는 제한 길이 식별자이며 causation event ID가 아닙니다.
 */
internal class CommitmentCommandContext(
    tenantGroupId: Long,
    clinicId: Long,
    actorScopeHash: String,
    actorAuditRef: String,
    actorRole: String = "UNKNOWN",
    idempotencyKeyHash: String,
    commandHash: String,
    correlationId: String,
) : Serializable {
    val tenantGroupId = tenantGroupId.requirePositiveNumber("tenantGroupId")
    val clinicId = clinicId.requirePositiveNumber("clinicId")
    val actorScopeHash = actorScopeHash.requireNotBlank("actorScopeHash")
    val actorAuditRef = actorAuditRef.requireNotBlank("actorAuditRef")
    val actorRole = actorRole.requireNotBlank("actorRole")
    val idempotencyKeyHash = idempotencyKeyHash.requireNotBlank("idempotencyKeyHash")
    val commandHash = commandHash.requireNotBlank("commandHash")
    val correlationId = correlationId.requireNotBlank("correlationId")

    init {
        require(this.actorScopeHash.matches(SHA256)) {
            "actorScopeHash must be a lowercase SHA-256 value"
        }
        require(this.actorAuditRef.length <= 128) { "actorAuditRef must not exceed 128 characters" }
        require(this.actorRole.matches(ACTOR_ROLE)) {
            "actorRole must be a stable actor type"
        }
        require(this.idempotencyKeyHash.matches(SHA256)) {
            "idempotencyKeyHash must be a lowercase SHA-256 value"
        }
        require(this.commandHash.matches(SHA256)) {
            "commandHash must be a lowercase SHA-256 value"
        }
        require(CORRELATION_ID.matches(this.correlationId)) {
            "correlationId must contain 1..128 safe ASCII characters"
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val ACTOR_ROLE = Regex("[A-Z_]{1,16}")
        private val CORRELATION_ID = Regex("[A-Za-z0-9._:/-]{1,128}")
        private const val serialVersionUID = 1L
    }
}

/**
 * 아직 appointment ID가 배정되지 않은 방문 proposal 입력입니다.
 *
 * @property revision commitment 안에서 append되는 양수 revision입니다.
 * @property startsAt 방문 점유 UTC 시작 시각입니다.
 * @property endsAt [startsAt]보다 뒤인 UTC 종료 시각입니다.
 * @property items 한 번 방문에서 수행을 시도할 순서 있는 세부 진료 목록입니다.
 * @property resourceRequests 확정 transaction에서 모두 점유해야 하는 실제 자원과 상한입니다.
 * @property policySnapshotId 제안 계산에 사용한 불변 정책 snapshot 식별자입니다.
 * @property supersedesProposalId 변경 제안이 대체하려는 기존 proposal이며 최초 제안은 `null`입니다.
 */
internal class VisitProposalInput(
    revision: Long,
    val startsAt: Instant,
    val endsAt: Instant,
    items: List<AppointmentItemDraft>,
    resourceRequests: List<ResourceAllocationRequest>,
    policySnapshotId: Long,
    supersedesProposalId: Long?,
) : Serializable {
    val revision = revision.requirePositiveNumber("revision")
    val items = items.toList()
    val resourceRequests = resourceRequests.toList()
    val policySnapshotId = policySnapshotId.requirePositiveNumber("policySnapshotId")
    val supersedesProposalId =
        supersedesProposalId?.requirePositiveNumber("supersedesProposalId")

    init {
        require(startsAt < endsAt) { "startsAt must be before endsAt" }
        require(this.items.isNotEmpty()) { "proposal items must not be empty" }
        require(this.resourceRequests.isNotEmpty()) { "resource requests must not be empty" }
        require(
            this.resourceRequests.all {
                it.allocation.startsAt >= startsAt && it.allocation.endsAt <= endsAt
            },
        ) {
            "resource allocation must be inside the proposal interval"
        }
    }

    /** 영속 appointment identity가 정해진 뒤 canonical proposal 초안으로 결합합니다. */
    fun toDraft(
        appointmentId: Long,
        reliabilityStamp: BookingReliabilityDecisionStamp? = null,
    ): AppointmentProposalDraft =
        AppointmentProposalDraft(
            appointmentId = appointmentId.requirePositiveNumber("appointmentId"),
            revision = revision,
            startsAt = startsAt,
            endsAt = endsAt,
            items = items,
            allocations = resourceRequests.map(ResourceAllocationRequest::allocation),
            policySnapshotId = policySnapshotId,
            supersedesProposalId = supersedesProposalId,
            bookingReliabilityStamp = reliabilityStamp,
        )

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 원본 동의 서비스가 검증한 증빙 metadata입니다.
 *
 * proposal ID/revision/hash는 caller가 제공하지 않습니다. 최초 생성 전 검증은 DB 생성
 * ID를 제외한 안정적인 proposal hash에 결합되고, command service는 영속 proposal이
 * 생긴 뒤 실제 ID/revision과 같은 hash로
 * [io.bluetape4k.clinic.appointment.model.commitment.ProposalConsentSubject]를 생성합니다.
 * 전역 unique evidence와 proposal 소유권 검증이 다른 proposal 재사용을 차단합니다.
 */
internal class ProposalConsentEvidence(
    val decision: ConsentDecisionType,
    evidenceType: String,
    evidenceAuthority: String,
    evidenceId: String,
    evidenceHash: String,
    val decidedAt: Instant,
    termsHash: String?,
    actorRef: String,
) : Serializable {
    val evidenceType = evidenceType.requireNotBlank("evidenceType")
    val evidenceAuthority = evidenceAuthority.requireNotBlank("evidenceAuthority")
    val evidenceId = evidenceId.requireNotBlank("evidenceId")
    val evidenceHash = evidenceHash.requireNotBlank("evidenceHash")
    val termsHash = termsHash?.requireNotBlank("termsHash")
    val actorRef = actorRef.requireNotBlank("actorRef")

    init {
        require(this.evidenceType.length <= 64) { "evidenceType must not exceed 64 characters" }
        require(this.evidenceAuthority.length <= 128) {
            "evidenceAuthority must not exceed 128 characters"
        }
        require(this.evidenceId.length <= 128) { "evidenceId must not exceed 128 characters" }
        require(this.evidenceHash.matches(SHA256)) {
            "evidenceHash must be a lowercase SHA-256 value"
        }
        require(this.termsHash == null || this.termsHash.matches(SHA256)) {
            "termsHash must be a lowercase SHA-256 value"
        }
        require(this.actorRef.length <= 128) { "actorRef must not exceed 128 characters" }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val serialVersionUID = 1L
    }
}

/**
 * 관리자의 직접 확정 권한을 계산한 유효 정책 snapshot입니다.
 *
 * request body가 아니라 예약 정책 서비스가 현재 tenant·clinic 유효 정책에서 계산해
 * command에 전달해야 합니다. [policySnapshotId]는 proposal의 `policySnapshotId`와
 * 같아야 하며 [policySnapshotHash]는 감사 가능한 canonical 정책 hash입니다.
 *
 * @property adminBookingMode 관리자 예약을 바로 확정할 수 있는 정책 방식입니다.
 * @property allowedEvidenceTypes 직접 확정에 허용되는 닫힌 동의 증빙 유형 집합입니다.
 * @property maximumEvidenceAge [ProposalConsentEvidence.decidedAt] 이후 허용되는 최대 기간입니다.
 * @property termsHashRequired 정확한 약관 hash가 동의 증빙에 반드시 있어야 하는지 나타냅니다.
 * @property requiredTermsHash 직접 확정 증빙이 정확히 동의해야 하는 약관 SHA-256입니다.
 * [termsHashRequired]가 `true`이면 반드시 존재해야 하며 단순히 임의 hash가 있다는 사실만
 * 확인하지 않습니다.
 */
internal class DirectConfirmationPolicyDecision(
    policySnapshotId: Long,
    policySnapshotHash: String,
    val adminBookingMode: AdminBookingMode,
    allowedEvidenceTypes: Set<String>,
    val maximumEvidenceAge: Duration,
    val termsHashRequired: Boolean,
    requiredTermsHash: String?,
) : Serializable {
    val policySnapshotId = policySnapshotId.requirePositiveNumber("policySnapshotId")
    val policySnapshotHash = policySnapshotHash.requireNotBlank("policySnapshotHash")
    val allowedEvidenceTypes =
        allowedEvidenceTypes.mapTo(linkedSetOf()) {
            it.requireNotBlank("allowedEvidenceType")
        }
    val requiredTermsHash = requiredTermsHash?.requireNotBlank("requiredTermsHash")

    init {
        require(this.policySnapshotHash.matches(SHA256)) {
            "policySnapshotHash must be a lowercase SHA-256 value"
        }
        require(this.allowedEvidenceTypes.isNotEmpty()) {
            "allowedEvidenceTypes must not be empty"
        }
        require(this.allowedEvidenceTypes.all { it.length <= 64 }) {
            "allowedEvidenceType must not exceed 64 characters"
        }
        require(!maximumEvidenceAge.isZero && !maximumEvidenceAge.isNegative) {
            "maximumEvidenceAge must be positive"
        }
        require(!termsHashRequired || this.requiredTermsHash != null) {
            "requiredTermsHash must exist when termsHashRequired is true"
        }
        require(this.requiredTermsHash == null || this.requiredTermsHash.matches(SHA256)) {
            "requiredTermsHash must be a lowercase SHA-256 value"
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val serialVersionUID = 1L
    }
}

/**
 * 확정 proposal을 기존 예약 조회 필드로 투영할 신뢰된 자원 선택입니다.
 *
 * 날짜와 시간은 caller가 제공하지 않습니다. command service가 proposal UTC 구간을
 * 병원 IANA timezone으로 환산해 생성합니다. [practitionerResourceId]는 proposal이
 * 실제로 점유하는 `PRACTITIONER` 자원과 같아야 하고 [doctorId]와의 매핑은 병원
 * 자원 inventory가 검증한 값이어야 합니다.
 */
internal class ConfirmedAppointmentProjectionTarget(
    doctorId: Long,
    treatmentTypeId: Long,
    practitionerResourceId: String,
) : Serializable {
    val doctorId = doctorId.requirePositiveNumber("doctorId")
    val treatmentTypeId = treatmentTypeId.requirePositiveNumber("treatmentTypeId")
    val practitionerResourceId =
        practitionerResourceId.requireNotBlank("practitionerResourceId")

    init {
        require(this.practitionerResourceId.length <= 160) {
            "practitionerResourceId must not exceed 160 characters"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 고객이 선택한 최초 일정을 가예약으로 등록하는 command입니다. */
internal class CustomerAppointmentRequestCommand(
    val context: CommitmentCommandContext,
    val identity: AppointmentVisitIdentity,
    val proposal: VisitProposalInput,
    val expiresAt: Instant,
    representativeTreatmentName: String,
    val consent: ProposalConsentEvidence,
    val holdResources: Boolean = false,
) : Serializable {
    val representativeTreatmentName =
        representativeTreatmentName.requireNotBlank("representativeTreatmentName")

    init {
        require(this.representativeTreatmentName.length <= 256) {
            "representativeTreatmentName must not exceed 256 characters"
        }
        require(expiresAt <= proposal.startsAt) { "expiresAt must not be after proposal start" }
        require(proposal.supersedesProposalId == null) { "initial proposal must not supersede another proposal" }
        require(consent.decision == ConsentDecisionType.ACCEPTED) {
            "customer request must include accepted consent"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 고객 요청 proposal을 병원 승인을 거쳐 확정하는 command입니다.
 *
 * @property context Gateway actor scope와 멱등성 digest를 포함한 command 문맥.
 * @property appointmentId 확정할 commitment가 소유한 방문 예약 식별자.
 * @property proposalId 확정 대상인 영속 proposal 식별자.
 * @property expectedVersion `If-Match`에서 해석한 commitment 낙관적 version.
 * @property proposal 서버가 영속 proposal과 Plan snapshot으로 재구성한 정확한 일정 입력.
 * @property expectedProposalHash 영속 proposal 내용에 결합된 canonical SHA-256.
 * @property projectionTarget 확정 후 legacy 예약 projection에 반영할 서버 해석 결과.
 * @property consent 관리자 직접 확정 요청에서 검증한 고객 동의 증빙. 일반 승인에서는
 * 고객 최초 요청에 이미 저장된 동의를 사용하므로 `null`이다.
 * @property policyDecision 관리자 직접 확정 시 현재 유효 정책에서 계산한 서버 측 판단이다.
 * command service가 영속 proposal의 정책 snapshot과 같은 transaction에서 다시 검증한다.
 * 일반 병원 승인에서는 고객 요청 당시 동의만 확인하므로 `null`이다.
 */
internal class ConfirmAppointmentProposalCommand(
    val context: CommitmentCommandContext,
    appointmentId: Long,
    proposalId: Long,
    expectedVersion: Long,
    val proposal: VisitProposalInput,
    expectedProposalHash: String,
    val projectionTarget: ConfirmedAppointmentProjectionTarget,
    val consent: ProposalConsentEvidence? = null,
    val policyDecision: DirectConfirmationPolicyDecision? = null,
) : Serializable {
    val appointmentId = appointmentId.requirePositiveNumber("appointmentId")
    val proposalId = proposalId.requirePositiveNumber("proposalId")
    val expectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
    val expectedProposalHash = expectedProposalHash.requireNotBlank("expectedProposalHash")

    init {
        require(this.expectedProposalHash.matches(SHA256)) {
            "expectedProposalHash must be a lowercase SHA-256 value"
        }
        require(consent == null || consent.decision == ConsentDecisionType.ACCEPTED) {
            "direct confirmation consent must be accepted"
        }
        require((consent == null) == (policyDecision == null)) {
            "direct confirmation consent and policy decision must be provided together"
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val serialVersionUID = 1L
    }
}

/** 병원 제안과 고객 동의가 이미 준비된 신규 방문을 한 번에 확정하는 command입니다. */
internal class DirectAppointmentConfirmationCommand(
    val context: CommitmentCommandContext,
    val identity: AppointmentVisitIdentity,
    val proposal: VisitProposalInput,
    val expiresAt: Instant,
    representativeTreatmentName: String,
    val projectionTarget: ConfirmedAppointmentProjectionTarget,
    val policyDecision: DirectConfirmationPolicyDecision,
    val consent: ProposalConsentEvidence,
) : Serializable {
    val representativeTreatmentName =
        representativeTreatmentName.requireNotBlank("representativeTreatmentName")

    init {
        require(this.representativeTreatmentName.length <= 256) {
            "representativeTreatmentName must not exceed 256 characters"
        }
        require(expiresAt <= proposal.startsAt) { "expiresAt must not be after proposal start" }
        require(proposal.supersedesProposalId == null) { "direct initial proposal must not supersede another proposal" }
        require(consent.decision == ConsentDecisionType.ACCEPTED) {
            "direct confirmation must include accepted customer consent"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 기존 확정을 유지한 채 새 변경 proposal revision만 append하는 command입니다. */
internal class ChangeAppointmentProposalCommand(
    val context: CommitmentCommandContext,
    appointmentId: Long,
    expectedVersion: Long,
    val proposal: VisitProposalInput,
    val expiresAt: Instant,
    representativeTreatmentName: String,
) : Serializable {
    val appointmentId = appointmentId.requirePositiveNumber("appointmentId")
    val expectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
    val representativeTreatmentName =
        representativeTreatmentName.requireNotBlank("representativeTreatmentName")

    init {
        require(this.representativeTreatmentName.length <= 256) {
            "representativeTreatmentName must not exceed 256 characters"
        }
        require(expiresAt <= proposal.startsAt) { "expiresAt must not be after proposal start" }
        require(proposal.supersedesProposalId != null) {
            "change proposal must identify the confirmed proposal it supersedes"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 고객 수락과 새 자원 점유를 원자적으로 확정하는 변경 command입니다. */
internal class AcceptAppointmentProposalCommand(
    val context: CommitmentCommandContext,
    appointmentId: Long,
    proposalId: Long,
    expectedVersion: Long,
    val proposal: VisitProposalInput,
    expectedProposalHash: String,
    val projectionTarget: ConfirmedAppointmentProjectionTarget,
    val consent: ProposalConsentEvidence,
) : Serializable {
    val appointmentId = appointmentId.requirePositiveNumber("appointmentId")
    val proposalId = proposalId.requirePositiveNumber("proposalId")
    val expectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
    val expectedProposalHash = expectedProposalHash.requireNotBlank("expectedProposalHash")

    init {
        require(this.expectedProposalHash.matches(SHA256)) {
            "expectedProposalHash must be a lowercase SHA-256 value"
        }
        require(consent.decision == ConsentDecisionType.ACCEPTED) {
            "accept command must include accepted consent"
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val serialVersionUID = 1L
    }
}

/** 고객 거부 이력을 append하고 기존 확정 예약은 유지하는 command입니다. */
internal class DeclineAppointmentProposalCommand(
    val context: CommitmentCommandContext,
    appointmentId: Long,
    proposalId: Long,
    expectedVersion: Long,
    expectedProposalHash: String,
    val consent: ProposalConsentEvidence,
) : Serializable {
    val appointmentId = appointmentId.requirePositiveNumber("appointmentId")
    val proposalId = proposalId.requirePositiveNumber("proposalId")
    val expectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
    val expectedProposalHash = expectedProposalHash.requireNotBlank("expectedProposalHash")

    init {
        require(this.expectedProposalHash.matches(SHA256)) {
            "expectedProposalHash must be a lowercase SHA-256 value"
        }
        require(consent.decision == ConsentDecisionType.DECLINED) {
            "decline command must include declined consent"
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val serialVersionUID = 1L
    }
}

/** proposal 유효시간 경과를 기록하는 command입니다. */
internal class ExpireAppointmentProposalCommand(
    val context: CommitmentCommandContext,
    appointmentId: Long,
    proposalId: Long,
    expectedVersion: Long,
    expectedProposalHash: String,
) : Serializable {
    val appointmentId = appointmentId.requirePositiveNumber("appointmentId")
    val proposalId = proposalId.requirePositiveNumber("proposalId")
    val expectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
    val expectedProposalHash = expectedProposalHash.requireNotBlank("expectedProposalHash")

    init {
        require(this.expectedProposalHash.matches(SHA256)) {
            "expectedProposalHash must be a lowercase SHA-256 value"
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val serialVersionUID = 1L
    }
}

/**
 * 현재 예약 commitment를 취소하고 보유 중인 자원 점유를 해제하는 command입니다.
 *
 * @property appointmentId 취소할 commitment가 소유한 방문 식별자입니다.
 * @property proposalId 취소 판단과 감사 event에 결합할 현재 proposal 식별자입니다.
 * @property expectedVersion `If-Match`에서 파생한 현재 commitment version입니다.
 * @property expectedProposalHash caller가 조회한 현재 proposal의 canonical hash입니다.
 * @property reasonCode 자유 텍스트를 대신하는 등록된 대문자 업무 사유 code입니다.
 * @property reasonDetail 관리자·staff가 환자에게 전달할 제한된 취소 설명입니다.
 */
internal class CancelAppointmentCommand(
    val context: CommitmentCommandContext,
    appointmentId: Long,
    proposalId: Long,
    expectedVersion: Long,
    expectedProposalHash: String,
    reasonCode: String,
    val reasonDetail: String? = null,
) : Serializable {
    val appointmentId = appointmentId.requirePositiveNumber("appointmentId")
    val proposalId = proposalId.requirePositiveNumber("proposalId")
    val expectedVersion = expectedVersion.requirePositiveNumber("expectedVersion")
    val expectedProposalHash = expectedProposalHash.requireNotBlank("expectedProposalHash")
    val reasonCode = reasonCode.requireNotBlank("reasonCode")

    init {
        require(this.expectedProposalHash.matches(SHA256)) {
            "expectedProposalHash must be a lowercase SHA-256 value"
        }
        CancellationReasonRegistry.requireCode(this.reasonCode)
        CancellationReasonRegistry.requireDetail(this.reasonDetail)
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val serialVersionUID = 1L
    }
}

/**
 * commitment mutation의 durable 결과입니다.
 *
 * @property commitment command 완료 뒤의 commitment snapshot입니다.
 * @property proposal command가 대상으로 삼은 불변 proposal입니다.
 * @property idempotentReplay 새 side effect 없이 기존 durable 결과를 재생했는지 나타냅니다.
 */
internal data class AppointmentCommitmentCommandResult(
    val commitment: AppointmentCommitmentRecord,
    val proposal: AppointmentProposalRecord,
    val idempotentReplay: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** commitment command가 caller에게 노출하는 안정적인 업무 오류입니다. */
internal enum class AppointmentCommitmentCommandError {
    SCOPE_MISMATCH,
    COMMITMENT_NOT_FOUND,
    PROPOSAL_NOT_FOUND,
    PROPOSAL_NOT_CURRENT,
    PROPOSAL_EXPIRED,
    PROPOSAL_NOT_EXPIRED,
    PROPOSAL_ALREADY_EXPIRED,
    PROPOSAL_REVISION_CONFLICT,
    APPOINTMENT_ITEM_INVALID,
    CONSENT_REQUIRED,
    CONSENT_EVIDENCE_INVALID,
    CONSENT_EVIDENCE_REUSED,
    CUSTOMER_DECLINED,
    DIRECT_CONFIRM_NOT_ALLOWED,
    CONFIRMED_PROJECTION_INVALID,
    INVALID_TRANSITION,
    RESOURCE_CONFLICT,
    VERSION_CONFLICT,
    IDEMPOTENCY_KEY_REUSED,
    IDEMPOTENCY_RESULT_MISSING,
    RETRY_INTERRUPTED,
    RETRY_EXHAUSTED,
}

/**
 * HTTP status mapping 전의 안정적인 commitment application 오류입니다.
 *
 * Task 7 adapter는 [code]를 고정 API 오류로 변환하며 raw database exception이나
 * 증빙 payload를 외부에 노출하지 않습니다.
 */
internal class AppointmentCommitmentCommandException(
    val code: AppointmentCommitmentCommandError,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
