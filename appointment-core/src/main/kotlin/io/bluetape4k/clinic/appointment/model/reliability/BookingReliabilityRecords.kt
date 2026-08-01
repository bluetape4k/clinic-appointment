package io.bluetape4k.clinic.appointment.model.reliability

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventSource
import java.io.Serializable
import java.time.Instant

/**
 * 평가 시점에 고정한 예약 신뢰성 정책 snapshot입니다.
 *
 * 이 record는 정책 정의 table의 원문 JSON을 다시 저장하지 않고, 평가에 필요한 bounded
 * threshold와 version/hash만 담습니다.
 *
 * @property policyVersionId 평가에 사용한 활성 정책 version입니다.
 * @property policyHash canonical policy payload의 lowercase SHA-256입니다.
 * @property enabled false이면 이력 threshold를 평가하지 않습니다.
 * @property lookbackDays 고객 책임 사건을 조회할 과거 기간입니다.
 * @property lateCancellationWindowMinutes 예약 시작 전 이 시간 이내 취소를 late cancellation으로 봅니다.
 * @property noShowThreshold lookback 안 고객 책임 no-show 누적 기준입니다.
 * @property lateCancellationThreshold lookback 안 고객 책임 late cancellation 누적 기준입니다.
 * @property coolingOffHours 신규 제한 결정의 기본 유효 시간입니다.
 * @property restrictionMode threshold 충족 시 downstream offer가 적용할 제한 방식입니다.
 * @property noShowThresholdEnabled no-show threshold를 적용할지 나타냅니다. clinic override가
 * `DISABLE`이면 false가 되며 관찰 count는 남기되 제한 기준에는 반영하지 않습니다.
 * @property lateCancellationThresholdEnabled late cancellation threshold를 적용할지 나타냅니다.
 * `DISABLE`이면 false가 되며 관찰 count는 남기되 제한 기준에는 반영하지 않습니다.
 */
data class BookingReliabilityPolicySnapshot(
    val policyVersionId: Long,
    val policyHash: String,
    val enabled: Boolean,
    val lookbackDays: Int,
    val lateCancellationWindowMinutes: Int,
    val noShowThreshold: Int,
    val lateCancellationThreshold: Int,
    val coolingOffHours: Int,
    val restrictionMode: BookingReliabilityRestrictionMode,
    val noShowThresholdEnabled: Boolean = true,
    val lateCancellationThresholdEnabled: Boolean = true,
) : Serializable {
    init {
        require(policyVersionId > 0) { "policyVersionId must be positive" }
        require(SHA256_REGEX.matches(policyHash)) { "policyHash must be lowercase SHA-256" }
        require(lookbackDays > 0) { "lookbackDays must be positive" }
        require(lateCancellationWindowMinutes >= 0) {
            "lateCancellationWindowMinutes must be zero or positive"
        }
        require(noShowThreshold >= 0) { "noShowThreshold must be zero or positive" }
        require(lateCancellationThreshold >= 0) {
            "lateCancellationThreshold must be zero or positive"
        }
        require(coolingOffHours > 0) { "coolingOffHours must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 신규 예약 command가 보존하는 immutable reliability decision stamp입니다. */
data class BookingReliabilityDecisionStamp(
    val decisionId: Long,
    val policyVersionId: Long,
    val policyHash: String,
    val evaluationDigest: String,
    val expiresAt: Instant?,
) : Serializable {
    init {
        require(decisionId > 0) { "decisionId must be positive" }
        require(policyVersionId > 0) { "policyVersionId must be positive" }
        require(SHA256_REGEX.matches(policyHash)) { "policyHash must be lowercase SHA-256" }
        require(SHA256_REGEX.matches(evaluationDigest)) {
            "evaluationDigest must be lowercase SHA-256"
        }
    }

    fun isUsableAt(at: Instant): Boolean = expiresAt == null || expiresAt.isAfter(at)

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 평가 입력으로 정규화된 예약 이력 사건입니다.
 *
 * 고객 이름, 전화번호, 자유 입력 취소 사유는 포함하지 않습니다. 고객 식별자는 [MemberId]로만
 * 전달하고, 책임 분류와 사건 종류는 enum으로 제한합니다.
 *
 * @property appointmentId trigger 감사에 사용할 예약 식별자입니다.
 * @property memberId 회원 서비스의 불투명 식별자입니다.
 * @property eventType 예약 상태 사건의 bounded type입니다.
 * @property responsibility 사건 책임 분류입니다.
 * @property scheduledStartAt 원 예약 시작 시각입니다.
 * @property occurredAt 상태 사건 발생 시각입니다.
 * @property eventId source event의 opaque dedupe 식별자입니다.
 * @property sourceVersion 같은 event의 정정 순서를 나타내는 양수 버전입니다.
 * @property source 사건을 발행한 신뢰 경계입니다.
 */
data class BookingReliabilityEventRecord(
    val appointmentId: Long,
    val memberId: MemberId,
    val eventType: BookingReliabilityEventType,
    val responsibility: BookingReliabilityResponsibility,
    val scheduledStartAt: Instant,
    val occurredAt: Instant,
    val eventId: String = "appointment-$appointmentId",
    val sourceVersion: Long = 1L,
    val source: BookingReliabilityEventSource = BookingReliabilityEventSource.APPOINTMENT,
    /** ingress가 이미 계산한 canonical payload hash입니다. 없으면 repository가 계산합니다. */
    val eventHash: String? = null,
) : Serializable {
    init {
        require(appointmentId > 0) { "appointmentId must be positive" }
        require(eventId.isNotBlank() && eventId.length <= 160) {
            "eventId must contain 1..160 characters"
        }
        require(sourceVersion > 0) { "sourceVersion must be positive" }
        require(eventHash == null || SHA256_REGEX.matches(eventHash)) {
            "eventHash must be lowercase SHA-256 when present"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 직원이 명시적으로 적용한 평가 override snapshot입니다.
 *
 * 자유 입력 사유나 고객 PII는 담지 않습니다. 실제 상세 감사 문구가 필요하면 별도 감사 저장소가
 * bounded reason code와 actor 식별자를 기준으로 관리해야 합니다.
 *
 * @property memberId override 대상 회원 식별자입니다.
 * @property verdict override가 강제할 판정입니다.
 * @property reasonCode override를 설명하는 bounded reason입니다.
 * @property effectiveFrom 적용 시작 시각입니다.
 * @property expiresAt 적용 종료 시각입니다. null이면 명시 해제 전까지 유효합니다.
 * @property policyVersionId clear가 만들어진 정책 version입니다. 정책이 바뀌면 clear는
 * 더 이상 현재 정책을 대체하지 않습니다.
 */
data class BookingReliabilityOverrideRecord(
    val memberId: MemberId,
    val verdict: BookingReliabilityVerdict,
    val reasonCode: BookingReliabilityReasonCode,
    val effectiveFrom: Instant,
    val expiresAt: Instant?,
    val policyVersionId: Long? = null,
) : Serializable {
    init {
        require(verdict != BookingReliabilityVerdict.STALE) { "override verdict must not be STALE" }
        require(verdict != BookingReliabilityVerdict.UNAVAILABLE) {
            "override verdict must not be UNAVAILABLE"
        }
        require(expiresAt == null || expiresAt > effectiveFrom) {
            "expiresAt must be after effectiveFrom"
        }
        require(policyVersionId == null || policyVersionId > 0) {
            "policyVersionId must be positive when present"
        }
    }

    fun isActiveAt(at: Instant): Boolean =
        !effectiveFrom.isAfter(at) && (expiresAt == null || expiresAt.isAfter(at))

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 예약 제안 판단에 첨부할 신뢰성 평가 결정입니다.
 *
 * @property tenantGroupId tenant 범위입니다.
 * @property clinicId clinic 범위입니다.
 * @property memberId 회원 서비스의 불투명 식별자입니다.
 * @property policyVersionId 평가에 사용한 정책 version입니다. stale/unavailable이면 null입니다.
 * @property policyHash 평가에 사용한 정책 hash입니다. stale/unavailable이면 null입니다.
 * @property evaluatedAt 평가 기준 시각입니다.
 * @property verdict 최종 판정입니다.
 * @property reasonCodes 감사용 bounded reason code 집합입니다.
 * @property triggers threshold에 실제 반영된 예약 trigger입니다.
 * @property noShowCount lookback 안 고객 책임 no-show 수입니다.
 * @property lateCancellationCount lookback 안 고객 책임 late cancellation 수입니다.
 * @property effectiveFrom 제한 또는 override 판정 시작 시각입니다.
 * @property expiresAt 제한 또는 override 판정 종료 시각입니다.
 * @property decisionDigest 결정 내용을 canonical하게 요약한 lowercase SHA-256입니다.
 * @property hasAdditionalTriggers true이면 전체 trigger는 audit cursor로 이어집니다.
 * @property auditCursor 개인정보와 원 예약 식별자를 포함하지 않는 opaque cursor입니다.
 */
data class BookingReliabilityDecisionRecord(
    val tenantGroupId: Long,
    val clinicId: Long,
    val memberId: MemberId,
    val policyVersionId: Long?,
    val policyHash: String?,
    val evaluatedAt: Instant,
    val verdict: BookingReliabilityVerdict,
    val reasonCodes: Set<BookingReliabilityReasonCode>,
    val triggers: List<BookingReliabilityTrigger>,
    val noShowCount: Int,
    val lateCancellationCount: Int,
    val effectiveFrom: Instant?,
    val expiresAt: Instant?,
    val decisionDigest: String,
    val hasAdditionalTriggers: Boolean = false,
    val auditCursor: String? = null,
    val decisionId: Long? = null,
) : Serializable {
    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(policyHash == null || SHA256_REGEX.matches(policyHash)) {
            "policyHash must be lowercase SHA-256"
        }
        require(reasonCodes.isNotEmpty()) { "reasonCodes must not be empty" }
        require(noShowCount >= 0) { "noShowCount must be zero or positive" }
        require(lateCancellationCount >= 0) {
            "lateCancellationCount must be zero or positive"
        }
        require(SHA256_REGEX.matches(decisionDigest)) {
            "decisionDigest must be lowercase SHA-256"
        }
        require(decisionId == null || decisionId > 0) { "decisionId must be positive when present" }
        require(auditCursor == null || auditCursor.length <= 512) {
            "auditCursor must not exceed 512 characters"
        }
        require(expiresAt == null || effectiveFrom == null || expiresAt > effectiveFrom) {
            "expiresAt must be after effectiveFrom"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

private val SHA256_REGEX = Regex("[0-9a-f]{64}")
