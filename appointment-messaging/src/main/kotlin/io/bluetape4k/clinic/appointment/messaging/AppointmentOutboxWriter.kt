package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insertAndGetId

/** 예약 aggregate mutation과 메시징 intent를 같은 Exposed transaction에 기록하는 port다. */
interface AppointmentOutboxWriter {
    fun created(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        context: AppointmentMessagingContext,
    )

    fun statusChanged(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        fromState: AppointmentState,
        context: AppointmentMessagingContext,
        reasonCode: CancellationReasonCode? = null,
    )

    fun cancelled(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        context: AppointmentMessagingContext,
        reasonCode: CancellationReasonCode? = null,
    )

    fun rescheduled(
        scope: TenantClinicScope,
        original: AppointmentRecord,
        replacement: AppointmentRecord,
        context: AppointmentMessagingContext,
    )
}

/**
 * 같은 caller transaction에서 appointment/clinic scope를 재증명하고 outbox row를 insert한다.
 * Kafka 또는 다른 외부 I/O는 이 클래스에 없다.
 */
class DefaultAppointmentOutboxWriter(
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
    private val stateHistoryRepository: AppointmentStateHistoryRepository = AppointmentStateHistoryRepository(),
    private val clinicRepository: ClinicRepository = ClinicRepository(),
    private val codec: AppointmentEventEnvelopeCodec = AppointmentEventEnvelopeCodec(),
    private val eventTopic: AppointmentTopic = AppointmentTopic(DEFAULT_TOPIC),
    private val databaseClock: AppointmentDatabaseClock = AppointmentDatabaseClock.current,
    private val eventIdFactory: () -> AppointmentEventId = AppointmentEventId::generate,
) : AppointmentOutboxWriter {

    override fun created(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        context: AppointmentMessagingContext,
    ) {
        val appointmentId = appointment.requireId()
        proveScope(scope, appointment)
        val payload = AppointmentCreatedPayload(
            appointmentId = AppointmentAggregateId(appointmentId),
            version = appointment.version,
            status = appointment.status,
        )
        insert(scope, appointmentId, context, AppointmentEventType.CREATED, payload)
    }

    override fun statusChanged(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        fromState: AppointmentState,
        context: AppointmentMessagingContext,
        reasonCode: CancellationReasonCode?,
    ) {
        val appointmentId = appointment.requireId()
        val toState = appointment.status
        require(appointment.clinicId == scope.clinicId) {
            "appointment does not belong to requested clinic"
        }
        val canonical = appointmentRepository.findByIdAndScope(appointmentId, scope)
            ?: throw IllegalArgumentException("appointment does not belong to requested scope")
        require(canonical.version == appointment.version) {
            "appointment version does not match canonical row"
        }
        require(canonical.status == toState) {
            "status event toState does not match canonical row"
        }
        require(fromState != toState) { "status event must change state" }
        val latestHistory = stateHistoryRepository.findLatestByAppointmentId(appointmentId)
            ?: throw IllegalArgumentException("status event history is required")
        require(latestHistory.fromState == fromState && latestHistory.toState == toState) {
            "status event does not match latest state history"
        }
        val payload = AppointmentStatusChangedPayload(
            appointmentId = AppointmentAggregateId(appointmentId),
            version = canonical.version,
            fromState = fromState,
            toState = toState,
            reasonCode = reasonCode,
        )
        insert(scope, appointmentId, context, AppointmentEventType.STATUS_CHANGED, payload)
    }

    override fun cancelled(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        context: AppointmentMessagingContext,
        reasonCode: CancellationReasonCode?,
    ) {
        val appointmentId = appointment.requireId()
        proveScope(scope, appointment)
        val payload = AppointmentCancelledPayload(
            appointmentId = AppointmentAggregateId(appointmentId),
            version = appointment.version,
            reasonCode = reasonCode,
        )
        insert(scope, appointmentId, context, AppointmentEventType.CANCELLED, payload)
    }

    override fun rescheduled(
        scope: TenantClinicScope,
        original: AppointmentRecord,
        replacement: AppointmentRecord,
        context: AppointmentMessagingContext,
    ) {
        val originalId = original.requireId()
        val replacementId = replacement.requireId()
        proveScope(scope, original)
        proveScope(scope, replacement)
        require(original.clinicId == scope.clinicId && replacement.clinicId == scope.clinicId) {
            "reschedule appointments must belong to the requested clinic"
        }
        val payload = AppointmentRescheduledPayload(
            originalAppointmentId = AppointmentAggregateId(originalId),
            replacementAppointmentId = AppointmentAggregateId(replacementId),
            originalVersion = original.version,
            replacementVersion = replacement.version,
        )
        insert(scope, originalId, context, AppointmentEventType.RESCHEDULED, payload)
    }

    private fun proveScope(scope: TenantClinicScope, appointmentId: Long) {
        require(clinicRepository.findByIdAndTenant(scope.clinicId, scope.tenantGroupId) != null) {
            "clinic does not belong to tenant scope"
        }
        require(appointmentRepository.findByIdAndScope(appointmentId, scope) != null) {
            "appointment does not belong to tenant scope"
        }
    }

    private fun proveScope(scope: TenantClinicScope, appointment: AppointmentRecord) {
        require(appointment.clinicId == scope.clinicId) {
            "appointment does not belong to requested clinic"
        }
        proveScope(scope, appointment.requireId())
    }

    private fun insert(
        scope: TenantClinicScope,
        aggregateId: Long,
        context: AppointmentMessagingContext,
        eventType: AppointmentEventType,
        payload: AppointmentEventPayload,
    ) {
        val occurredAt = databaseClock.now()
        val envelope = AppointmentEventEnvelope(
            eventId = eventIdFactory(),
            eventType = eventType,
            schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
            occurredAt = occurredAt,
            tenantGroupId = scope.tenantGroupId,
            clinicId = scope.clinicId,
            aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
            aggregateId = AppointmentAggregateId(aggregateId),
            correlationId = context.correlationId,
            causationId = context.causationId,
            payload = payload,
        )
        val partitionKey = AppointmentPartitionKeyFactory.create(
            tenantGroupId = scope.tenantGroupId,
            clinicId = scope.clinicId,
            appointmentId = aggregateId,
        )
        try {
            SchedulingOutboxEvents.insertAndGetId {
                it[SchedulingOutboxEvents.eventId] = envelope.eventId.value
                it[SchedulingOutboxEvents.causationEventId] = envelope.causationId.value
                it[SchedulingOutboxEvents.correlationId] = envelope.correlationId.value
                it[SchedulingOutboxEvents.eventType] = envelope.eventType.wireName
                it[SchedulingOutboxEvents.tenantGroupId] = scope.tenantGroupId
                it[SchedulingOutboxEvents.clinicId] = scope.clinicId
                it[SchedulingOutboxEvents.planId] = null
                it[SchedulingOutboxEvents.aggregateType] = envelope.aggregateType
                it[SchedulingOutboxEvents.aggregateId] = envelope.aggregateId.value.toString()
                it[SchedulingOutboxEvents.occurredAt] = occurredAt
                it[SchedulingOutboxEvents.topic] = eventTopic.value
                it[SchedulingOutboxEvents.partitionKey] = partitionKey.value
                it[SchedulingOutboxEvents.schemaVersion] = envelope.schemaVersion
                it[SchedulingOutboxEvents.payloadJson] = codec.encode(envelope)
                it[SchedulingOutboxEvents.status] = SchedulingOutboxStatus.PENDING
                it[SchedulingOutboxEvents.attemptCount] = 0
                it[SchedulingOutboxEvents.nextAttemptAt] = occurredAt
                it[SchedulingOutboxEvents.leaseOwner] = null
                it[SchedulingOutboxEvents.leaseToken] = null
                it[SchedulingOutboxEvents.leaseUntil] = null
                it[SchedulingOutboxEvents.lastFailureCode] = null
                it[SchedulingOutboxEvents.lastFailureAt] = null
            }
        } catch (failure: ExposedSQLException) {
            throw AppointmentMessagingContractException(
                failureCode = AppointmentMessagingFailureCode.OUTBOX_PERSISTENCE_UNAVAILABLE,
                cause = failure,
            )
        }
    }

    private fun AppointmentRecord.requireId(): Long =
        requireNotNull(id) { "appointment id is required after mutation" }

    companion object {
        const val DEFAULT_TOPIC = "clinic.appointment.events"
    }
}
