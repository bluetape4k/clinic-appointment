package io.bluetape4k.clinic.appointment.event.notification

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 알림 template parameter의 닫힌 type discriminator다.
 */
enum class NotificationParameterType {
    APPOINTMENT_CONFIRMED,
}

/**
 * 알림 template에 전달할 수 있는 parameter의 닫힌 allow-list다.
 *
 * 수신자 이름, 전화번호, 렌더링된 메시지처럼 member profile에서 유래한 데이터는 이
 * 계약에 포함하지 않는다.
 */
sealed interface NotificationTemplateParameters : Serializable {
    val parameterType: NotificationParameterType
}

/**
 * 예약 확정 알림 template parameter다.
 */
data class AppointmentConfirmedParameters(
    val clinicDisplayName: String,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
) : NotificationTemplateParameters {

    init {
        validateDurableOpaqueString(clinicDisplayName, "clinicDisplayName", MAX_CLINIC_DISPLAY_NAME_LENGTH)
    }

    override val parameterType: NotificationParameterType = NotificationParameterType.APPOINTMENT_CONFIRMED

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_CLINIC_DISPLAY_NAME_LENGTH = 120
    }
}
