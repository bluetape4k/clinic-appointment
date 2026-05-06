package io.bluetape4k.clinic.appointment.notification

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable

/**
 * 알림 설정 프로퍼티.
 *
 * ```yaml
 * clinic:
 *   notification:
 *     enabled: true
 *     events:
 *       created: true
 *       confirmed: true
 *       cancelled: true
 *       rescheduled: true
 *     reminder:
 *       enabled: true
 *       day-before: true
 *       same-day: true
 *       same-day-hours-before: 2
 * ```
 *
 * @property enabled 알림 모듈 활성화 여부
 * @property events 예약 이벤트별 알림 설정
 * @property reminder 예약 리마인더 설정
 */
@ConfigurationProperties(prefix = "clinic.notification")
data class NotificationProperties(
    val enabled: Boolean = true,
    val events: EventProperties = EventProperties(),
    val reminder: ReminderProperties = ReminderProperties(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * 예약 이벤트별 알림 설정.
     *
     * @property created 예약 생성 알림 활성화 여부
     * @property confirmed 예약 확정 알림 활성화 여부
     * @property cancelled 예약 취소 알림 활성화 여부
     * @property rescheduled 예약 재배정 알림 활성화 여부
     */
    data class EventProperties(
        val created: Boolean = true,
        val confirmed: Boolean = true,
        val cancelled: Boolean = true,
        val rescheduled: Boolean = true,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * 예약 리마인더 설정.
     *
     * @property enabled 리마인더 활성화 여부
     * @property dayBefore 전일 리마인더 발송 여부
     * @property sameDay 당일 리마인더 발송 여부
     * @property sameDayHoursBefore 당일 리마인더 기준 시간(예약 전 N시간)
     */
    data class ReminderProperties(
        val enabled: Boolean = true,
        val dayBefore: Boolean = true,
        val sameDay: Boolean = true,
        val sameDayHoursBefore: Int = 2,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
