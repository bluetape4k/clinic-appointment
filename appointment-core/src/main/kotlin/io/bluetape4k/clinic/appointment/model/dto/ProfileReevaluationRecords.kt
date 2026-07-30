package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * CRM 환자 식별자를 노출하지 않는 프로필 재평가 병합 범위입니다.
 *
 * @property patientReferenceFingerprint 신뢰 경계에서 만든 소문자 SHA-256 지문입니다.
 */
data class ProfileReevaluationScope(
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientReferenceFingerprint: String,
) : Serializable {
    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(SHA256_REGEX.matches(patientReferenceFingerprint)) {
            "patientReferenceFingerprint must be lowercase SHA-256"
        }
    }
}

/**
 * 작업 최초 선점 때 한 번 고정하는 예약 상태 우선순위입니다.
 */
enum class ProfileReevaluationPriorityClass {
    /** 아직 `HELD` 예약 존재 여부를 확인하지 않았습니다. */
    UNCLASSIFIED,

    /** 처리 범위에 `HELD` 예약이 하나 이상 있습니다. */
    HELD_PRESENT,

    /** 처리 범위가 `PROPOSED` 예약으로만 구성됩니다. */
    PROPOSED_ONLY,
}

/**
 * 환자 범위별 최신 프로필 revision을 가리키는 병합 지점입니다.
 */
data class ProfileReevaluationHeadRecord(
    val id: Long,
    val scope: ProfileReevaluationScope,
    val latestRevision: Long,
    val latestEventId: String,
    val assessmentRef: String,
    val assessmentHash: String,
    val occurredAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Serializable

/**
 * 프로필 revision 하나를 재평가하는 durable 작업입니다.
 */
data class ProfileReevaluationJobRecord(
    val id: Long,
    val headId: Long,
    val scope: ProfileReevaluationScope,
    val targetRevision: Long,
    val eventId: String,
    val assessmentRef: String,
    val assessmentHash: String,
    val status: ProfileReevaluationJobStatus,
    val occurredAt: Instant,
    val dueAt: Instant,
    val targetDuration: Duration,
    val heldTarget: Duration,
    val proposedTarget: Duration,
    val targetPolicyRef: String,
    val targetPolicyGeneration: Long,
    val nextAttemptAt: Instant,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?,
    val attemptCount: Int,
    val firstAttemptAt: Instant?,
    val redriveCount: Int,
    val rootJobId: Long,
    val redriveOfJobId: Long?,
    val redriveGeneration: Int,
    val priorityClass: ProfileReevaluationPriorityClass,
    val heldCursorAppointmentId: Long?,
    val proposedCursorAppointmentId: Long?,
    val scannedCount: Long,
    val outcomeCounts: ProfileReevaluationOutcomeCounts,
    val lastFailureCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) : Serializable

/**
 * 작업 행에 제한된 크기로 누적하는 결과별 개수입니다.
 */
data class ProfileReevaluationOutcomeCounts(
    val proposalSuperseded: Long = 0L,
    val holdKept: Long = 0L,
    val holdReplaced: Long = 0L,
    val fallbackToProposed: Long = 0L,
    val skippedIneligible: Long = 0L,
    val skippedUnchanged: Long = 0L,
) : Serializable {
    init {
        require(values().all { it >= 0L }) { "outcome counts must be non-negative" }
    }

    internal fun values(): List<Long> = listOf(
        proposalSuperseded,
        holdKept,
        holdReplaced,
        fallbackToProposed,
        skippedIneligible,
        skippedUnchanged,
    )
}

/**
 * 예약 상태별 resume cursor와 이번 checkpoint 증가분입니다.
 */
data class ProfileReevaluationCursor(
    val heldCursorAppointmentId: Long? = null,
    val proposedCursorAppointmentId: Long? = null,
    val scannedDelta: Long = 0L,
    val outcomeDeltas: ProfileReevaluationOutcomeCounts = ProfileReevaluationOutcomeCounts(),
) : Serializable {
    init {
        require(heldCursorAppointmentId == null || heldCursorAppointmentId > 0) {
            "heldCursorAppointmentId must be positive"
        }
        require(proposedCursorAppointmentId == null || proposedCursorAppointmentId > 0) {
            "proposedCursorAppointmentId must be positive"
        }
        require(scannedDelta >= 0L) { "scannedDelta must be non-negative" }
        require(outcomeDeltas.values().sum() <= scannedDelta) {
            "outcome deltas cannot exceed scannedDelta"
        }
    }
}

/**
 * 검증된 프로필 변경 이벤트를 latest-revision 저장소에 병합하는 명령입니다.
 */
data class UpsertProfileChange(
    val scope: ProfileReevaluationScope,
    val revision: Long,
    val eventId: String,
    val assessmentRef: String,
    val assessmentHash: String,
    val occurredAt: Instant,
    val heldTarget: Duration,
    val proposedTarget: Duration,
    val targetPolicyRef: String,
    val targetPolicyGeneration: Long,
) : Serializable {
    init {
        require(revision > 0) { "revision must be positive" }
        require(eventId.isNotBlank() && eventId.length <= 160) { "eventId must contain 1..160 characters" }
        require(assessmentRef.isNotBlank() && assessmentRef.length <= 512) {
            "assessmentRef must contain 1..512 characters"
        }
        require(SHA256_REGEX.matches(assessmentHash)) { "assessmentHash must be lowercase SHA-256" }
        require(!heldTarget.isNegative && !heldTarget.isZero) { "heldTarget must be positive" }
        require(!proposedTarget.isNegative && !proposedTarget.isZero) { "proposedTarget must be positive" }
        require(targetPolicyRef.isNotBlank() && targetPolicyRef.length <= 256) {
            "targetPolicyRef must contain 1..256 characters"
        }
        require(targetPolicyGeneration > 0) { "targetPolicyGeneration must be positive" }
    }
}

/**
 * 병원 간 공정성을 유지하며 실행 가능한 작업을 선점하는 명령입니다.
 */
data class ClaimProfileReevaluationJobs(
    val leaseOwner: String,
    val limit: Int,
    val perClinicLimit: Int,
) : Serializable {
    init {
        require(leaseOwner.isNotBlank() && leaseOwner.length <= 160) {
            "leaseOwner must contain 1..160 characters"
        }
        require(limit in 1..1000) { "limit must be between 1 and 1000" }
        require(perClinicLimit in 1..limit) { "perClinicLimit must be between 1 and limit" }
    }
}

/**
 * 실패한 작업을 원본 변경 없이 다시 실행하기 위한 명령입니다.
 */
data class RedriveProfileReevaluationJob(
    val jobId: Long,
    val cooldown: Duration,
    val expectedRedriveCount: Int? = null,
) : Serializable {
    init {
        require(jobId > 0) { "jobId must be positive" }
        require(!cooldown.isNegative) { "cooldown must be non-negative" }
        require(expectedRedriveCount == null || expectedRedriveCount >= 0) {
            "expectedRedriveCount must be non-negative"
        }
    }
}

/**
 * 예약 한 건에 대한 비식별 감사 결과입니다.
 */
data class ProfileReevaluationOutcomeRecord(
    val id: Long,
    val jobId: Long,
    val targetRevision: Long,
    val appointmentId: Long,
    val outcomeType: ProfileReevaluationOutcomeType,
    val createdAt: Instant,
) : Serializable

private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

