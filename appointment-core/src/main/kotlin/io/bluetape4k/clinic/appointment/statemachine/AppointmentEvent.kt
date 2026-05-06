package io.bluetape4k.clinic.appointment.statemachine

import java.io.Serializable

/**
 * 예약 상태 전이를 트리거하는 이벤트.
 */
sealed class AppointmentEvent : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    /** 예약 요청 (PENDING → REQUESTED) */
    data object Request : AppointmentEvent()

    /** 예약 확정 (REQUESTED → CONFIRMED) */
    data object Confirm : AppointmentEvent()

    /** 내원 확인 (CONFIRMED → CHECKED_IN) */
    data object CheckIn : AppointmentEvent()

    /** 진료 시작 (CHECKED_IN → IN_PROGRESS) */
    data object StartTreatment : AppointmentEvent()

    /** 진료 완료 (IN_PROGRESS → COMPLETED) */
    data object Complete : AppointmentEvent()

    /**
     * 취소 이벤트.
     *
     * @property reason 취소 사유
     */
    data class Cancel(
        val reason: String,
    ) : AppointmentEvent(), Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** 미내원 처리 (CONFIRMED → NO_SHOW) */
    data object MarkNoShow : AppointmentEvent()

    /** 재예약 (CONFIRMED → PENDING) */
    data object Reschedule : AppointmentEvent()

    /**
     * 재배정 요청 이벤트.
     *
     * @property reason 재배정 요청 사유
     */
    data class RequestReschedule(
        val reason: String,
    ) : AppointmentEvent(), Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** 재배정 확정 (PENDING_RESCHEDULE → RESCHEDULED) */
    data object ConfirmReschedule : AppointmentEvent()

    override fun toString(): String = this::class.simpleName ?: "Unknown"
}
