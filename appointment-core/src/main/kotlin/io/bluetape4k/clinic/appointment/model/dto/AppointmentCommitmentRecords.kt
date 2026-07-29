package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.commitment.ConsentDecisionType
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 영속 commitment row의 불변 read model입니다.
 *
 * @property id 양수 commitment 식별자입니다.
 * @property appointmentId commitment와 1:1로 결합된 양수 방문 예약 식별자입니다.
 * @property status 진료 진행 상태와 독립적인 일정 합의 상태입니다.
 * @property origin 최초 제안 주체입니다.
 * @property confirmedProposalId 현재 활성 자원 점유와 원자적으로 결합된 proposal입니다.
 * 확정 전에는 `null`이며 새 제안 대기 중에도 기존 확정 값을 보존합니다.
 * @property effectivePolicySnapshotId 결정 당시 정책 스냅샷입니다.
 * @property version CAS에 사용하는 양수 낙관적 잠금 값입니다.
 */
data class AppointmentCommitmentRecord(
    val id: Long,
    val appointmentId: Long,
    val status: AppointmentCommitmentStatus,
    val origin: AppointmentOrigin,
    val confirmedProposalId: Long?,
    val effectivePolicySnapshotId: Long,
    val version: Long,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 수정 불가능하게 append된 예약 제안 row입니다.
 *
 * @property id 양수 proposal 식별자입니다.
 * @property commitmentId 소유 commitment 식별자입니다.
 * @property revision commitment 안에서 단조 증가하고 중복되지 않는 양수 revision입니다.
 * @property proposalHash 시간, 항목, 자원, 정책을 모두 포함한 canonical hash입니다.
 * 동의는 이 값과 proposal ID/revision에 정확히 결합돼야 합니다.
 * @property expiredAt 만료 command가 기록한 UTC 시각입니다. `null`이면 아직 만료가
 * 기록되지 않았다는 뜻이며 [expiresAt]이 미래임을 별도로 보장하지 않습니다.
 */
data class AppointmentProposalRecord(
    val id: Long,
    val commitmentId: Long,
    val revision: Long,
    val proposedStartAt: Instant,
    val proposedEndAt: Instant,
    val expiresAt: Instant,
    val expiredAt: Instant?,
    val representativeTreatmentName: String,
    val proposalHash: String,
    val policySnapshotId: Long,
    val supersedesProposalId: Long?,
    val createdByActor: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 자원 점유 요청과 capacity 정책을 결합한 repository 입력입니다.
 *
 * @property allocation 점유 대상과 시간 구간입니다.
 * @property maximumCapacity `CAPACITY_BUCKET`의 같은 bucket에서 허용하는 양수 합계입니다.
 * 전담·공유 자원에서는 1이어야 합니다.
 */
class ResourceAllocationRequest(
    val allocation: ResourceAllocationDraft,
    maximumCapacity: Int,
) : Serializable {
    val maximumCapacity = maximumCapacity.requirePositiveNumber("maximumCapacity")

    init {
        require(allocation.capacityUnits <= this.maximumCapacity) {
            "allocation capacityUnits must not exceed maximumCapacity"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 영속 자원 점유 read model입니다.
 *
 * @property status [ResourceAllocationStatus.ACTIVE]만 충돌·capacity 합계에 포함됩니다.
 * 교체 성공 후 이전 proposal의 row는 삭제하지 않고 `RELEASED`로 남깁니다.
 * @property maximumCapacity 이 allocation을 만든 구매·정책 snapshot의 양수 상한입니다.
 */
data class ResourceAllocationRecord(
    val id: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val proposalId: Long,
    val allocation: ResourceAllocationDraft,
    val maximumCapacity: Int,
    val status: ResourceAllocationStatus,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 자원 점유 수명주기입니다.
 */
enum class ResourceAllocationStatus {
    /** 현재 확정 proposal을 위해 충돌·capacity 계산에 포함되는 점유입니다. */
    ACTIVE,

    /** proposal 교체 또는 취소로 더 이상 capacity를 사용하지 않는 감사 row입니다. */
    RELEASED,
}

/**
 * 병원이 실제로 점유할 수 있는 진료 공간입니다.
 *
 * @property id 영속화 전 `null`, 저장 후 양수 식별자입니다.
 * @property tenantGroupId SaaS 권한 경계입니다.
 * @property clinicId 공간을 소유한 병원 경계입니다.
 * @property spaceCode 병원 안에서 중복되지 않는 안정적인 실제 공간 코드입니다.
 * @property displayName 운영자 표시명이며 식별 키가 아닙니다.
 * @property capabilities 이 공간에서 수행 가능한 진료·수술·회복 capability입니다.
 * @property nominalCapacity 정상 운영 시 수용 가능한 양수 단위입니다.
 * @property bucketMinutes capacity를 직렬화할 양수 분 단위입니다.
 * @property active 새 allocation에 사용할 수 있는지 나타냅니다.
 */
data class TreatmentSpaceRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val clinicId: Long,
    val spaceCode: String,
    val displayName: String,
    val capabilities: List<String>,
    val nominalCapacity: Int,
    val bucketMinutes: Int,
    val active: Boolean,
) : Serializable {
    init {
        tenantGroupId.requirePositiveNumber("tenantGroupId")
        clinicId.requirePositiveNumber("clinicId")
        spaceCode.requireNotBlank("spaceCode")
        displayName.requireNotBlank("displayName")
        nominalCapacity.requirePositiveNumber("nominalCapacity")
        bucketMinutes.requirePositiveNumber("bucketMinutes")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * idempotency key 선점 결과입니다.
 */
enum class CommandClaimResult {
    /** 이 actor scope에서 command를 처음 선점했습니다. */
    ACQUIRED,

    /** 같은 key와 같은 command hash의 안전한 replay입니다. */
    REPLAY,
}

/**
 * commitment v2 방문 identity를 먼저 생성하기 위한 최소 개인정보 입력입니다.
 *
 * 확정 전에는 담당 의사·진료 유형·일정 projection을 알 수 없으므로 이 값만
 * `scheduling_appointments`에 저장합니다. 인증 actor나 tenant/clinic scope를 포함하지
 * 않으며 application service가 별도 권한 경계에서 전달해야 합니다.
 *
 * @property patientName 예약 운영에 필요한 고객 표시명입니다.
 * @property patientPhone 선택적인 고객 연락처입니다.
 * @property patientExternalId 고객 서비스가 발급한 선택적 외부 식별자입니다.
 * @property patientReferenceFingerprint 구매 Plan과 같은 환자임을 원문 복호화 없이
 * 검증하는 필수 비가역 참조입니다. commitment v2 방문 row에 보존하며 legacy 예약에는
 * 존재하지 않을 수 있습니다.
 */
class AppointmentVisitIdentityDraft(
    patientName: String,
    patientPhone: String?,
    patientExternalId: String?,
    patientReferenceFingerprint: String,
) : Serializable {
    val patientName = patientName.requireNotBlank("patientName")
    val patientPhone = patientPhone?.requireNotBlank("patientPhone")
    val patientExternalId = patientExternalId?.requireNotBlank("patientExternalId")
    val patientReferenceFingerprint =
        patientReferenceFingerprint.requireNotBlank("patientReferenceFingerprint")

    init {
        require(this.patientReferenceFingerprint.length <= 128) {
            "patientReferenceFingerprint must not exceed 128 characters"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 확정 proposal을 기존 예약 조회 표면에 투영할 값입니다.
 *
 * @property doctorId 확정된 담당자의 양수 영속 식별자입니다.
 * @property treatmentTypeId 대표 진료 유형의 양수 영속 식별자입니다.
 * @property appointmentDate 병원 표시 timezone으로 환산한 방문 일자입니다.
 * @property startTime 병원 표시 timezone으로 환산한 방문 시작 시각입니다.
 * @property endTime [startTime]보다 뒤인 방문 종료 시각입니다.
 */
class ConfirmedAppointmentProjection(
    doctorId: Long,
    treatmentTypeId: Long,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
) : Serializable {
    val doctorId = doctorId.requirePositiveNumber("doctorId")
    val treatmentTypeId = treatmentTypeId.requirePositiveNumber("treatmentTypeId")

    init {
        require(startTime < endTime) { "startTime must be before endTime" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * proposal subject에 대한 최신 append-only 고객 결정 read model입니다.
 *
 * @property proposalId 동의 payload가 가리키는 proposal 식별자입니다.
 * @property proposalRevision 동의 payload에 고정된 proposal revision입니다.
 * @property proposalHash 동의 payload에 고정된 canonical hash입니다.
 * @property decision 고객의 수락 또는 거부 결정입니다.
 * @property evidenceType 정책이 확인한 증빙 종류이며 기존 동의는 `null`일 수 있습니다.
 * @property termsHash 고객이 동의한 약관 원문의 canonical SHA-256이며 원문은 저장하지 않습니다.
 */
class ProposalConsentDecisionRecord(
    proposalId: Long,
    proposalRevision: Long,
    proposalHash: String,
    val decision: ConsentDecisionType,
    evidenceType: String?,
    termsHash: String?,
) : Serializable {
    val proposalId = proposalId.requirePositiveNumber("proposalId")
    val proposalRevision = proposalRevision.requirePositiveNumber("proposalRevision")
    val proposalHash = proposalHash.requireNotBlank("proposalHash")
    val evidenceType = evidenceType?.requireNotBlank("evidenceType")
    val termsHash = termsHash?.requireNotBlank("termsHash")

    init {
        require(this.termsHash == null || SHA256_REGEX.matches(this.termsHash)) {
            "termsHash must be a lowercase SHA-256 value"
        }
    }

    companion object {
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
        private const val serialVersionUID = 1L
    }
}

/**
 * 멱등 command가 transaction 마지막에 기록한 durable 결과 snapshot입니다.
 *
 * @property resultType application service가 해석하는 안정적인 결과 분류입니다.
 * @property resultId 같은 transaction에서 생성·변경된 양수 결과 식별자입니다.
 * @property commitment command 완료 시점의 commitment snapshot입니다.
 * @property proposal command가 반환한 불변 proposal snapshot입니다.
 * @property responseHash caller 응답 본문을 재검증하기 위한 lowercase SHA-256입니다.
 */
class AppointmentCommandResultRecord(
    resultType: String,
    resultId: Long,
    val commitment: AppointmentCommitmentRecord,
    val proposal: AppointmentProposalRecord,
    responseHash: String,
) : Serializable {
    val resultType = resultType.requireNotBlank("resultType")
    val resultId = resultId.requirePositiveNumber("resultId")
    val responseHash = responseHash.requireNotBlank("responseHash")

    init {
        require(proposal.id == this.resultId) { "resultId must match proposal id" }
        require(proposal.commitmentId == commitment.id) { "proposal must belong to commitment" }
        require(SHA256_REGEX.matches(this.responseHash)) {
            "responseHash must be a lowercase SHA-256 value"
        }
    }

    companion object {
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
        private const val serialVersionUID = 1L
    }
}
