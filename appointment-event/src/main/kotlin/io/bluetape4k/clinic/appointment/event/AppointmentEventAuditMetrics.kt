package io.bluetape4k.clinic.appointment.event

/**
 * Best-effort appointment event audit의 운영 관측 경계입니다.
 *
 * event 모듈은 notification metrics 구현에 의존하지 않으며, notification 모듈이
 * 이 포트를 선택적으로 구현할 수 있습니다.
 */
fun interface AppointmentEventAuditMetrics {
    fun recordEventLogWriteFailure(reasonCode: String)
}
