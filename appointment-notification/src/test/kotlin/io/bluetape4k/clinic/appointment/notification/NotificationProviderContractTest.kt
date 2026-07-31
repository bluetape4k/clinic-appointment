package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class NotificationProviderContractTest {

    @Test
    fun `provider idempotency key는 opaque outbox key만 받아 domain-separated HMAC으로 만든다`() {
        val key = NotificationProviderIdempotencyKeyFactory("s".repeat(32).toByteArray())
            .create(NotificationIdempotencyKey("a".repeat(64)))

        key.value.startsWith("hmac-v1.") shouldBeEqualTo true
        key.value.contains("a".repeat(64)) shouldBeEqualTo false
        NotificationProviderIdempotencyKeyFactory::class.java.methods
            .any { method -> method.name == "create" && method.parameterTypes.contentEquals(arrayOf(String::class.java)) }
            .shouldBeEqualTo(false)
    }

    @Test
    fun `provider idempotency key는 HMAC digest가 아닌 outbox key를 거절한다`() {
        val factory = NotificationProviderIdempotencyKeyFactory("s".repeat(32).toByteArray())

        assertThrows<IllegalArgumentException> {
            factory.create(NotificationIdempotencyKey("raw-appointment-1"))
        }
    }

    @Test
    fun `provider idempotency secret은 최소 32 bytes를 요구한다`() {
        val failure = assertThrows<IllegalArgumentException> {
            NotificationProviderIdempotencyKeyFactory("short".toByteArray())
        }

        failure.message!!.contains("32") shouldBeEqualTo true
    }

    @Test
    fun `provider exception mapper는 Task2 failure enum만 반환한다`() {
        val failureCode = NotificationProviderFailureMapper.fromException(RuntimeException("500 raw provider body"))

        (failureCode in NotificationFailureCode.entries) shouldBeEqualTo true
        failureCode shouldBeEqualTo NotificationFailureCode.PROVIDER_UNAVAILABLE
    }

    @Test
    fun `provider runtime request와 rendered template의 문자열 표현은 개인정보를 가린다`() {
        val request = NotificationProviderRequest(
            channel = NotificationChannelType.SMS,
            destination = "+821012345678",
            idempotencyKey = NotificationProviderIdempotencyKey("hmac-v1.${"A".repeat(43)}"),
            templateKey = NotificationTemplateKey("appointment.confirmed"),
            templateVersion = NotificationTemplateVersion(1),
            rendered = RenderedNotificationTemplate(null, "홍길동 예약", null),
        )

        val text = request.toString()
        text.contains("+821012345678") shouldBeEqualTo false
        text.contains("홍길동") shouldBeEqualTo false
        request.rendered.toString().contains("홍길동") shouldBeEqualTo false
    }
}
