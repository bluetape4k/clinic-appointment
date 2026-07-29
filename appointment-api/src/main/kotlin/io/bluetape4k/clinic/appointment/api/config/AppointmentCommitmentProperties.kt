package io.bluetape4k.clinic.appointment.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * commitment v2의 점진 배포와 동기 계획 계산 안전 상한을 고정합니다.
 *
 * 기본 [mode]는 [AppointmentCommitmentMode.OFF]이며 `WRITE`만 설정해도 쓰기가
 * 열리지 않습니다. 병원은 [clinicAllowlist]에도 포함되어야 합니다. 이 이중 조건은
 * 신규 유입만 차단하면서 이미 생성된 v2 row의 query/mutation 경로를 유지하는
 * rollback을 가능하게 합니다.
 *
 * [ceiling]은 platform 상한이므로 병원 또는 상품 설정이 완화할 수 없습니다.
 * 변경이 필요하면 비동기 planning 설계와 별도 검토를 거쳐야 합니다.
 *
 * @property mode 신규 commitment 계산·쓰기에 적용할 배포 단계입니다.
 * @property clinicAllowlist `WRITE`를 허용할 양수 clinic ID의 명시적 집합입니다.
 * @property proposalTtl 가예약 proposal이 고객 또는 관리자 승인을 기다릴 최대 시간입니다.
 * @property retry 자원 충돌·직렬화 실패의 제한된 재시도 설정입니다.
 * @property ceiling 동기 계획 계산이 절대 초과할 수 없는 platform 상한입니다.
 */
@ConfigurationProperties("appointment.commitment")
data class AppointmentCommitmentProperties(
    val mode: AppointmentCommitmentMode = AppointmentCommitmentMode.OFF,
    val clinicAllowlist: Set<Long> = emptySet(),
    val proposalTtl: Duration = Duration.ofMinutes(30),
    val retry: AppointmentCommitmentRetryProperties = AppointmentCommitmentRetryProperties(),
    val ceiling: AppointmentCommitmentCeilingProperties = AppointmentCommitmentCeilingProperties(),
) : Serializable {

    init {
        require(clinicAllowlist.all { it > 0L }) { "clinicAllowlist must contain only positive clinic IDs" }
        require(!proposalTtl.isZero && !proposalTtl.isNegative && proposalTtl <= MAX_PROPOSAL_TTL) {
            "proposalTtl must be greater than zero and at most $MAX_PROPOSAL_TTL"
        }
    }

    /**
     * 해당 병원에 신규 commitment write가 허용되는지 fail-closed로 판단합니다.
     *
     * `SHADOW`는 계산 차이만 관찰하고 영속 상태를 만들지 않습니다. allowlist가 비어
     * 있으면 `WRITE`에서도 모든 병원이 차단됩니다.
     */
    fun isWriteEnabled(clinicId: Long): Boolean =
        mode == AppointmentCommitmentMode.WRITE && clinicId in clinicAllowlist

    companion object {
        private const val serialVersionUID = 1L
        private val MAX_PROPOSAL_TTL: Duration = Duration.ofDays(7)
    }
}

/**
 * 신규 commitment 유입의 점진 배포 단계입니다.
 */
enum class AppointmentCommitmentMode {
    /** 계산과 쓰기를 모두 수행하지 않습니다. */
    OFF,

    /** legacy 결과와 차이만 관찰하고 commitment v2 row는 쓰지 않습니다. */
    SHADOW,

    /** allowlist 병원에 한해 commitment v2 row를 생성합니다. */
    WRITE,
}

/**
 * 일시적 충돌에만 적용하는 제한된 재시도 설정입니다.
 *
 * @property maxAttempts 최초 시도를 포함한 최대 시도 횟수입니다.
 * @property initialBackoff 첫 재시도 전 대기 시간입니다.
 */
data class AppointmentCommitmentRetryProperties(
    val maxAttempts: Int = 3,
    val initialBackoff: Duration = Duration.ofMillis(25),
) : Serializable {

    init {
        require(maxAttempts in 1..3) { "maxAttempts must be between 1 and 3" }
        require(!initialBackoff.isNegative && !initialBackoff.isZero && initialBackoff <= Duration.ofSeconds(1)) {
            "initialBackoff must be greater than zero and at most one second"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 단일 구매 Plan을 동기식으로 계산할 때의 변경 불가능한 platform 상한입니다.
 *
 * 각 값은 설계 승인값보다 작게 조정할 수는 있지만 크게 설정할 수 없습니다. 작은 값은
 * 특정 배포에서 부하를 더 보수적으로 제한할 때 사용합니다.
 */
data class AppointmentCommitmentCeilingProperties(
    val plannedTreatments: Int = 500,
    val relationshipEdges: Int = 4_000,
    val repeatCount: Int = 100,
    val searchDays: Int = 365,
    val candidateSlots: Int = 2_000,
    val returnedProposals: Int = 20,
) : Serializable {

    init {
        require(plannedTreatments in 1..500) { "plannedTreatments must be between 1 and 500" }
        require(relationshipEdges in 1..4_000) { "relationshipEdges must be between 1 and 4000" }
        require(repeatCount in 1..100) { "repeatCount must be between 1 and 100" }
        require(searchDays in 1..365) { "searchDays must be between 1 and 365" }
        require(candidateSlots in 1..2_000) { "candidateSlots must be between 1 and 2000" }
        require(returnedProposals in 1..20) { "returnedProposals must be between 1 and 20" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
