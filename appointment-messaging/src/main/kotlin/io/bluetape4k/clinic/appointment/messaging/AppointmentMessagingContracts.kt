package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.commitment.CancellationReasonCode
import io.bluetape4k.clinic.appointment.service.AppointmentCausationId
import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.bluetape4k.clinic.appointment.service.AppointmentCorrelationId
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** API command transaction에서 공개 가능한 messaging 장애 분류다. */
enum class AppointmentMessagingFailureCode {
    OUTBOX_PERSISTENCE_UNAVAILABLE,
}

/** aggregate/outbox transaction을 rollback시키고 privacy-safe 503으로 변환할 계약 예외다. */
class AppointmentMessagingContractException(
    val failureCode: AppointmentMessagingFailureCode,
    message: String = failureCode.name,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** appointment domain event의 닫힌 wire type이다. */
enum class AppointmentEventType(
    val wireName: String,
) {
    CREATED("AppointmentCreated"),
    STATUS_CHANGED("AppointmentStatusChanged"),
    CANCELLED("AppointmentCancelled"),
    RESCHEDULED("AppointmentRescheduled"),
    ;

    companion object {
        fun fromWireName(value: String): AppointmentEventType =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("Unsupported appointment event type")
    }
}

/** 서버가 발급한 opaque event id다. */
@JvmInline
value class AppointmentEventId(val value: String) : Serializable {
    init {
        require(value.isNotBlank() && value.length <= 128) { "eventId must be bounded" }
        require(value.none(Char::isISOControl)) { "eventId must not contain control characters" }
    }

    companion object {
        fun generate(): AppointmentEventId = AppointmentEventId(UUID.randomUUID().toString())
    }
}

/** outbox에 저장되는 승인된 Kafka topic이다. */
@JvmInline
value class AppointmentTopic(val value: String) : Serializable {
    init {
        require(TOPIC_PATTERN.matches(value)) { "topic must use bounded Kafka topic characters" }
    }

    companion object {
        private val TOPIC_PATTERN = Regex("^[A-Za-z0-9._-]{1,249}$")
    }
}

/** appointment aggregate의 canonical partition key다. */
@JvmInline
value class AppointmentPartitionKey(val value: String) : Serializable {
    init {
        require(value.length in 1..512) { "partitionKey must be bounded" }
        require(value.none { it.isISOControl() || it == '\r' || it == '\n' }) {
            "partitionKey must not contain control characters"
        }
        require(PARTITION_KEY_PATTERN.matches(value)) { "partitionKey is not canonical" }
    }

    companion object {
        private val PARTITION_KEY_PATTERN = Regex("^tenant-[1-9][0-9]*:CLINIC:clinic-[1-9][0-9]*:APPOINTMENT:apt-[1-9][0-9]*$")
    }
}

/** aggregate identity를 payload/metadata에 표현하는 양수 ID다. */
@JvmInline
value class AppointmentAggregateId(val value: Long) : Serializable {
    init {
        require(value > 0) { "aggregateId must be positive" }
    }
}

/**
 * messaging 모듈에서 사용하는 command lineage다.
 * `AppointmentCommandContext`를 명시적으로 변환하여 core가 messaging에 의존하지 않게 한다.
 */
data class AppointmentMessagingContext(
    val correlationId: AppointmentCorrelationId,
    val causationId: AppointmentCausationId,
) : Serializable {
    companion object {
        fun from(command: AppointmentCommandContext): AppointmentMessagingContext =
            AppointmentMessagingContext(command.correlationId, command.causationId)
    }
}

/** envelope payload의 닫힌 타입이다. 자유 입력 개인정보나 raw reason은 포함하지 않는다. */
sealed interface AppointmentEventPayload : Serializable {
    val eventType: AppointmentEventType
    fun asFields(): Map<String, Any?>
}

data class AppointmentCreatedPayload(
    val appointmentId: AppointmentAggregateId,
    val version: Long,
    val status: AppointmentState,
) : AppointmentEventPayload {
    init {
        require(version >= 0) { "version must not be negative" }
    }

    override val eventType: AppointmentEventType = AppointmentEventType.CREATED
    override fun asFields(): Map<String, Any?> = mapOf(
        "appointmentId" to appointmentId.value,
        "version" to version,
        "status" to status.name,
    )
}

data class AppointmentStatusChangedPayload(
    val appointmentId: AppointmentAggregateId,
    val version: Long,
    val fromState: AppointmentState,
    val toState: AppointmentState,
    val reasonCode: CancellationReasonCode? = null,
) : AppointmentEventPayload {
    init {
        require(version >= 0) { "version must not be negative" }
    }

    override val eventType: AppointmentEventType = AppointmentEventType.STATUS_CHANGED
    override fun asFields(): Map<String, Any?> = buildMap {
        put("appointmentId", appointmentId.value)
        put("version", version)
        put("fromState", fromState.name)
        put("toState", toState.name)
        reasonCode?.let { put("reasonCode", it.value) }
    }
}

data class AppointmentCancelledPayload(
    val appointmentId: AppointmentAggregateId,
    val version: Long,
    val reasonCode: CancellationReasonCode? = null,
) : AppointmentEventPayload {
    init {
        require(version >= 0) { "version must not be negative" }
    }

    override val eventType: AppointmentEventType = AppointmentEventType.CANCELLED
    override fun asFields(): Map<String, Any?> = buildMap {
        put("appointmentId", appointmentId.value)
        put("version", version)
        reasonCode?.let { put("reasonCode", it.value) }
    }
}

data class AppointmentRescheduledPayload(
    val originalAppointmentId: AppointmentAggregateId,
    val replacementAppointmentId: AppointmentAggregateId,
    val originalVersion: Long,
    val replacementVersion: Long,
) : AppointmentEventPayload {
    init {
        require(originalAppointmentId != replacementAppointmentId) { "replacement must be a distinct appointment" }
        require(originalVersion >= 0 && replacementVersion >= 0) { "versions must not be negative" }
    }

    override val eventType: AppointmentEventType = AppointmentEventType.RESCHEDULED
    override fun asFields(): Map<String, Any?> = mapOf(
        "originalAppointmentId" to originalAppointmentId.value,
        "replacementAppointmentId" to replacementAppointmentId.value,
        "originalVersion" to originalVersion,
        "replacementVersion" to replacementVersion,
    )
}

/** Kafka로 전달되는 immutable appointment envelope다. */
data class AppointmentEventEnvelope(
    val eventId: AppointmentEventId,
    val eventType: AppointmentEventType,
    val schemaVersion: Int,
    val occurredAt: Instant,
    val tenantGroupId: Long,
    val clinicId: Long,
    val aggregateType: String,
    val aggregateId: AppointmentAggregateId,
    val correlationId: AppointmentCorrelationId,
    val causationId: AppointmentCausationId,
    val payload: AppointmentEventPayload,
) : Serializable {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported appointment schemaVersion" }
        require(tenantGroupId > 0 && clinicId > 0) { "tenantGroupId and clinicId must be positive" }
        require(aggregateType == AGGREGATE_TYPE) { "aggregateType must be APPOINTMENT" }
        require(eventType == payload.eventType) { "eventType must match payload type" }
        require(payload.aggregateId() == aggregateId) { "aggregateId must match payload identity" }
    }

    private fun AppointmentEventPayload.aggregateId(): AppointmentAggregateId = when (this) {
        is AppointmentCreatedPayload -> appointmentId
        is AppointmentStatusChangedPayload -> appointmentId
        is AppointmentCancelledPayload -> appointmentId
        is AppointmentRescheduledPayload -> originalAppointmentId
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val AGGREGATE_TYPE = "APPOINTMENT"
    }
}
