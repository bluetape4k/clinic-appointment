package io.bluetape4k.clinic.appointment.event.reliability

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityEvents
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.event.integration.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class BookingReliabilityEventIngressTest {

    private val now = Instant.parse("2026-08-01T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var clinicId: Long = 0

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:booking_reliability_ingress_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                BookingReliabilityEvents,
                UntrustedSchedulingEventRejections,
                SchedulingQuarantineEvents,
                SchedulingQuarantineAuditEvents,
            )
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
                it[active] = true
            }
            clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Reliability Clinic"
            }.value
        }
    }

    @Test
    fun `trusted event stores only opaque member and bounded attribution`() {
        val event = signalEvent()
        val result = transaction { ingress(event).accept(envelope(event), VALID_RAW_PAYLOAD) }

        result as BookingReliabilityIngressResult.Accepted
        transaction {
            val row = BookingReliabilityEvents.selectAll().single()
            row[BookingReliabilityEvents.id].value shouldBeEqualTo result.eventRecordId
            row[BookingReliabilityEvents.memberId] shouldBeEqualTo "member-176"
            row[BookingReliabilityEvents.eventType].name shouldBeEqualTo "NO_SHOW"
            row[BookingReliabilityEvents.responsibility].name shouldBeEqualTo "PATIENT"
            row[BookingReliabilityEvents.eventHash] shouldBeEqualTo BookingReliabilitySignalPayloadHasher.hash(event)
            BookingReliabilityEvents.columns.map { it.name.lowercase() }
                .none { it in setOf("patient_name", "patient_phone", "raw_payload", "payload_json") }
                .shouldBeEqualTo(true)
        }
    }

    @Test
    fun `same event identity replay returns the accepted record`() {
        val event = signalEvent()
        val first = transaction { ingress(event).accept(envelope(event), VALID_RAW_PAYLOAD) }
            as BookingReliabilityIngressResult.Accepted
        val replay = transaction { ingress(event).accept(envelope(event), VALID_RAW_PAYLOAD) }
            as BookingReliabilityIngressResult.Accepted

        replay.eventRecordId shouldBeEqualTo first.eventRecordId
        transaction { BookingReliabilityEvents.selectAll().toList().shouldHaveSize(1) }
    }

    @Test
    fun `same event identity with a different bounded payload is quarantined`() {
        val first = signalEvent()
        transaction { ingress(first).accept(envelope(first), VALID_RAW_PAYLOAD) }
        val conflicting = first.copy(
            signalType = BookingReliabilitySignalType.LATE_CANCELLATION_RECORDED,
            scheduledStartAt = now.plusSeconds(600),
        )

        val result = transaction { ingress(conflicting).accept(envelope(conflicting), VALID_RAW_PAYLOAD) }

        result as BookingReliabilityIngressResult.Quarantined
        result.reasonCode shouldBeEqualTo "SOURCE_VERSION_HASH_CONFLICT"
        transaction {
            BookingReliabilityEvents.selectAll().toList().shouldHaveSize(1)
            UntrustedSchedulingEventRejections.selectAll().toList().shouldHaveSize(1)
            SchedulingQuarantineEvents.selectAll().toList().shouldHaveSize(1)
        }
    }

    @Test
    fun `envelope payload mismatch is quarantined instead of escaping validation`() {
        val event = signalEvent()
        val mismatched = envelope(event).copy(eventId = "envelope-event-2")

        val result = transaction { ingress(event).accept(mismatched, VALID_RAW_PAYLOAD) }

        result as BookingReliabilityIngressResult.Quarantined
        result.reasonCode shouldBeEqualTo "PAYLOAD_CONTRACT_INVALID"
        transaction {
            SchedulingQuarantineEvents.selectAll().toList().shouldHaveSize(1)
            UntrustedSchedulingEventRejections.selectAll().toList().shouldHaveSize(1)
        }
    }

    @Test
    fun `malformed decoder failure is quarantined`() {
        val event = signalEvent()
        val result = transaction {
            ingress(
                event,
                payloadDecoder = BookingReliabilitySignalEventDecoder {
                    throw IllegalArgumentException("malformed payload")
                },
            ).accept(envelope(event), VALID_RAW_PAYLOAD)
        }

        result as BookingReliabilityIngressResult.Quarantined
        result.reasonCode shouldBeEqualTo "BOOKING_RELIABILITY_MAPPING_FAILED"
        transaction {
            SchedulingQuarantineEvents.selectAll().toList().shouldHaveSize(1)
            UntrustedSchedulingEventRejections.selectAll().toList().shouldHaveSize(1)
        }
    }

    @Test
    fun `verification failure uses tolerant protection only`() {
        val event = signalEvent()
        val protector = RecordingQuarantineEnvelopeProtector()
        val mismatched = envelope(event).copy(eventId = "envelope-event-2")

        val result = transaction {
            ingress(event, quarantineEnvelopeProtector = protector).accept(mismatched, VALID_RAW_PAYLOAD)
        }

        result as BookingReliabilityIngressResult.Quarantined
        protector.protectCalls shouldBeEqualTo 0
        protector.protectUntrustedCalls shouldBeEqualTo 1
    }

    @Test
    fun `verified repository failure uses normal protection only`() {
        val first = signalEvent()
        transaction { ingress(first).accept(envelope(first), VALID_RAW_PAYLOAD) }
        val conflicting = first.copy(
            signalType = BookingReliabilitySignalType.LATE_CANCELLATION_RECORDED,
            scheduledStartAt = now.plusSeconds(600),
        )
        val protector = RecordingQuarantineEnvelopeProtector()

        val result = transaction {
            ingress(conflicting, quarantineEnvelopeProtector = protector)
                .accept(envelope(conflicting), VALID_RAW_PAYLOAD)
        }

        result as BookingReliabilityIngressResult.Quarantined
        result.reasonCode shouldBeEqualTo "SOURCE_VERSION_HASH_CONFLICT"
        protector.protectCalls shouldBeEqualTo 1
        protector.protectUntrustedCalls shouldBeEqualTo 0
    }

    @Test
    fun `invalid signature is quarantined before accepted insert`() {
        val event = signalEvent()
        val result = transaction {
            ingress(event).accept(envelope(event, signature = "invalid-signature"), VALID_RAW_PAYLOAD)
        }

        result as BookingReliabilityIngressResult.Quarantined
        result.reasonCode shouldBeEqualTo "SIGNATURE_INVALID"
        transaction {
            BookingReliabilityEvents.selectAll().toList().shouldHaveSize(0)
            SchedulingQuarantineEvents.selectAll().toList().shouldHaveSize(1)
        }
    }

    @Test
    fun `strict decoder rejects PII and unknown fields`() {
        val validJson = """
            {
              "sourceAuthority":"appointment-state-history",
              "sourceAggregateId":"attendance-aggregate-1",
              "sourceVersion":1,
              "tenantGroupId":1,
              "clinicId":$clinicId,
              "memberId":"member-176",
              "eventId":"reliability-event-1",
              "appointmentId":100,
              "signalType":"NO_SHOW_RECORDED",
              "responsibility":"PATIENT_RESPONSIBLE",
              "scheduledStartAt":"2026-08-01T03:10:00Z",
              "occurredAt":"2026-08-01T02:50:00Z",
              "source":"APPOINTMENT"
            }
        """.trimIndent().encodeToByteArray()

        StrictBookingReliabilitySignalEventDecoder().decode(validJson).memberId shouldBeEqualTo "member-176"
        assertFailsWith<IllegalArgumentException> {
            StrictBookingReliabilitySignalEventDecoder().decode(
                validJson.decodeToString()
                    .replace("\"sourceAuthority\"", "\"patientPhone\":\"010-1234-5678\",\"sourceAuthority\"")
                    .encodeToByteArray(),
            )
        }
    }

    private fun ingress(
        decoded: BookingReliabilitySignalEvent,
        payloadDecoder: BookingReliabilitySignalEventDecoder = BookingReliabilitySignalEventDecoder { decoded },
        quarantineEnvelopeProtector: QuarantineEnvelopeProtector = AesGcmQuarantineEnvelopeProtector(
            encryptionKey = ByteArray(32) { index -> index.toByte() },
            keyId = "quarantine-key-1",
        ),
    ): BookingReliabilityEventIngress =
        BookingReliabilityEventIngress(
            trustVerifier = SchedulingEventTrustVerifier(
                signatureVerifier = SchedulingEventSignatureVerifier { it.signature == "valid-signature" },
                allowedProducers = setOf("appointment-state-service"),
                allowedKeyIds = setOf("reliability-key"),
                allowedAlgorithms = setOf("EdDSA"),
                expectedIssuer = "clinic-platform",
                expectedAudience = "appointment-service",
                replayWindow = Duration.ofHours(1),
                clock = clock,
            ),
            payloadDecoder = payloadDecoder,
            eventRepository = BookingReliabilityEventRepository(clock),
            quarantineEnvelopeProtector = quarantineEnvelopeProtector,
            quarantineRepository = SchedulingQuarantineRepository(clock),
            rejectionRepository = UntrustedSchedulingEventRejectionRepository(),
            clock = clock,
        )

    private fun signalEvent(
        sourceVersion: Long = 1,
    ) = BookingReliabilitySignalEvent(
        sourceAuthority = "appointment-state-history",
        sourceAggregateId = "attendance-aggregate-1",
        sourceVersion = sourceVersion,
        tenantGroupId = 1,
        clinicId = clinicId,
        memberId = "member-176",
        eventId = "reliability-event-1",
        appointmentId = 100,
        signalType = BookingReliabilitySignalType.NO_SHOW_RECORDED,
        responsibility = BookingReliabilityResponsibility.PATIENT_RESPONSIBLE,
        scheduledStartAt = now.plusSeconds(600),
        occurredAt = now.minusSeconds(600),
    )

    private fun envelope(
        payload: BookingReliabilitySignalEvent,
        signature: String = "valid-signature",
    ) = UntrustedSchedulingEventEnvelope(
        eventId = payload.eventId,
        eventType = "BookingReliabilitySignalRecorded",
        occurredAt = payload.occurredAt,
        receivedAt = now,
        producer = "appointment-state-service",
        issuer = "clinic-platform",
        audience = "appointment-service",
        keyId = "reliability-key",
        algorithm = "EdDSA",
        schemaVersion = 1,
        correlationId = "correlation-1",
        payloadHash = BookingReliabilitySignalPayloadHasher.hash(payload),
        signature = signature,
        payload = payload,
    )

    private class RecordingQuarantineEnvelopeProtector : QuarantineEnvelopeProtector {
        private val delegate = AesGcmQuarantineEnvelopeProtector(
            encryptionKey = ByteArray(32) { index -> index.toByte() },
            keyId = "quarantine-key-1",
        )

        var protectCalls: Int = 0
            private set
        var protectUntrustedCalls: Int = 0
            private set

        override fun protect(
            envelope: UntrustedSchedulingEventEnvelope<*>,
        ): ProtectedQuarantineEnvelope {
            protectCalls++
            return delegate.protect(envelope)
        }

        override fun protectUntrusted(
            envelope: UntrustedSchedulingEventEnvelope<*>,
        ): ProtectedQuarantineEnvelope {
            protectUntrustedCalls++
            return delegate.protectUntrusted(envelope)
        }
    }

    private companion object {
        val VALID_RAW_PAYLOAD = "{}".encodeToByteArray()
    }
}
