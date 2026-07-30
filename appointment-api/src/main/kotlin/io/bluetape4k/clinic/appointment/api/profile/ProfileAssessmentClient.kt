package io.bluetape4k.clinic.appointment.api.profile

/**
 * 한 번의 CRM assessment 조회에 필요한 비식별 입력입니다.
 */
data class FetchProfileAssessment(
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientReferenceFingerprint: String,
    val profileRevision: Long,
    val assessmentReference: String,
    val assessmentHash: String,
    val correlationId: String,
) {
    init {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(LOWERCASE_SHA256.matches(patientReferenceFingerprint)) {
            "patientReferenceFingerprint must be lowercase SHA-256"
        }
        require(profileRevision > 0) { "profileRevision must be positive" }
        require(assessmentReference.isNotBlank() && assessmentReference.length <= 512) {
            "assessmentReference must contain 1..512 characters"
        }
        require(LOWERCASE_SHA256.matches(assessmentHash)) {
            "assessmentHash must be lowercase SHA-256"
        }
        require(SAFE_CORRELATION_ID.matches(correlationId)) {
            "correlationId must be a bounded identifier"
        }
    }

    private companion object {
        val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
        val SAFE_CORRELATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

fun interface ProfileAssessmentClient {
    fun fetch(request: FetchProfileAssessment): ProfileSchedulingAssessment
}

enum class ProfileAssessmentFailureKind {
    RETRYABLE_TECHNICAL,
    RETRYABLE_BACKPRESSURE,
    TERMINAL_SECURITY,
    TERMINAL_CONTRACT,
}

enum class ProfileAssessmentFailureCode(
    val kind: ProfileAssessmentFailureKind,
) {
    CONCURRENCY_SATURATED(ProfileAssessmentFailureKind.RETRYABLE_BACKPRESSURE),
    TIMEOUT(ProfileAssessmentFailureKind.RETRYABLE_TECHNICAL),
    UPSTREAM_UNAVAILABLE(ProfileAssessmentFailureKind.RETRYABLE_TECHNICAL),
    AUTHENTICATION_INFRASTRUCTURE_UNAVAILABLE(ProfileAssessmentFailureKind.RETRYABLE_TECHNICAL),
    ENDPOINT_ADDRESS_REJECTED(ProfileAssessmentFailureKind.TERMINAL_SECURITY),
    ASSESSMENT_REFERENCE_INVALID(ProfileAssessmentFailureKind.TERMINAL_SECURITY),
    REDIRECT_REJECTED(ProfileAssessmentFailureKind.TERMINAL_SECURITY),
    RESPONSE_IDENTITY_MISMATCH(ProfileAssessmentFailureKind.TERMINAL_SECURITY),
    RESPONSE_TOO_LARGE(ProfileAssessmentFailureKind.TERMINAL_CONTRACT),
    RESPONSE_CONTENT_TYPE_INVALID(ProfileAssessmentFailureKind.TERMINAL_CONTRACT),
    SCHEMA_INVALID(ProfileAssessmentFailureKind.TERMINAL_CONTRACT),
    HTTP_CONTRACT_REJECTED(ProfileAssessmentFailureKind.TERMINAL_CONTRACT),
}

/**
 * CRM 응답 본문이나 식별자를 message에 넣지 않는 안정적인 실패입니다.
 */
class ProfileAssessmentException(
    val code: ProfileAssessmentFailureCode,
) : RuntimeException(code.name) {
    val retryable: Boolean
        get() = code.kind == ProfileAssessmentFailureKind.RETRYABLE_TECHNICAL ||
            code.kind == ProfileAssessmentFailureKind.RETRYABLE_BACKPRESSURE
}
