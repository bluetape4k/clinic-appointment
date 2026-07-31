package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class NotificationOutboxCodecTest {

    private val codec = NotificationOutboxCodec()
    private val jsonMapper = jacksonObjectMapper()

    @Test
    fun `codec round trip preserves a typed appointment confirmation envelope`() {
        val envelope = envelope()

        val json = codec.encode(envelope)
        val decoded = codec.decode(json)

        decoded shouldBeEqualTo envelope
        decoded.parameters.shouldBeInstanceOf<AppointmentConfirmedParameters>()
    }

    @Test
    fun `codec rejects unknown schema version with template parameter invalid contract failure`() {
        val json = codec.encode(envelope()).replace("\"schemaVersion\":1", "\"schemaVersion\":2")

        val failure = assertFailsWith<NotificationContractException> {
            codec.decode(json)
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
    }

    @Test
    fun `codec rejects unknown parameter type without fallback`() {
        val json = codec.encode(envelope()).replace(
            "\"parameterType\":\"APPOINTMENT_CONFIRMED\"",
            "\"parameterType\":\"UNKNOWN_PARAMETER_TYPE\"",
        )

        val failure = assertFailsWith<NotificationContractException> {
            codec.decode(json)
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
    }

    @Test
    fun `codec output never carries recipient profile data or rendered messages`() {
        val json = codec.encode(envelope())

        val tree = jsonMapper.readValue<Map<String, Any?>>(json)
        tree.keys shouldBeEqualTo setOf(
            "schemaVersion",
            "eventId",
            "idempotencyKey",
            "tenantGroupId",
            "clinicId",
            "appointmentId",
            "memberId",
            "channel",
            "eventType",
            "notificationSlot",
            "templateKey",
            "templateVersion",
            "parameterType",
            "parameters",
            "occurredAt",
            "availableAt",
        )

        @Suppress("UNCHECKED_CAST")
        val parameters = tree.getValue("parameters") as Map<String, Any?>
        parameters.keys shouldBeEqualTo setOf("clinicDisplayName", "appointmentDate", "startTime")

        json.contains("patientName").shouldBeFalse()
        json.contains("patientPhone").shouldBeFalse()
        json.contains("memberName").shouldBeFalse()
    }

    @Test
    fun `codec rejects closed boundary violations as template parameter invalid without raw payload leakage`() {
        val valid = codec.encode(envelope())
        val cases = listOf(
            valid.replace("\"channel\":\"SMS\"", "\"channel\":\"FAX\""),
            valid.replace("\"eventType\":\"CONFIRMED\"", "\"eventType\":\"UNKNOWN_EVENT\""),
            valid.replace("\"notificationSlot\":\"CONFIRMED\"", "\"notificationSlot\":\"UNKNOWN_SLOT\""),
            valid.replace("\"appointmentDate\":\"2026-08-01\"", "\"appointmentDate\":\"not-a-date\""),
            valid.replace("\"startTime\":\"10:30\"", "\"startTime\":\"not-a-time\""),
            valid.replace("\"eventId\":\"event-1\"", "\"eventId\":\"bad\\u0001id\""),
            valid.replace("\"eventId\":\"event-1\",", ""),
            valid.replace("\"eventId\":\"event-1\"", "\"eventId\":null"),
            "{",
        )

        cases.forEach { json ->
            val failure = assertFailsWith<NotificationContractException> {
                codec.decode(json)
            }

            failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
            failure.message?.contains("FAX").shouldBeFalse()
            failure.message?.contains("UNKNOWN_EVENT").shouldBeFalse()
            failure.message?.contains("not-a-date").shouldBeFalse()
            failure.message?.contains("bad").shouldBeFalse()
            failure.message?.contains(json).shouldBeFalse()
        }
    }

    @Test
    fun `malformed JSON decode failure has sanitized message and no retained parser cause`() {
        val failure = assertFailsWith<NotificationContractException> {
            codec.decode("""{"schemaVersion":1,"eventId":""")
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
        failure.message shouldBeEqualTo "Invalid notification outbox payload"
        failure.cause shouldBeEqualTo null
    }

    @Test
    fun `codec rejects top level and nested unknown fields`() {
        val valid = codec.encode(envelope())
        val topLevelUnknown = valid.replace("\"eventId\":\"event-1\"", "\"unexpected\":\"value\",\"eventId\":\"event-1\"")
        val nestedUnknown = valid.replace(
            "\"clinicDisplayName\":\"Blue Clinic\"",
            "\"unexpected\":\"value\",\"clinicDisplayName\":\"Blue Clinic\"",
        )

        listOf(topLevelUnknown, nestedUnknown).forEach { json ->
            val failure = assertFailsWith<NotificationContractException> {
                codec.decode(json)
            }

            failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
            failure.message?.contains("unexpected").shouldBeFalse()
            failure.message?.contains("value").shouldBeFalse()
        }
    }

    @Test
    fun `opaque durable strings and template parameters enforce bounded safe values`() {
        NotificationEventId("a".repeat(128)).value shouldBeEqualTo "a".repeat(128)
        NotificationIdempotencyKey("A".repeat(128)).value shouldBeEqualTo "A".repeat(128)
        NotificationTemplateKey("template.key:v1").value shouldBeEqualTo "template.key:v1"
        AppointmentConfirmedParameters(
            clinicDisplayName = "가".repeat(120),
            appointmentDate = LocalDate.parse("2026-08-01"),
            startTime = LocalTime.parse("10:30"),
        ).clinicDisplayName shouldBeEqualTo "가".repeat(120)

        assertFailsWith<IllegalArgumentException> { NotificationEventId("a".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { NotificationEventId("bad\u0001id") }
        assertFailsWith<IllegalArgumentException> { NotificationIdempotencyKey("a".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { NotificationIdempotencyKey("bad\u0001key") }
        assertFailsWith<IllegalArgumentException> { NotificationTemplateKey("a".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { NotificationTemplateKey("bad\u0001template") }
        assertFailsWith<IllegalArgumentException> {
            AppointmentConfirmedParameters(
                clinicDisplayName = "a".repeat(121),
                appointmentDate = LocalDate.parse("2026-08-01"),
                startTime = LocalTime.parse("10:30"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentConfirmedParameters(
                clinicDisplayName = "bad\u0001clinic",
                appointmentDate = LocalDate.parse("2026-08-01"),
                startTime = LocalTime.parse("10:30"),
            )
        }
    }

    private fun envelope(): NotificationOutboxEnvelope =
        NotificationOutboxEnvelope(
            schemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
            eventId = NotificationEventId("event-1"),
            idempotencyKey = NotificationIdempotencyKey("tenant-10:clinic-20:appointment-30:member-40:confirmed"),
            tenantGroupId = TenantGroupId(10L),
            clinicId = ClinicId(20L),
            appointmentId = AppointmentId(30L),
            memberId = MemberId("member-40"),
            channel = NotificationChannelType.SMS,
            eventType = NotificationEventType.CONFIRMED,
            notificationSlot = NotificationSlot.CONFIRMED,
            templateKey = NotificationTemplateKey("appointment.confirmed.sms"),
            templateVersion = NotificationTemplateVersion(3),
            parameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
            parameters = AppointmentConfirmedParameters(
                clinicDisplayName = "Blue Clinic",
                appointmentDate = LocalDate.parse("2026-08-01"),
                startTime = LocalTime.parse("10:30"),
            ),
            occurredAt = Instant.parse("2026-07-31T01:00:00Z"),
            availableAt = Instant.parse("2026-07-31T01:05:00Z"),
        )
}
