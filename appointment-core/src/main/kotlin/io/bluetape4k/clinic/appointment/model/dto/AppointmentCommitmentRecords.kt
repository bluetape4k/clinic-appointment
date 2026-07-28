package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationDraft
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.time.Instant

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
 */
data class AppointmentProposalRecord(
    val id: Long,
    val commitmentId: Long,
    val revision: Long,
    val proposedStartAt: Instant,
    val proposedEndAt: Instant,
    val expiresAt: Instant,
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
data class ResourceAllocationRequest(
    val allocation: ResourceAllocationDraft,
    val maximumCapacity: Int,
) : Serializable {

    init {
        maximumCapacity.requirePositiveNumber("maximumCapacity")
        require(allocation.capacityUnits <= maximumCapacity) {
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
 */
data class ResourceAllocationRecord(
    val id: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val proposalId: Long,
    val allocation: ResourceAllocationDraft,
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
