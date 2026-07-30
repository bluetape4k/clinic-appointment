package io.bluetape4k.clinic.appointment.api.profile

import java.time.Instant

/**
 * CRM assessment가 허용한 절대 시간 구간입니다.
 */
data class AllowedTimeWindow(
    val startAt: Instant,
    val endAt: Instant,
) {
    init {
        require(endAt > startAt) { "allowed time window endAt must be later than startAt" }
    }
}

/**
 * 예약 후보 계산에 필요한 CRM assessment의 최소 projection입니다.
 *
 * 이름, 생년월일, 진단, 프로필 특징, 점수, 설명, 교정 정보는 이 계약에 포함하지
 * 않습니다. 응답 객체는 worker의 한 번의 계산에만 사용하고 영속화하지 않습니다.
 */
data class ProfileSchedulingAssessment(
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientReferenceFingerprint: String,
    val profileRevision: Long,
    val assessmentReference: String,
    val assessmentHash: String,
    val eligibleServiceCodes: Set<String>,
    val requiredResourceTags: Set<String>,
    val allowedTimeWindows: List<AllowedTimeWindow>,
) {
    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(lowercaseSha256.matches(patientReferenceFingerprint)) {
            "patientReferenceFingerprint must be lowercase SHA-256"
        }
        require(profileRevision > 0) { "profileRevision must be positive" }
        require(assessmentReference.isNotBlank() && assessmentReference.length <= MAX_REFERENCE_LENGTH) {
            "assessmentReference must contain 1..$MAX_REFERENCE_LENGTH characters"
        }
        require(assessmentReference.none(Char::isISOControl)) {
            "assessmentReference must not contain control characters"
        }
        require(lowercaseSha256.matches(assessmentHash)) {
            "assessmentHash must be lowercase SHA-256"
        }
        validateIdentifiers(eligibleServiceCodes, "eligibleServiceCodes")
        validateIdentifiers(requiredResourceTags, "requiredResourceTags")
        require(allowedTimeWindows.size <= MAX_TIME_WINDOWS) {
            "allowedTimeWindows exceeds $MAX_TIME_WINDOWS items"
        }
    }

    private fun validateIdentifiers(values: Set<String>, fieldName: String) {
        require(values.size <= MAX_IDENTIFIERS) { "$fieldName exceeds $MAX_IDENTIFIERS items" }
        require(values.all(identifier::matches)) {
            "$fieldName contains an invalid identifier"
        }
    }

    private companion object {
        const val MAX_REFERENCE_LENGTH = 512
        const val MAX_IDENTIFIERS = 64
        const val MAX_TIME_WINDOWS = 64
        val lowercaseSha256 = Regex("[0-9a-f]{64}")
        val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
    }
}
