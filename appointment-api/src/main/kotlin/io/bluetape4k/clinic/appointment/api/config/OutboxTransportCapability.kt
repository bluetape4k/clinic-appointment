package io.bluetape4k.clinic.appointment.api.config

/**
 * Marker supplied only when a deployment can publish scheduling outbox rows.
 */
fun interface OutboxTransportCapability {
    fun isAvailable(): Boolean
}
