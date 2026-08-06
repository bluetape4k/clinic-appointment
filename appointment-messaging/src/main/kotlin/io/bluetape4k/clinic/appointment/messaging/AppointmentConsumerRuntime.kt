package io.bluetape4k.clinic.appointment.messaging

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.support.Acknowledgment
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** consumer handler에 전달하는 검증 완료 envelope와 metadata context입니다. */
data class AppointmentConsumerContext(
    val identity: AppointmentConsumerIdentity,
    val provenance: AppointmentConsumerProvenance,
)

fun interface AppointmentConsumerHandler {
    fun handle(envelope: AppointmentEventEnvelope, context: AppointmentConsumerContext)
}

enum class AppointmentConsumerOutcome {
    PROCESSED,
    DUPLICATE,
    RETRYABLE,
    QUARANTINED,
}

/** handler가 broker redelivery를 요청하는 bounded failure입니다. */
class AppointmentConsumerRetryableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** decode/topic/schema 경계가 거부한 event입니다. 원문 payload를 예외 메시지에 포함하지 않습니다. */
class AppointmentConsumerInvalidEnvelopeException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Kafka record를 durable inbox lifecycle과 handler에 연결하는 순수 consumer runtime입니다. */
class AppointmentConsumerRuntime(
    private val codec: AppointmentEventEnvelopeCodec,
    private val inboxStore: AppointmentConsumerInboxStore,
    private val allowedTopics: Set<AppointmentTopic>,
    private val schemaRegistry: AppointmentSchemaRegistry = StaticAppointmentSchemaRegistry(),
) {
    init {
        require(allowedTopics.isNotEmpty()) { "allowedTopics must not be empty" }
    }

    fun consume(
        record: ConsumerRecord<String, String>,
        identity: AppointmentConsumerIdentity,
        handler: AppointmentConsumerHandler,
    ): AppointmentConsumerOutcome = consume(record, null, identity, handler)

    fun consume(
        record: ConsumerRecord<String, String>,
        acknowledgment: Acknowledgment?,
        identity: AppointmentConsumerIdentity,
        handler: AppointmentConsumerHandler,
    ): AppointmentConsumerOutcome {
        val topic = runCatching { AppointmentTopic(record.topic()) }
            .getOrElse { throw AppointmentConsumerInvalidEnvelopeException("consumer topic is invalid") }
        if (topic !in allowedTopics) {
            throw AppointmentConsumerInvalidEnvelopeException("consumer topic is not allow-listed")
        }
        val value = record.value()
            ?: throw AppointmentConsumerInvalidEnvelopeException("tombstone event is not supported")
        val envelope = try {
            codec.decode(value)
        } catch (failure: Exception) {
            throw AppointmentConsumerInvalidEnvelopeException("appointment event envelope was rejected", failure)
        }
        try {
            schemaRegistry.validate(envelope.schemaVersion)
        } catch (failure: Exception) {
            throw AppointmentConsumerInvalidEnvelopeException("appointment event schema was rejected", failure)
        }
        val provenance = AppointmentConsumerProvenance(
            topic = topic,
            partition = record.partition(),
            offset = record.offset(),
            schemaVersion = envelope.schemaVersion,
            tenantGroupId = envelope.tenantGroupId,
            clinicId = envelope.clinicId,
            payloadSha256 = sha256(value),
        )
        val keyMatches = record.key() == AppointmentPartitionKeyFactory.create(
            tenantGroupId = envelope.tenantGroupId,
            clinicId = envelope.clinicId,
            appointmentId = envelope.aggregateId.value,
        ).value
        when (val begin = inboxStore.begin(identity, envelope.eventId, provenance)) {
            is AppointmentConsumerBeginResult.Duplicate -> {
                acknowledgment?.acknowledge()
                return when (begin.status) {
                    AppointmentConsumerStatus.QUARANTINED -> AppointmentConsumerOutcome.QUARANTINED
                    else -> AppointmentConsumerOutcome.DUPLICATE
                }
            }

            is AppointmentConsumerBeginResult.Acquired -> Unit
        }
        if (!keyMatches) {
            inboxStore.quarantine(identity, envelope.eventId, AppointmentConsumerFailureCode.PARTITION_KEY_MISMATCH)
            acknowledgment?.acknowledge()
            return AppointmentConsumerOutcome.QUARANTINED
        }

        val context = AppointmentConsumerContext(identity, provenance)
        try {
            handler.handle(envelope, context)
            return if (inboxStore.markProcessed(identity, envelope.eventId)) {
                acknowledgment?.acknowledge()
                AppointmentConsumerOutcome.PROCESSED
            } else {
                acknowledgment?.acknowledge()
                AppointmentConsumerOutcome.DUPLICATE
            }
        } catch (failure: AppointmentConsumerRetryableException) {
            val status = inboxStore.markFailure(
                identity,
                envelope.eventId,
                AppointmentConsumerFailureCode.HANDLER_RETRYABLE,
            )
            if (status == AppointmentConsumerStatus.QUARANTINED) {
                acknowledgment?.acknowledge()
                return AppointmentConsumerOutcome.QUARANTINED
            }
            throw failure
        } catch (failure: Exception) {
            val status = inboxStore.markFailure(
                identity,
                envelope.eventId,
                AppointmentConsumerFailureCode.HANDLER_FAILED,
            )
            if (status == AppointmentConsumerStatus.QUARANTINED) {
                acknowledgment?.acknowledge()
                return AppointmentConsumerOutcome.QUARANTINED
            }
            throw failure
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
