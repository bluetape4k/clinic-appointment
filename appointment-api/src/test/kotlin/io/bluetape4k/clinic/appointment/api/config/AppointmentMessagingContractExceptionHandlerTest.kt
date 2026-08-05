package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingContractException
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingFailureCode
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest

internal class AppointmentMessagingContractExceptionHandlerTest {
    @Test
    fun `outbox persistence failure is a privacy safe retryable 503`() {
        val secretMarker = "sql-and-appointment-identifiers"
        val request = MockHttpServletRequest().apply {
            setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "appointment-correlation")
        }

        val response = GlobalExceptionHandler().handleAppointmentMessagingContract(
            AppointmentMessagingContractException(
                failureCode = AppointmentMessagingFailureCode.OUTBOX_PERSISTENCE_UNAVAILABLE,
                message = secretMarker,
            ),
            request,
        )

        response.statusCode.value() shouldBeEqualTo 503
        response.headers.getFirst(HttpHeaders.RETRY_AFTER) shouldBeEqualTo "5"
        val body = response.body.shouldNotBeNull()
        body.errorCode shouldBeEqualTo "APPOINTMENT_MESSAGING_UNAVAILABLE"
        body.correlationId shouldBeEqualTo "appointment-correlation"
        body.retryable.shouldBeTrue()
        body.error.contains(secretMarker).shouldBeFalse()
        body.action.orEmpty().contains(secretMarker).shouldBeFalse()
    }
}
