package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType

/**
 * 더미 알림 채널.
 *
 * 발송 내용을 외부로 보내지 않고 닫힌 성공 결과만 반환한다. destination, 렌더링 본문,
 * 이름과 provider payload는 로그나 별도 history에 기록하지 않는다.
 */
class DummyNotificationChannel : NotificationChannel {

    companion object : KLogging()

    override val channelType: NotificationChannelType = NotificationChannelType.DUMMY

    override fun send(request: NotificationProviderRequest): NotificationProviderResult {
        log.info { "[DUMMY] notification outcome: channel=${request.channel}, outcome=ACCEPTED" }
        return NotificationProviderResult.accepted()
    }
}
