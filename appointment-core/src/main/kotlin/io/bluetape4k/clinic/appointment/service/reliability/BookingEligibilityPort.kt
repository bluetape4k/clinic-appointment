package io.bluetape4k.clinic.appointment.service.reliability

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityPolicySnapshot
import java.io.Serializable
import java.time.Instant

/**
 * 예약 제안 후보의 신뢰성 적격성 평가 입력을 읽는 포트입니다.
 *
 * 구현체는 정책 snapshot과 고객 책임 이력을 같은 권위 read boundary에서 고정해야 합니다.
 * 읽기 중 generation mismatch가 있으면 관대한 기본값 대신 [BookingEligibilityReadResult.Stale]을
 * 반환하고, 저장소 장애나 필수 정책 부재는 [BookingEligibilityReadResult.Unavailable]로 닫습니다.
 */
interface BookingEligibilityPort {

    /**
     * 지정 회원의 평가 입력을 읽습니다.
     */
    fun loadBookingEligibility(query: BookingEligibilityQuery): BookingEligibilityReadResult
}

/**
 * 예약 신뢰성 평가 요청입니다.
 *
 * @property tenantGroupId tenant 범위입니다.
 * @property clinicId clinic 범위입니다.
 * @property memberId 회원 서비스가 발급한 불투명 식별자입니다.
 * @property asOf 평가 기준 시각입니다.
 */
data class BookingEligibilityQuery(
    val tenantGroupId: Long,
    val clinicId: Long,
    val memberId: MemberId,
    val asOf: Instant,
    val requestedPolicySnapshotId: Long? = null,
) : Serializable {
    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(requestedPolicySnapshotId == null || requestedPolicySnapshotId > 0) {
            "requestedPolicySnapshotId must be positive when present"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 평가에 필요한 입력 bundle입니다.
 *
 * @property policy 정책 snapshot입니다.
 * @property events lookback 후보가 될 예약 사건입니다.
 * @property overrides 직원 override snapshot입니다.
 * @property previousDecision 직전 immutable decision입니다. cooling-off를 재발행하지 않고
 * 만료·새 사건 경계를 판단할 때 사용합니다.
 */
data class BookingEligibilityInput(
    val policy: BookingReliabilityPolicySnapshot,
    val events: List<BookingReliabilityEventRecord>,
    val overrides: List<BookingReliabilityOverrideRecord> = emptyList(),
    val previousDecision: BookingReliabilityDecisionRecord? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 포트 읽기 결과의 닫힌 집합입니다.
 */
sealed interface BookingEligibilityReadResult : Serializable {
    /**
     * 평가 가능한 입력을 읽었습니다.
     */
    data class Available(
        val input: BookingEligibilityInput,
    ) : BookingEligibilityReadResult {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * 읽기 중 정책 또는 이력 generation이 바뀌어 현재 snapshot을 사용할 수 없습니다.
     */
    data object Stale : BookingEligibilityReadResult {
        private const val serialVersionUID = 1L
    }

    /**
     * 정책이나 이력을 신뢰 가능하게 읽을 수 없습니다.
     */
    data object Unavailable : BookingEligibilityReadResult {
        private const val serialVersionUID = 1L
    }
}
