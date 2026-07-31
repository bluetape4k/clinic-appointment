package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType

/**
 * 알림 채널 인터페이스.
 *
 * 외부 알림 서비스(이메일, SMS, Push 등)를 추상화합니다.
 * 기본 구현체는 [DummyNotificationChannel]이며,
 * 운영 환경에서는 Feign 기반 구현체로 교체합니다.
 */
interface NotificationChannel {

    /** provider channel 유형입니다. */
    val channelType: NotificationChannelType

    fun send(request: NotificationProviderRequest): NotificationProviderResult
}
