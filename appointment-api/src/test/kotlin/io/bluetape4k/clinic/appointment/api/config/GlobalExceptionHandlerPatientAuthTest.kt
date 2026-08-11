package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.api.auth.PatientAuthenticationValidationException
import io.bluetape4k.clinic.appointment.api.auth.PatientDuplicateIdentifierException
import io.bluetape4k.clinic.appointment.api.auth.PatientInvalidCredentialsException
import io.bluetape4k.clinic.appointment.api.auth.PatientLoginRateLimitedException
import jakarta.servlet.http.HttpServletRequest
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/** 환자 인증 예외의 generic, privacy-safe wire envelope 계약입니다. */
class GlobalExceptionHandlerPatientAuthTest {

    private val handler = GlobalExceptionHandler()
    private val request = mockk<HttpServletRequest> {
        every { getAttribute(any()) } returns "patient-auth-correlation"
        every { requestURI } returns "/api/tenant-default/auth/login"
    }

    @Test
    fun `invalid credentials maps to generic 401 without input detail`() {
        val response = handler.handlePatientInvalidCredentials(
            PatientInvalidCredentialsException(),
            request,
        )

        response.statusCode shouldBeEqualTo HttpStatus.UNAUTHORIZED
        response.body?.errorCode shouldBeEqualTo "PATIENT_INVALID_CREDENTIALS"
        response.body?.error.orEmpty().shouldNotContain("password")
        response.body?.error.orEmpty().shouldNotContain("patient@example.com")
    }

    @Test
    fun `validation duplicate and rate limit use stable status and codes`() {
        handler.handlePatientValidation(PatientAuthenticationValidationException(), request)
            .statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        handler.handlePatientDuplicate(PatientDuplicateIdentifierException(), request)
            .statusCode shouldBeEqualTo HttpStatus.CONFLICT
        val rateLimited = handler.handlePatientRateLimited(PatientLoginRateLimitedException(), request)
        rateLimited.statusCode shouldBeEqualTo HttpStatus.TOO_MANY_REQUESTS
        rateLimited.headers.getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER) shouldBeEqualTo "60"
    }
}
