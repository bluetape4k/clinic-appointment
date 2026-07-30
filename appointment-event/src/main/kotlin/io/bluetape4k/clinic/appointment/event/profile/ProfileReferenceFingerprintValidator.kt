package io.bluetape4k.clinic.appointment.event.profile

import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope

/**
 * CRM 신뢰 경계에서 만든 비식별 환자 지문만 예약 재평가 범위로 허용합니다.
 */
object ProfileReferenceFingerprintValidator {
    private val lowercaseSha256 = Regex("[0-9a-f]{64}")

    fun validate(patientReferenceFingerprint: String) {
        require(lowercaseSha256.matches(patientReferenceFingerprint)) {
            "patientReferenceFingerprint must be lowercase SHA-256"
        }
    }

    fun scope(
        tenantGroupId: Long,
        clinicId: Long,
        patientReferenceFingerprint: String,
    ): ProfileReevaluationScope {
        validate(patientReferenceFingerprint)
        return ProfileReevaluationScope(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            patientReferenceFingerprint = patientReferenceFingerprint,
        )
    }
}
