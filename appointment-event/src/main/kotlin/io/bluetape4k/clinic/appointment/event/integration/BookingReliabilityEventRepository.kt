package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventRecord as CoreEventRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventType
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityResponsibility as CoreResponsibility
import io.bluetape4k.clinic.appointment.repository.BookingReliabilityIdempotencyConflictException
import io.bluetape4k.clinic.appointment.repository.BookingReliabilityRepository
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityEvents
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.Serializable
import java.time.Clock

data class BookingReliabilityEventRecord(
    val id: Long,
    val eventId: String,
    val sourceVersion: Long,
    val payloadHash: String,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 신뢰된 event를 core append-only 원장에 기록합니다.
 *
 * 이 adapter는 transaction을 열지 않습니다. ingress와 quarantine/rejection 저장소가
 * 같은 caller-owned Exposed transaction에서 원자적으로 커밋되도록 유지합니다.
 */
class BookingReliabilityEventRepository(
    private val clock: Clock = Clock.systemUTC(),
    private val coreRepository: BookingReliabilityRepository = BookingReliabilityRepository(),
) {

    fun recordAccepted(envelope: TrustedSchedulingEventEnvelope<BookingReliabilitySignalEvent>): Long {
        val payload = envelope.payload
        val memberId = MemberId(payload.memberId)
        val existing = findScoped(payload.tenantGroupId, payload.clinicId, memberId, payload.eventId, payload.sourceVersion)
        if (existing != null) {
            if (existing[BookingReliabilityEvents.eventHash] != envelope.payloadHash) {
                throw SchedulingTrustException("SOURCE_VERSION_HASH_CONFLICT")
            }
            return existing[BookingReliabilityEvents.id].value
        }

        val record = payload.toCoreRecord(envelope.payloadHash)
        try {
            coreRepository.recordEvent(
                tenantGroupId = payload.tenantGroupId,
                clinicId = payload.clinicId,
                record = record,
                correlationId = envelope.correlationId,
            )
        } catch (_: BookingReliabilityIdempotencyConflictException) {
            throw SchedulingTrustException("SOURCE_VERSION_HASH_CONFLICT")
        }
        return requireNotNull(findScoped(payload.tenantGroupId, payload.clinicId, memberId, payload.eventId, payload.sourceVersion)) {
            "accepted booking reliability event was not found after insert"
        }[BookingReliabilityEvents.id].value
    }

    fun findByEventId(eventId: String): BookingReliabilityEventRecord? =
        BookingReliabilityEvents
            .selectAll()
            .where { BookingReliabilityEvents.eventId eq eventId }
            .limit(1)
            .singleOrNull()
            ?.let {
                BookingReliabilityEventRecord(
                    id = it[BookingReliabilityEvents.id].value,
                    eventId = it[BookingReliabilityEvents.eventId],
                    sourceVersion = it[BookingReliabilityEvents.sourceVersion],
                    payloadHash = it[BookingReliabilityEvents.eventHash],
                )
            }

    private fun findScoped(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        eventId: String,
        sourceVersion: Long,
    ) = BookingReliabilityEvents
        .selectAll()
        .where {
            (BookingReliabilityEvents.tenantGroupId eq tenantGroupId) and
                (BookingReliabilityEvents.clinicId eq clinicId) and
                (BookingReliabilityEvents.memberId eq memberId.value) and
                (BookingReliabilityEvents.eventId eq eventId) and
                (BookingReliabilityEvents.sourceVersion eq sourceVersion)
        }
        .singleOrNull()

    private fun BookingReliabilitySignalEvent.toCoreRecord(eventHash: String): CoreEventRecord =
        CoreEventRecord(
            appointmentId = appointmentId,
            memberId = MemberId(memberId),
            eventType = when (signalType) {
                BookingReliabilitySignalType.NO_SHOW_RECORDED -> BookingReliabilityEventType.NO_SHOW
                BookingReliabilitySignalType.LATE_CANCELLATION_RECORDED -> BookingReliabilityEventType.CANCELLED
            },
            responsibility = when (responsibility) {
                BookingReliabilityResponsibility.PATIENT_RESPONSIBLE -> CoreResponsibility.PATIENT
                BookingReliabilityResponsibility.CLINIC_RESPONSIBLE -> CoreResponsibility.CLINIC
                BookingReliabilityResponsibility.OPERATIONAL_EXCEPTION ->
                    CoreResponsibility.OPERATIONAL_EXCEPTION
                BookingReliabilityResponsibility.DATA_CORRECTION -> CoreResponsibility.DATA_CORRECTION
                BookingReliabilityResponsibility.UNKNOWN -> CoreResponsibility.UNKNOWN
            },
            scheduledStartAt = scheduledStartAt,
            occurredAt = occurredAt,
            eventId = eventId,
            sourceVersion = sourceVersion,
            source = source,
            eventHash = eventHash,
        )
}
