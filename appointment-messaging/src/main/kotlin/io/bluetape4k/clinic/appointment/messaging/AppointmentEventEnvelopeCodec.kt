package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.commitment.CancellationReasonCode
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/**
 * appointment envelope의 strict Jackson3 codec이다.
 *
 * event type과 payload discriminator는 sealed mapping으로 고정하며, default typing이나
 * FQN header를 사용하지 않는다. payload는 privacy allow-list field만 저장한다.
 */
class AppointmentEventEnvelopeCodec {
    private val mapper: JsonMapper = DEFAULT_MAPPER

    fun encode(envelope: AppointmentEventEnvelope): String =
        mapper.writeValueAsString(envelope.toJson())

    fun decode(json: String): AppointmentEventEnvelope {
        require(json.isNotBlank()) { "appointment event envelope must not be blank or tombstone" }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_VALUE_BYTES) {
            "appointment event value exceeds $MAX_VALUE_BYTES bytes"
        }
        return try {
            mapper.readValue<EnvelopeJson>(json).toEnvelope()
        } catch (failure: Exception) {
            throw IllegalArgumentException("appointment event envelope is invalid", failure)
        }
    }

    private fun AppointmentEventEnvelope.toJson(): EnvelopeJson = EnvelopeJson(
        eventId = eventId.value,
        eventType = eventType.wireName,
        schemaVersion = schemaVersion,
        occurredAt = occurredAt.toString(),
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        aggregateType = aggregateType,
        aggregateId = aggregateId.value,
        correlationId = correlationId.value,
        causationId = causationId.value,
        payload = payload.asFields(),
    )

    private fun EnvelopeJson.toEnvelope(): AppointmentEventEnvelope {
        val type = AppointmentEventType.fromWireName(eventType)
        require(aggregateType == AppointmentEventEnvelope.AGGREGATE_TYPE) { "aggregateType is not allowed" }
        val payload = payload.toPayload(type)
        return AppointmentEventEnvelope(
            eventId = AppointmentEventId(eventId),
            eventType = type,
            schemaVersion = schemaVersion,
            occurredAt = Instant.parse(occurredAt),
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            aggregateType = aggregateType,
            aggregateId = AppointmentAggregateId(aggregateId),
            correlationId = io.bluetape4k.clinic.appointment.service.AppointmentCorrelationId(correlationId),
            causationId = io.bluetape4k.clinic.appointment.service.AppointmentCausationId(causationId),
            payload = payload,
        )
    }

    private fun Map<String, Any?>.toPayload(type: AppointmentEventType): AppointmentEventPayload {
        require(size <= MAX_COLLECTION_ENTRIES) { "payload contains too many fields" }
        forEach { (key, value) ->
            require(key.length <= MAX_STRING_CHARS) { "payload field name is too long" }
            validatePayloadValue(value)
        }
        val allowed = when (type) {
            AppointmentEventType.CREATED -> setOf("appointmentId", "version", "status")
            AppointmentEventType.STATUS_CHANGED -> setOf("appointmentId", "version", "fromState", "toState", "reasonCode")
            AppointmentEventType.CANCELLED -> setOf("appointmentId", "version", "reasonCode")
            AppointmentEventType.RESCHEDULED -> setOf(
                "originalAppointmentId",
                "replacementAppointmentId",
                "originalVersion",
                "replacementVersion",
            )
        }
        require(keys.all { it in allowed }) { "payload contains an unknown field" }
        fun requiredLong(name: String): Long = when (val value = this[name]) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is BigInteger -> value.longValueExact()
            is BigDecimal -> value.toBigIntegerExact().longValueExact()
            else -> throw IllegalArgumentException("payload field $name must be an integer")
        }
        fun requiredString(name: String): String = this[name] as? String
            ?: throw IllegalArgumentException("payload field $name is required")
        fun optionalReason(): CancellationReasonCode? =
            if (!containsKey("reasonCode")) {
                null
            } else {
                when (val value = this["reasonCode"]) {
                    null -> null
                    is String -> CancellationReasonCode(value)
                    else -> throw IllegalArgumentException("payload field reasonCode must be a string")
                }
            }

        return when (type) {
            AppointmentEventType.CREATED -> AppointmentCreatedPayload(
                appointmentId = AppointmentAggregateId(requiredLong("appointmentId")),
                version = requiredLong("version"),
                status = AppointmentState.fromName(requiredString("status")),
            )
            AppointmentEventType.STATUS_CHANGED -> AppointmentStatusChangedPayload(
                appointmentId = AppointmentAggregateId(requiredLong("appointmentId")),
                version = requiredLong("version"),
                fromState = AppointmentState.fromName(requiredString("fromState")),
                toState = AppointmentState.fromName(requiredString("toState")),
                reasonCode = optionalReason(),
            )
            AppointmentEventType.CANCELLED -> AppointmentCancelledPayload(
                appointmentId = AppointmentAggregateId(requiredLong("appointmentId")),
                version = requiredLong("version"),
                reasonCode = optionalReason(),
            )
            AppointmentEventType.RESCHEDULED -> AppointmentRescheduledPayload(
                originalAppointmentId = AppointmentAggregateId(requiredLong("originalAppointmentId")),
                replacementAppointmentId = AppointmentAggregateId(requiredLong("replacementAppointmentId")),
                originalVersion = requiredLong("originalVersion"),
                replacementVersion = requiredLong("replacementVersion"),
            )
        }
    }

    private fun validatePayloadValue(value: Any?) {
        when (value) {
            is String -> {
                require(value.length <= MAX_STRING_CHARS) { "payload string is too long" }
                require(value.none(Char::isISOControl)) { "payload string contains a control character" }
            }
            is Map<*, *> -> {
                require(value.size <= MAX_COLLECTION_ENTRIES) { "payload object is too large" }
                value.forEach { (key, nested) ->
                    require(key is String && key.length <= MAX_STRING_CHARS) {
                        "payload object field name is invalid"
                    }
                    validatePayloadValue(nested)
                }
            }
            is Collection<*> -> {
                require(value.size <= MAX_COLLECTION_ENTRIES) { "payload collection is too large" }
                value.forEach(::validatePayloadValue)
            }
        }
    }

    private data class EnvelopeJson(
        val eventId: String,
        val eventType: String,
        val schemaVersion: Int,
        val occurredAt: String,
        val tenantGroupId: Long,
        val clinicId: Long,
        val aggregateType: String,
        val aggregateId: Long,
        val correlationId: String,
        val causationId: String,
        val payload: Map<String, Any?>,
    )

    companion object {
        private const val MAX_VALUE_BYTES = 64 * 1024
        private const val MAX_STRING_CHARS = 4 * 1024
        private const val MAX_COLLECTION_ENTRIES = 128
        private val DEFAULT_MAPPER: JsonMapper = JsonMapper.builder(
            JsonFactory.builder()
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxNestingDepth(32)
                        .maxDocumentLength(MAX_VALUE_BYTES.toLong())
                        .maxStringLength(MAX_STRING_CHARS)
                        .maxNameLength(MAX_STRING_CHARS)
                        .build(),
                )
                .build(),
        )
            .addModule(KotlinModule.Builder().build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build()
    }
}
