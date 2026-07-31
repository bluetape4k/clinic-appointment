package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.event.notification.NotificationContractException
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest

internal class NotificationContractExceptionHandlerTest {

    @Test
    fun `outbox 계약 장애는 내부 정보를 숨긴 재시도 가능 503으로 변환한다`() {
        val secretMarker = "secret-key-id-and-member-reference"
        val request = MockHttpServletRequest().apply {
            setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "notification-correlation")
        }

        val response = GlobalExceptionHandler().handleNotificationContract(
            NotificationContractException(
                failureCode = NotificationFailureCode.HMAC_KEY_UNAVAILABLE,
                message = secretMarker,
            ),
            request,
        )

        assertEquals(503, response.statusCode.value())
        assertEquals("5", response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertEquals("NOTIFICATION_ENQUEUE_UNAVAILABLE", response.body!!.errorCode)
        assertEquals("notification-correlation", response.body!!.correlationId)
        assertEquals(true, response.body!!.retryable)
        assertFalse(response.body!!.error.contains(secretMarker))
        assertFalse(response.body!!.action.orEmpty().contains(secretMarker))
    }
}
