package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class NotificationOutboxCodecTest {

    private val codec = NotificationOutboxCodec()

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

        json.contains("patientName").shouldBeFalse()
        json.contains("patientPhone").shouldBeFalse()
        json.contains("memberName").shouldBeFalse()
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
