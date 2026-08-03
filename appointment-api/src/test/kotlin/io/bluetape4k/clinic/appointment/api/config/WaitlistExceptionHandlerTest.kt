package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.security.SecurityErrorResponseWriter
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistApiError
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistApiException
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class WaitlistExceptionHandlerTest {

    @Test
    fun `waitlist exception preserves stable reason and retry contract`() {
        val request = MockHttpServletRequest("POST", "/api/tenant-a/clinics/7/waitlist/offers/o-1/confirm")
            .apply { setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "waitlist-correlation") }

        val response = GlobalExceptionHandler().handleWaitlist(
            WaitlistApiException(WaitlistApiError.WAITLIST_UNAVAILABLE),
            request,
        )

        response.statusCode.value() shouldBeEqualTo 503
        response.headers.getFirst("Retry-After") shouldBeEqualTo "5"
        val body = requireNotNull(response.body)
        body.reasonCode shouldBeEqualTo "WAITLIST_UNAVAILABLE"
        body.errorCode shouldBeEqualTo "WAITLIST_UNAVAILABLE"
        body.correlationId shouldBeEqualTo "waitlist-correlation"
        body.retryable shouldBeEqualTo true
        body.success shouldBeEqualTo false
    }

    @Test
    fun `security serialization exposes reason without internal details`() {
        val response = MockHttpServletResponse()
        SecurityErrorResponseWriter.write(response, WaitlistApiError.WAITLIST_FORBIDDEN)

        response.status shouldBeEqualTo 403
        response.contentAsString.shouldContain("\"reasonCode\":\"WAITLIST_FORBIDDEN\"")
        response.contentAsString.shouldContain("\"errorCode\":\"WAITLIST_FORBIDDEN\"")
        response.contentAsString.shouldNotContain("member")
    }
}
