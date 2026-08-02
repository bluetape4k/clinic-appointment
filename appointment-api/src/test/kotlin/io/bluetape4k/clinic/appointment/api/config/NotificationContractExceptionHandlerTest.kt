package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.event.notification.NotificationContractException
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
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

        response.statusCode.value() shouldBeEqualTo 503
        response.headers.getFirst(HttpHeaders.RETRY_AFTER) shouldBeEqualTo "5"
        val body = response.body.shouldNotBeNull()
        body.errorCode shouldBeEqualTo "NOTIFICATION_ENQUEUE_UNAVAILABLE"
        body.correlationId shouldBeEqualTo "notification-correlation"
        body.retryable.shouldBeTrue()
        body.error.contains(secretMarker).shouldBeFalse()
        body.action.orEmpty().contains(secretMarker).shouldBeFalse()
    }
}
