package io.bluetape4k.clinic.appointment.event.integration

import java.io.Serializable
import java.time.Instant

data class TrustedSchedulingEventEnvelope<T>(
    val eventId: String,
    val eventType: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val producer: String,
    val issuer: String,
    val audience: String,
    val keyId: String,
    val schemaVersion: Int,
    val correlationId: String,
    val payloadHash: String,
    val payload: T,
) : Serializable

data class UntrustedSchedulingEventEnvelope<T>(
    val eventId: String,
    val eventType: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val producer: String,
    val issuer: String,
    val audience: String,
    val keyId: String,
    val schemaVersion: Int,
    val correlationId: String,
    val payloadHash: String,
    val signature: String,
    val payload: T,
) : Serializable {
    fun trusted() = TrustedSchedulingEventEnvelope(
        eventId = eventId,
        eventType = eventType,
        occurredAt = occurredAt,
        receivedAt = receivedAt,
        producer = producer,
        issuer = issuer,
        audience = audience,
        keyId = keyId,
        schemaVersion = schemaVersion,
        correlationId = correlationId,
        payloadHash = payloadHash,
        payload = payload,
    )
}
