package io.bluetape4k.clinic.appointment.api.stats

import io.bluetape4k.clinic.appointment.messaging.AppointmentCancelledPayload
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerContext
import io.bluetape4k.clinic.appointment.messaging.AppointmentConsumerHandler
import io.bluetape4k.clinic.appointment.messaging.AppointmentCreatedPayload
import io.bluetape4k.clinic.appointment.messaging.AppointmentEventEnvelope
import io.bluetape4k.clinic.appointment.messaging.AppointmentRescheduledPayload
import io.bluetape4k.clinic.appointment.messaging.AppointmentStatusChangedPayload
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.ZoneOffset

/** appointment event의 최신 aggregate 상태를 tenant/date/status read model로 투영하는 통계 consumer입니다. */
class AppointmentStatsProjectionConsumer(
    private val database: Database,
    private val repository: AppointmentStatsProjectionRepository,
) : AppointmentConsumerHandler {
    override fun handle(envelope: AppointmentEventEnvelope, context: AppointmentConsumerContext) {
        check(context.provenance.tenantGroupId == envelope.tenantGroupId) { "consumer tenant scope mismatch" }
        check(context.provenance.clinicId == envelope.clinicId) { "consumer clinic scope mismatch" }
        val event = projectionEvent(envelope)
        transaction(database) {
            repository.upsert(
                tenantGroupId = envelope.tenantGroupId,
                clinicId = envelope.clinicId,
                eventDate = envelope.occurredAt.atZone(ZoneOffset.UTC).toLocalDate(),
                status = event.status,
                aggregateId = envelope.aggregateId.value.toString(),
                eventVersion = event.version,
                eventId = envelope.eventId.value,
            )
        }
    }

    private fun projectionEvent(envelope: AppointmentEventEnvelope): ProjectionEvent = when (val payload = envelope.payload) {
        is AppointmentCreatedPayload -> ProjectionEvent(payload.status, payload.version)
        is AppointmentStatusChangedPayload -> ProjectionEvent(payload.toState, payload.version)
        is AppointmentCancelledPayload -> ProjectionEvent(AppointmentState.CANCELLED, payload.version)
        is AppointmentRescheduledPayload ->
            ProjectionEvent(AppointmentState.RESCHEDULED, maxOf(payload.originalVersion, payload.replacementVersion))
    }

    private data class ProjectionEvent(
        val status: AppointmentState,
        val version: Long,
    )
}
