package io.bluetape4k.clinic.appointment.api.auth

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.identity.PatientLoginIdentifierKey
import org.junit.jupiter.api.Test

/** HTTP의 구조화된 identifier 입력이 core canonical 값으로 변환되는 계약입니다. */
class PatientLoginIdentifierNormalizerTest {

    @Test
    fun `phone input is normalized to one canonical representation`() {
        PatientLoginIdentifierNormalizer.normalize(
            PatientLoginIdentifierRequest(PatientLoginIdentifierKey.PHONE, " 010-1234-5678 "),
        ).value shouldBeEqualTo "+821012345678"

        PatientLoginIdentifierNormalizer.normalize(
            PatientLoginIdentifierRequest(PatientLoginIdentifierKey.PHONE, "+82 10 1234 5678"),
        ).value shouldBeEqualTo "+821012345678"
    }

    @Test
    fun `email and login id use NFC trim and lowercase`() {
        PatientLoginIdentifierNormalizer.normalize(
            PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "  PATIENT@Example.COM "),
        ).value shouldBeEqualTo "patient@example.com"
        PatientLoginIdentifierNormalizer.normalize(
            PatientLoginIdentifierRequest(PatientLoginIdentifierKey.LOGIN_ID, "  Hong.Patient "),
        ).value shouldBeEqualTo "hong.patient"
    }

    @Test
    fun `control characters and malformed identifier values are rejected`() {
        assertFailsWith<PatientAuthenticationValidationException> {
            PatientLoginIdentifierNormalizer.normalize(
                PatientLoginIdentifierRequest(PatientLoginIdentifierKey.EMAIL, "patient@example.com\n"),
            )
        }
        assertFailsWith<PatientAuthenticationValidationException> {
            PatientLoginIdentifierNormalizer.normalize(
                PatientLoginIdentifierRequest(PatientLoginIdentifierKey.LOGIN_ID, "ab"),
            )
        }
    }
}
