package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import org.junit.jupiter.api.Test

internal class DummyNotificationChannelTest {

    @Test
    fun `dummy channel은 runtime request를 저장하지 않고 닫힌 성공 결과만 반환한다`() {
        val channel = DummyNotificationChannel()
        val request = NotificationProviderRequest(
            channel = NotificationChannelType.DUMMY,
            destination = "private-destination",
            idempotencyKey = NotificationProviderIdempotencyKey("hmac-v1.${"A".repeat(43)}"),
            templateKey = NotificationTemplateKey("appointment.confirmed"),
            templateVersion = NotificationTemplateVersion(1),
            rendered = RenderedNotificationTemplate(
                title = "private title",
                textBody = "private body",
                htmlBody = null,
            ),
        )

        channel.channelType shouldBeEqualTo NotificationChannelType.DUMMY
        channel.send(request) shouldBeEqualTo NotificationProviderResult.accepted()
        DummyNotificationChannel::class.java.declaredFields
            .none { it.name.contains("history", ignoreCase = true) }
            .shouldBeEqualTo(true)
    }
}
