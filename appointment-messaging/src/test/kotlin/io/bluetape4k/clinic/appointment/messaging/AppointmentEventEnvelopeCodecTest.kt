package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.junit.jupiter.api.Test
import java.time.Instant

class AppointmentEventEnvelopeCodecTest {
    private val codec = AppointmentEventEnvelopeCodec()

    @Test
    fun `created envelope round trips without private payload data`() {
        val envelope = AppointmentEventEnvelope(
            eventId = AppointmentEventId("event-41"),
            eventType = AppointmentEventType.CREATED,
            schemaVersion = 1,
            occurredAt = Instant.parse("2026-08-05T08:30:00Z"),
            tenantGroupId = 7,
            clinicId = 31,
            aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
            aggregateId = AppointmentAggregateId(924),
            correlationId = AppointmentCommandContext.root("http-41").correlationId,
            causationId = AppointmentCommandContext.root("http-41").causationId,
            payload = AppointmentCreatedPayload(
                appointmentId = AppointmentAggregateId(924),
                version = 3,
                status = AppointmentState.CONFIRMED,
            ),
        )

        val json = codec.encode(envelope)
        json.contains("patient").shouldBeEqualTo(false)
        codec.decode(json) shouldBeEqualTo envelope
    }

    @Test
    fun `codec rejects unknown event type and payload fields`() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode(
                """{"eventId":"event-41","eventType":"Unknown","schemaVersion":1,"occurredAt":"2026-08-05T08:30:00Z","tenantGroupId":7,"clinicId":31,"aggregateType":"APPOINTMENT","aggregateId":924,"correlationId":"http-41","causationId":"http-41","payload":{}}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode(
                """{"eventId":"event-41","eventType":"AppointmentCreated","schemaVersion":1,"occurredAt":"2026-08-05T08:30:00Z","tenantGroupId":7,"clinicId":31,"aggregateType":"APPOINTMENT","aggregateId":924,"correlationId":"http-41","causationId":"http-41","payload":{"appointmentId":924,"version":3,"status":"CONFIRMED","patientName":"secret"}}""",
            )
        }
    }

    @Test
    fun `codec rejects trailing and duplicate json`() {
        val json = """{"eventId":"event-41","eventType":"AppointmentCreated","schemaVersion":1,"occurredAt":"2026-08-05T08:30:00Z","tenantGroupId":7,"clinicId":31,"aggregateType":"APPOINTMENT","aggregateId":924,"correlationId":"http-41","causationId":"http-41","payload":{"appointmentId":924,"version":3,"status":"CONFIRMED"}}"""
        assertFailsWith<IllegalArgumentException> { codec.decode("$json $json") }
        assertFailsWith<IllegalArgumentException> { codec.decode(json.replace("\"eventId\":\"event-41\"", "\"eventId\":\"event-41\",\"eventId\":\"event-42\"")) }
    }

    @Test
    fun `codec rejects fractional payload numbers`() {
        val json = """{"eventId":"event-41","eventType":"AppointmentCreated","schemaVersion":1,"occurredAt":"2026-08-05T08:30:00Z","tenantGroupId":7,"clinicId":31,"aggregateType":"APPOINTMENT","aggregateId":924,"correlationId":"http-41","causationId":"http-41","payload":{"appointmentId":924,"version":3.5,"status":"CONFIRMED"}}"""

        assertFailsWith<IllegalArgumentException> { codec.decode(json) }
    }

    @Test
    fun `codec rejects non string optional reason code`() {
        val json = """
            {"eventId":"event-41","eventType":"AppointmentStatusChanged","schemaVersion":1,"occurredAt":"2026-08-05T08:30:00Z","tenantGroupId":7,"clinicId":31,"aggregateType":"APPOINTMENT","aggregateId":924,"correlationId":"http-41","causationId":"http-41","payload":{"appointmentId":924,"version":3,"fromState":"PROPOSED","toState":"CONFIRMED","reasonCode":42}}
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> { codec.decode(json) }
    }

    @Test
    fun `codec rejects tombstones control characters and oversized strings`() {
        assertFailsWith<IllegalArgumentException> { codec.decode("") }
        assertFailsWith<IllegalArgumentException> { codec.decode("   ") }

        val controlCharacter = validCreatedEnvelopeJson(payload = """
            {"appointmentId":924,"version":3,"status":"CONFIRMED\u0001"}
        """.trimIndent())
        assertFailsWith<IllegalArgumentException> { codec.decode(controlCharacter) }

        val oversizedEventId = validCreatedEnvelopeJson(eventId = "e".repeat(4_097))
        assertFailsWith<IllegalArgumentException> { codec.decode(oversizedEventId) }
    }

    @Test
    fun `codec rejects nesting deeper than the parser contract`() {
        val nestedPayload = buildString {
            append('{')
            repeat(40) { append("\"nested\":{") }
            append('1')
            repeat(40) { append('}') }
            append('}')
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode(validCreatedEnvelopeJson(payload = nestedPayload))
        }
    }

    @Test
    fun `envelope rejects payload aggregate identity mismatch`() {
        assertFailsWith<IllegalArgumentException> {
            AppointmentEventEnvelope(
                eventId = AppointmentEventId("event-identity-mismatch"),
                eventType = AppointmentEventType.CREATED,
                schemaVersion = 1,
                occurredAt = Instant.parse("2026-08-05T08:30:00Z"),
                tenantGroupId = 7,
                clinicId = 31,
                aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
                aggregateId = AppointmentAggregateId(924),
                correlationId = AppointmentCommandContext.root("http-41").correlationId,
                causationId = AppointmentCommandContext.root("http-41").causationId,
                payload = AppointmentCreatedPayload(
                    appointmentId = AppointmentAggregateId(925),
                    version = 3,
                    status = AppointmentState.CONFIRMED,
                ),
            )
        }
    }

    private fun validCreatedEnvelopeJson(
        eventId: String = "event-41",
        payload: String = """{"appointmentId":924,"version":3,"status":"CONFIRMED"}""",
    ): String = """
        {"eventId":"$eventId","eventType":"AppointmentCreated","schemaVersion":1,"occurredAt":"2026-08-05T08:30:00Z","tenantGroupId":7,"clinicId":31,"aggregateType":"APPOINTMENT","aggregateId":924,"correlationId":"http-41","causationId":"http-41","payload":$payload}
    """.trimIndent()
}
