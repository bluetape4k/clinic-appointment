package io.bluetape4k.clinic.appointment.model.reliability

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import java.io.Serializable
import java.time.Instant

/**
 * 직원 override 감사 ledger에 저장하는 append-only action입니다.
 */
enum class BookingReliabilityOverrideAction {
    /** 직원이 정책 결정을 대체하는 판정을 적용했습니다. */
    OVERRIDE,

    /** 직원이 활성 override를 해제했습니다. */
    CLEAR,
}

/**
 * 예약 신뢰성 재평가 worker job 상태입니다.
 */
enum class BookingReliabilityReevaluationJobStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    PAUSED,
    COMPLETED,
    FAILED,
    DEAD_LETTER,
    STALE,
}

/**
 * 직원 override append-only 감사 record입니다.
 */
data class BookingReliabilityOverrideAuditRecord(
    val tenantGroupId: Long,
    val clinicId: Long,
    val memberId: MemberId,
    val action: BookingReliabilityOverrideAction,
    val verdict: BookingReliabilityVerdict?,
    val reasonCode: BookingReliabilityReasonCode,
    val policyVersionId: Long? = null,
    val effectiveFrom: Instant,
    val expiresAt: Instant?,
    val actorId: String,
    val actorType: String,
    val idempotencyKeyHash: String,
    val commandHash: String,
    val resultDigest: String,
    val expectedVersion: Long,
    val decisionId: Long? = null,
    val previousDecisionDigest: String? = null,
    val correlationId: String? = null,
    val auditId: Long? = null,
) : Serializable {
    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(action == BookingReliabilityOverrideAction.CLEAR || verdict != null) {
            "override action requires verdict"
        }
        require(action == BookingReliabilityOverrideAction.OVERRIDE || verdict == null) {
            "clear action must not carry verdict"
        }
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
        require(actorId.isNotBlank() && actorId.length <= 128) { "actorId must contain 1..128 characters" }
        require(actorType.isNotBlank() && actorType.length <= 32) { "actorType must contain 1..32 characters" }
        require(SHA256_REGEX.matches(idempotencyKeyHash)) {
            "idempotencyKeyHash must be lowercase SHA-256"
        }
        require(SHA256_REGEX.matches(commandHash)) { "commandHash must be lowercase SHA-256" }
        require(SHA256_REGEX.matches(resultDigest)) { "resultDigest must be lowercase SHA-256" }
        require(previousDecisionDigest == null || SHA256_REGEX.matches(previousDecisionDigest)) {
            "previousDecisionDigest must be lowercase SHA-256"
        }
        require(expectedVersion >= 0) { "expectedVersion must be zero or positive" }
        require(decisionId == null || decisionId > 0) { "decisionId must be positive when present" }
        require(correlationId == null || correlationId.length <= 160) {
            "correlationId must not exceed 160 characters"
        }
        require(auditId == null || auditId > 0) { "auditId must be positive when present" }
    }

    fun toOverrideRecord(): BookingReliabilityOverrideRecord? =
        when (action) {
            BookingReliabilityOverrideAction.OVERRIDE -> BookingReliabilityOverrideRecord(
                memberId = memberId,
                verdict = requireNotNull(verdict),
                reasonCode = reasonCode,
                effectiveFrom = effectiveFrom,
                expiresAt = expiresAt,
                policyVersionId = policyVersionId,
            )
            BookingReliabilityOverrideAction.CLEAR -> BookingReliabilityOverrideRecord(
                memberId = memberId,
                verdict = BookingReliabilityVerdict.ELIGIBLE,
                reasonCode = BookingReliabilityReasonCode.MANUAL_CLEAR,
                effectiveFrom = effectiveFrom,
                expiresAt = expiresAt,
                policyVersionId = policyVersionId,
            )
        }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 재평가 worker가 keyset cursor와 lease를 durable하게 이어가기 위한 job record입니다.
 */
data class BookingReliabilityReevaluationJobRecord(
    val tenantGroupId: Long,
    val clinicId: Long,
    val memberId: MemberId,
    val idempotencyKeyHash: String,
    val commandHash: String,
    val status: BookingReliabilityReevaluationJobStatus,
    val nextAttemptAt: Instant,
    val policyVersionId: Long? = null,
    val leaseOwner: String? = null,
    val leaseExpiresAt: Instant? = null,
    val attemptCount: Int = 0,
    val cursorOccurredAt: Instant? = null,
    val cursorEventId: String? = null,
    val scannedCount: Long = 0L,
    val decisionCount: Long = 0L,
    val lastFailureCode: String? = null,
    val jobId: Long? = null,
) : Serializable {
    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(policyVersionId == null || policyVersionId > 0) {
            "policyVersionId must be positive when present"
        }
        require(SHA256_REGEX.matches(idempotencyKeyHash)) {
            "idempotencyKeyHash must be lowercase SHA-256"
        }
        require(SHA256_REGEX.matches(commandHash)) { "commandHash must be lowercase SHA-256" }
        require(leaseOwner == null || leaseOwner.length <= 160) {
            "leaseOwner must not exceed 160 characters"
        }
        require(attemptCount >= 0) { "attemptCount must be zero or positive" }
        require(cursorEventId == null || cursorEventId.length <= 160) {
            "cursorEventId must not exceed 160 characters"
        }
        require(scannedCount >= 0) { "scannedCount must be zero or positive" }
        require(decisionCount >= 0) { "decisionCount must be zero or positive" }
        require(decisionCount <= scannedCount) { "decisionCount must not exceed scannedCount" }
        require(lastFailureCode == null || lastFailureCode.length <= 96) {
            "lastFailureCode must not exceed 96 characters"
        }
        require(jobId == null || jobId > 0) { "jobId must be positive when present" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 재평가 작업의 단조 증가 keyset cursor입니다.
 */
data class BookingReliabilityReevaluationCursor(
    val cursorOccurredAt: Instant?,
    val cursorEventId: String?,
    val scannedCount: Long,
    val decisionCount: Long,
) : Serializable {
    init {
        require((cursorOccurredAt == null) == (cursorEventId == null)) {
            "cursorOccurredAt and cursorEventId must be present together"
        }
        require(cursorEventId == null || cursorEventId.length <= 160) {
            "cursorEventId must not exceed 160 characters"
        }
        require(scannedCount >= 0) { "scannedCount must be zero or positive" }
        require(decisionCount >= 0) { "decisionCount must be zero or positive" }
        require(decisionCount <= scannedCount) { "decisionCount must not exceed scannedCount" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

private val SHA256_REGEX = Regex("[0-9a-f]{64}")
