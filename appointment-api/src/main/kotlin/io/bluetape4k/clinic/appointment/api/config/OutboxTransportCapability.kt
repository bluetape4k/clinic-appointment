package io.bluetape4k.clinic.appointment.api.config

/**
 * scheduling outbox row을 발행할 수 있는 배포 환경에서만 제공하는 표식입니다.
 */
fun interface OutboxTransportCapability {
    fun isAvailable(): Boolean
}
