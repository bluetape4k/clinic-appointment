package io.bluetape4k.clinic.appointment.model.identity

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class PatientLoginIdentifierTest {

    @Test
    fun `phone is normalized to a single Korean international form`() {
        PatientLoginIdentifier.of(PatientLoginIdentifierKey.PHONE, " 010-1234-5678 ").value
            .shouldBeEqualTo("+821012345678")
        PatientLoginIdentifier.of(PatientLoginIdentifierKey.PHONE, "+82 10 1234 5678").value
            .shouldBeEqualTo("+821012345678")
    }

    @Test
    fun `email is trimmed normalized and case folded`() {
        PatientLoginIdentifier.of(PatientLoginIdentifierKey.EMAIL, " User@Example.COM ").value
            .shouldBeEqualTo("user@example.com")
    }

    @Test
    fun `login id is trimmed and case folded using bounded safe ascii`() {
        PatientLoginIdentifier.of(PatientLoginIdentifierKey.LOGIN_ID, "  Patient_01 ").value
            .shouldBeEqualTo("patient_01")
    }

    @Test
    fun `identifier rejects blank control malformed and overlong values`() {
        listOf(
            PatientLoginIdentifierKey.PHONE to "",
            PatientLoginIdentifierKey.PHONE to "010-1234-000",
            PatientLoginIdentifierKey.EMAIL to "not-an-email",
            PatientLoginIdentifierKey.LOGIN_ID to "환자 아이디",
            PatientLoginIdentifierKey.LOGIN_ID to "a".repeat(65),
            PatientLoginIdentifierKey.EMAIL to "a\n@example.com",
        ).forEach { (key, value) ->
            assertFailsWith<IllegalArgumentException> {
                PatientLoginIdentifier.of(key, value)
            }
        }
    }

    @Test
    fun `registration requires one to three unique keys`() {
        val phone = PatientLoginIdentifier.of(PatientLoginIdentifierKey.PHONE, "01012345678")
        val email = PatientLoginIdentifier.of(PatientLoginIdentifierKey.EMAIL, "patient@example.com")
        val loginId = PatientLoginIdentifier.of(PatientLoginIdentifierKey.LOGIN_ID, "patient01")

        PatientLoginIdentifier.validateForRegistration(listOf(phone))
        PatientLoginIdentifier.validateForRegistration(listOf(phone, email, loginId))

        assertFailsWith<IllegalArgumentException> {
            PatientLoginIdentifier.validateForRegistration(emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            PatientLoginIdentifier.validateForRegistration(listOf(phone, email, loginId, phone))
        }
        assertFailsWith<IllegalArgumentException> {
            PatientLoginIdentifier.validateForRegistration(listOf(phone, phone))
        }
    }
}
