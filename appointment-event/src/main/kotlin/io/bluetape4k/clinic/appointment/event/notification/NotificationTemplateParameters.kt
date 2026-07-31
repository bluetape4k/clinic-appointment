package io.bluetape4k.clinic.appointment.event.notification

import java.io.Serializable
import java.time.LocalDate
import java.time.LocalTime

/**
 * 알림 template parameter의 닫힌 type discriminator다.
 */
enum class NotificationParameterType {
    APPOINTMENT_CREATED,
    APPOINTMENT_CONFIRMED,
    APPOINTMENT_REMINDER,
    APPOINTMENT_CANCELLED,
    APPOINTMENT_RESCHEDULED,
}

/**
 * 취소 알림에 포함할 수 있는 등록된 취소 사유 code다.
 *
 * 병원별 등록 code를 그대로 보존하되 대문자 업무 code 형식만 허용한다. 자유 입력
 * 사유는 durable notification payload에 저장하지 않는다.
 */
@JvmInline
value class CancellationReasonCode(val value: String) : Serializable {
    init {
        require(REASON_CODE.matches(value)) {
            "value must be a registered uppercase cancellation reason code"
        }
    }

    companion object {
        private val REASON_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
        private const val serialVersionUID = 1L
    }
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
 * 신규 예약 알림 template parameter다.
 */
data class AppointmentCreatedParameters(
    val clinicDisplayName: String,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
) : NotificationTemplateParameters {

    init {
        validateDurableOpaqueString(clinicDisplayName, "clinicDisplayName", MAX_CLINIC_DISPLAY_NAME_LENGTH)
    }

    override val parameterType: NotificationParameterType = NotificationParameterType.APPOINTMENT_CREATED

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_CLINIC_DISPLAY_NAME_LENGTH = 120
    }
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

/**
 * 예약 리마인더 알림 template parameter다.
 *
 * 24시간 전, 당일 같은 리마인더 시점은 [NotificationSlot]이 표현하므로 parameter에
 * 별도 timing field를 넣지 않는다.
 */
data class AppointmentReminderParameters(
    val clinicDisplayName: String,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
) : NotificationTemplateParameters {

    init {
        validateDurableOpaqueString(clinicDisplayName, "clinicDisplayName", MAX_CLINIC_DISPLAY_NAME_LENGTH)
    }

    override val parameterType: NotificationParameterType = NotificationParameterType.APPOINTMENT_REMINDER

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_CLINIC_DISPLAY_NAME_LENGTH = 120
    }
}

/**
 * 예약 취소 알림 template parameter다.
 *
 * 취소 사유는 등록된 [CancellationReasonCode]만 허용하고, 자유 입력 문구는 포함하지
 * 않는다.
 */
data class AppointmentCancelledParameters(
    val clinicDisplayName: String,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
    val cancellationReasonCode: CancellationReasonCode?,
) : NotificationTemplateParameters {

    init {
        validateDurableOpaqueString(clinicDisplayName, "clinicDisplayName", MAX_CLINIC_DISPLAY_NAME_LENGTH)
    }

    override val parameterType: NotificationParameterType = NotificationParameterType.APPOINTMENT_CANCELLED

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_CLINIC_DISPLAY_NAME_LENGTH = 120
    }
}

/**
 * 예약 일정 변경 알림 template parameter다.
 */
data class AppointmentRescheduledParameters(
    val clinicDisplayName: String,
    val previousAppointmentDate: LocalDate,
    val previousStartTime: LocalTime,
    val replacementAppointmentDate: LocalDate,
    val replacementStartTime: LocalTime,
) : NotificationTemplateParameters {

    init {
        validateDurableOpaqueString(clinicDisplayName, "clinicDisplayName", MAX_CLINIC_DISPLAY_NAME_LENGTH)
    }

    override val parameterType: NotificationParameterType = NotificationParameterType.APPOINTMENT_RESCHEDULED

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_CLINIC_DISPLAY_NAME_LENGTH = 120
    }
}
