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
    private val metrics: AppointmentConsumerMetrics = NoopAppointmentConsumerMetrics,
) {
    init {
        require(allowedTopics.isNotEmpty()) { "allowedTopics must not be empty" }
    }

    fun consume(
        record: ConsumerRecord<String, String>,
        identity: AppointmentConsumerIdentity,
        handler: AppointmentConsumerHandler,
    ): AppointmentConsumerOutcome = consume(record, null, identity, handler, null)

    fun consume(
        record: ConsumerRecord<String, String>,
        acknowledgment: Acknowledgment?,
        identity: AppointmentConsumerIdentity,
        handler: AppointmentConsumerHandler,
    ): AppointmentConsumerOutcome = consume(record, acknowledgment, identity, handler, null)

    /** replay source가 decode한 envelope의 tenant/clinic 범위를 runtime 경계에서 재확인합니다. */
    fun consume(
        record: ConsumerRecord<String, String>,
        acknowledgment: Acknowledgment?,
        identity: AppointmentConsumerIdentity,
        handler: AppointmentConsumerHandler,
        expectedScope: AppointmentReplayScope?,
    ): AppointmentConsumerOutcome {
        val topic = runCatching { AppointmentTopic(record.topic()) }
            .getOrElse {
                return rejectBeforeDecode(record, acknowledgment, identity, AppointmentConsumerFailureCode.INVALID_ENVELOPE)
            }
        if (topic !in allowedTopics) {
            return rejectBeforeDecode(record, acknowledgment, identity, AppointmentConsumerFailureCode.INVALID_ENVELOPE)
        }
        val value = record.value()
            ?: return rejectBeforeDecode(record, acknowledgment, identity, AppointmentConsumerFailureCode.INVALID_ENVELOPE)
        val envelope = try {
            codec.decode(value)
        } catch (_: Exception) {
            return rejectBeforeDecode(record, acknowledgment, identity, AppointmentConsumerFailureCode.INVALID_ENVELOPE)
        }
        try {
            schemaRegistry.validate(envelope.schemaVersion)
        } catch (failure: AppointmentSchemaRegistryUnavailableException) {
            metrics.retry(AppointmentConsumerFailureCode.HANDLER_RETRYABLE)
            throw AppointmentConsumerRetryableException("appointment schema registry is unavailable", failure)
        } catch (_: Exception) {
            return rejectBeforeDecode(record, acknowledgment, identity, AppointmentConsumerFailureCode.UNSUPPORTED_SCHEMA)
        }
        if (expectedScope != null &&
            (envelope.tenantGroupId != expectedScope.tenantGroupId || envelope.clinicId != expectedScope.clinicId)
        ) {
            return rejectBeforeDecode(record, acknowledgment, identity, AppointmentConsumerFailureCode.SCOPE_MISMATCH)
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
        val acquisition = when (val begin = inboxStore.begin(
            identity = identity,
            eventId = envelope.eventId,
            provenance = provenance,
            allowQuarantinedReplay = expectedScope != null,
        )) {
            is AppointmentConsumerBeginResult.Duplicate -> {
                if (!begin.provenanceMatches) {
                    inboxStore.quarantineRejected(identity, record, AppointmentConsumerFailureCode.PROVENANCE_MISMATCH)
                    acknowledgment?.acknowledge()
                    metrics.quarantined(AppointmentConsumerFailureCode.PROVENANCE_MISMATCH)
                    return AppointmentConsumerOutcome.QUARANTINED
                }
                if (begin.status == AppointmentConsumerStatus.PROCESSING) {
                    metrics.retry(AppointmentConsumerFailureCode.HANDLER_RETRYABLE)
                    throw AppointmentConsumerRetryableException("appointment event is still being processed")
                }
                acknowledgment?.acknowledge()
                return when (begin.status) {
                    AppointmentConsumerStatus.QUARANTINED -> {
                        metrics.quarantined(AppointmentConsumerFailureCode.ATTEMPT_EXHAUSTED)
                        AppointmentConsumerOutcome.QUARANTINED
                    }
                    else -> {
                        metrics.duplicate()
                        AppointmentConsumerOutcome.DUPLICATE
                    }
                }
            }

            is AppointmentConsumerBeginResult.Acquired -> begin
        }
        if (!keyMatches) {
            inboxStore.quarantine(identity, envelope.eventId, AppointmentConsumerFailureCode.PARTITION_KEY_MISMATCH)
            acknowledgment?.acknowledge()
            metrics.quarantined(AppointmentConsumerFailureCode.PARTITION_KEY_MISMATCH)
            return AppointmentConsumerOutcome.QUARANTINED
        }

        val context = AppointmentConsumerContext(identity, provenance)
        try {
            handler.handle(envelope, context)
            return if (inboxStore.markProcessed(identity, envelope.eventId, acquisition.leaseUntil)) {
                acknowledgment?.acknowledge()
                metrics.processed()
                AppointmentConsumerOutcome.PROCESSED
            } else {
                metrics.retry(AppointmentConsumerFailureCode.LEASE_EXPIRED)
                throw AppointmentConsumerRetryableException("appointment inbox lease was fenced")
            }
        } catch (failure: AppointmentConsumerRetryableException) {
            val status = inboxStore.markFailure(
                identity,
                envelope.eventId,
                AppointmentConsumerFailureCode.HANDLER_RETRYABLE,
                acquisition.leaseUntil,
            )
            if (status == AppointmentConsumerStatus.QUARANTINED) {
                acknowledgment?.acknowledge()
                metrics.quarantined(AppointmentConsumerFailureCode.ATTEMPT_EXHAUSTED)
                return AppointmentConsumerOutcome.QUARANTINED
            }
            metrics.retry(AppointmentConsumerFailureCode.HANDLER_RETRYABLE)
            throw failure
        } catch (failure: Exception) {
            val status = inboxStore.markFailure(
                identity,
                envelope.eventId,
                AppointmentConsumerFailureCode.HANDLER_FAILED,
                acquisition.leaseUntil,
            )
            if (status == AppointmentConsumerStatus.QUARANTINED) {
                acknowledgment?.acknowledge()
                metrics.quarantined(AppointmentConsumerFailureCode.ATTEMPT_EXHAUSTED)
                return AppointmentConsumerOutcome.QUARANTINED
            }
            metrics.retry(AppointmentConsumerFailureCode.HANDLER_FAILED)
            throw failure
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun rejectBeforeDecode(
        record: ConsumerRecord<String, String>,
        acknowledgment: Acknowledgment?,
        identity: AppointmentConsumerIdentity,
        failureCode: AppointmentConsumerFailureCode,
    ): AppointmentConsumerOutcome {
        inboxStore.quarantineRejected(identity, record, failureCode)
        acknowledgment?.acknowledge()
        metrics.quarantined(failureCode)
        return AppointmentConsumerOutcome.QUARANTINED
    }
}
