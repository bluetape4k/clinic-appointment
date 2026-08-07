package io.bluetape4k.clinic.appointment.event.integration

import java.io.Serializable
import java.time.Instant

/**
 * 정확히 하나의 tenant, clinic, producer, source authority에 대해 producer가
 * 권위를 가진 aggregate version이 최신임을 증명하는 제한된 기록입니다.
 */
data class SourceAuthorityVersionProof(
    val tenantGroupId: Long,
    val clinicId: Long,
    val producer: String,
    val sourceAuthority: String,
    val sourceAggregateId: String,
    val verifiedVersion: Long,
    val verifiedAt: Instant,
    val expiresAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

enum class SourceAuthorityFailureReason(
    val reasonCode: String,
) {
    TIMEOUT("SOURCE_AUTHORITY_TIMEOUT"),
    CIRCUIT_OPEN("SOURCE_AUTHORITY_CIRCUIT_OPEN"),
}

class SourceAuthorityUnavailableException(
    val failureReason: SourceAuthorityFailureReason,
) : RuntimeException(failureReason.reasonCode)
