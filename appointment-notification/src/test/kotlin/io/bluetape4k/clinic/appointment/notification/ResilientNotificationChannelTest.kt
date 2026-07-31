package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationProviderMessageReference
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class ResilientNotificationChannelTest {

    @Test
    fun `정상 호출은 provider result를 그대로 반환한다`() {
        val request = providerRequest()
        val delegate = mockk<NotificationChannel>()
        every { delegate.channelType } returns NotificationChannelType.SMS
        every { delegate.send(request) } returns NotificationProviderResult.accepted(
            NotificationProviderMessageReference("provider-1"),
        )

        val channel = ResilientNotificationChannel.create(delegate)
        val result = channel.send(request)

        result shouldBeEqualTo NotificationProviderResult.accepted(NotificationProviderMessageReference("provider-1"))
        verify(exactly = 1) { delegate.send(request) }
    }

    @Test
    fun `최종 provider exception은 삼키지 않고 typed exception으로 전파한다`() {
        val request = providerRequest()
        val delegate = mockk<NotificationChannel>()
        every { delegate.channelType } returns NotificationChannelType.SMS
        every { delegate.send(request) } throws NotificationProviderException(NotificationFailureCode.PROVIDER_UNAVAILABLE)

        val channel = ResilientNotificationChannel.create(
            delegate,
            NotificationResilienceProperties(
                retry = NotificationResilienceProperties.RetryProperties(maxAttempts = 1),
            ),
        )

        val failure = assertThrows<NotificationProviderException> {
            channel.send(request)
        }
        failure.failureCode shouldBeEqualTo NotificationFailureCode.PROVIDER_UNAVAILABLE
    }

    @Test
    fun `cancellation은 provider failure로 매핑하지 않고 그대로 전파한다`() {
        val request = providerRequest()
        val delegate = mockk<NotificationChannel>()
        every { delegate.channelType } returns NotificationChannelType.SMS
        every { delegate.send(request) } throws CancellationException("cancelled")

        val channel = ResilientNotificationChannel.create(delegate)

        assertThrows<CancellationException> {
            channel.send(request)
        }
        verify(exactly = 1) { delegate.send(request) }
    }

    private fun providerRequest(): NotificationProviderRequest =
        NotificationProviderRequest(
            channel = NotificationChannelType.SMS,
            destination = "+821012345678",
            idempotencyKey = NotificationProviderIdempotencyKey("hmac-v1.${"A".repeat(43)}"),
            templateKey = NotificationTemplateKey("appointment.confirmed"),
            templateVersion = NotificationTemplateVersion(1),
            rendered = RenderedNotificationTemplate(
                title = null,
                textBody = "confirmed",
                htmlBody = null,
            ),
        )
}
