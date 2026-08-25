package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.commitment.CancellationReasonRegistry
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
    fun `codec emits the stable notification envelope golden JSON`() {
        codec.encode(envelope()) shouldBeEqualTo
            """{"schemaVersion":2,"eventId":"event-1","idempotencyKey":"tenant-10:clinic-20:appointment-30:member-40:confirmed","tenantGroupId":10,"clinicId":20,"appointmentId":30,"memberId":"member-40","channel":"SMS","eventType":"CONFIRMED","notificationSlot":"CONFIRMED","templateKey":"appointment.confirmed.sms","templateVersion":1,"parameterType":"APPOINTMENT_CONFIRMED","parameters":{"clinicDisplayName":"Blue Clinic","appointmentDate":"2026-08-01","startTime":"10:30"},"occurredAt":"2026-07-31T01:00:00Z","availableAt":"2026-07-31T01:05:00Z"}"""
    }

    @Test
    fun `codec round trip preserves every appointment template parameter contract`() {
        val cases = listOf(
            envelope(
                eventType = NotificationEventType.CREATED,
                notificationSlot = NotificationSlot.CREATED,
                templateKey = "appointment.created.sms",
                parameterType = NotificationParameterType.APPOINTMENT_CREATED,
                parameters = AppointmentCreatedParameters(
                    clinicDisplayName = "Blue Clinic",
                    appointmentDate = LocalDate.parse("2026-08-01"),
                    startTime = LocalTime.parse("10:30"),
                ),
            ) to AppointmentCreatedParameters::class,
            envelope(
                eventType = NotificationEventType.REMINDER,
                notificationSlot = NotificationSlot.REMINDER_24H,
                templateKey = "appointment.reminder.sms",
                parameterType = NotificationParameterType.APPOINTMENT_REMINDER,
                parameters = AppointmentReminderParameters(
                    clinicDisplayName = "Blue Clinic",
                    appointmentDate = LocalDate.parse("2026-08-01"),
                    startTime = LocalTime.parse("10:30"),
                ),
            ) to AppointmentReminderParameters::class,
            envelope(
                eventType = NotificationEventType.CANCELLED,
                notificationSlot = NotificationSlot.CANCELLED,
                templateKey = "appointment.cancelled.sms",
                parameterType = NotificationParameterType.APPOINTMENT_CANCELLED,
                parameters = AppointmentCancelledParameters(
                    clinicDisplayName = "Blue Clinic",
                    appointmentDate = LocalDate.parse("2026-08-01"),
                    startTime = LocalTime.parse("10:30"),
                    cancellationReasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
                ),
            ) to AppointmentCancelledParameters::class,
            envelope(
                eventType = NotificationEventType.RESCHEDULED,
                notificationSlot = NotificationSlot.RESCHEDULED,
                templateKey = "appointment.rescheduled.sms",
                parameterType = NotificationParameterType.APPOINTMENT_RESCHEDULED,
                parameters = AppointmentRescheduledParameters(
                    clinicDisplayName = "Blue Clinic",
                    previousAppointmentDate = LocalDate.parse("2026-08-01"),
                    previousStartTime = LocalTime.parse("10:30"),
                    replacementAppointmentDate = LocalDate.parse("2026-08-02"),
                    replacementStartTime = LocalTime.parse("14:00"),
                ),
            ) to AppointmentRescheduledParameters::class,
        )

        cases.forEach { (envelope, expectedType) ->
            val decoded = codec.decode(codec.encode(envelope))

            decoded shouldBeEqualTo envelope
            expectedType.java.isInstance(decoded.parameters) shouldBeEqualTo true
        }
    }

    @Test
    fun `codec rejects unknown schema version with template parameter invalid contract failure`() {
        val json = codec.encode(envelope()).replace("\"schemaVersion\":2", "\"schemaVersion\":99")

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
    fun `codec rejects a known parameter with an incompatible event tuple`() {
        val json = codec.encode(envelope()).replace(
            "\"eventType\":\"CONFIRMED\"",
            "\"eventType\":\"CANCELLED\"",
        )

        val failure = assertFailsWith<NotificationContractException> {
            codec.decode(json)
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
    }

    @Test
    fun `codec rejects a reminder template key assigned to the other reminder slot`() {
        val valid = envelope(
            eventType = NotificationEventType.REMINDER,
            notificationSlot = NotificationSlot.REMINDER_24H,
            templateKey = "appointment-reminder-24h",
            parameterType = NotificationParameterType.APPOINTMENT_REMINDER,
            parameters = AppointmentReminderParameters(
                clinicDisplayName = "Blue Clinic",
                appointmentDate = LocalDate.parse("2026-08-01"),
                startTime = LocalTime.parse("10:30"),
            ),
        )
        val json = codec.encode(valid).replace(
            "\"templateKey\":\"appointment-reminder-24h\"",
            "\"templateKey\":\"appointment-reminder-same-day\"",
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
    fun `codec keeps cancellation parameters to registered reason code only`() {
        val json = codec.encode(
            envelope(
                eventType = NotificationEventType.CANCELLED,
                notificationSlot = NotificationSlot.CANCELLED,
                templateKey = "appointment.cancelled.sms",
                parameterType = NotificationParameterType.APPOINTMENT_CANCELLED,
                parameters = AppointmentCancelledParameters(
                    clinicDisplayName = "Blue Clinic",
                    appointmentDate = LocalDate.parse("2026-08-01"),
                    startTime = LocalTime.parse("10:30"),
                    cancellationReasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
                ),
            ),
        )

        val tree = jsonMapper.readValue<Map<String, Any?>>(json)
        @Suppress("UNCHECKED_CAST")
        val parameters = tree.getValue("parameters") as Map<String, Any?>

        parameters.keys shouldBeEqualTo setOf(
            "clinicDisplayName",
            "appointmentDate",
            "startTime",
            "cancellationReasonCode",
            "cancellationReasonDetail",
        )
        parameters.getValue("cancellationReasonCode") shouldBeEqualTo "CUSTOMER_REQUEST"
        json.contains("cancellationReasonText").shouldBeFalse()
        json.contains("reasonText").shouldBeFalse()
        json.contains("freeText").shouldBeFalse()
    }

    @Test
    fun `codec reads legacy cancellation payload without detail and round trips schema v2 detail`() {
        val legacy = codec.encode(
            envelope(
                eventType = NotificationEventType.CANCELLED,
                notificationSlot = NotificationSlot.CANCELLED,
                templateKey = "appointment.cancelled.sms",
                parameterType = NotificationParameterType.APPOINTMENT_CANCELLED,
                parameters = AppointmentCancelledParameters(
                    clinicDisplayName = "Blue Clinic",
                    appointmentDate = LocalDate.parse("2026-08-01"),
                    startTime = LocalTime.parse("10:30"),
                    cancellationReasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
                ),
            ),
        ).replace(",\"cancellationReasonDetail\":null", "")
            .replace("\"schemaVersion\":2", "\"schemaVersion\":1")
            .replace("\"templateVersion\":2", "\"templateVersion\":1")

        val legacyDecoded = codec.decode(legacy)
        (legacyDecoded.parameters as AppointmentCancelledParameters).cancellationReasonDetail shouldBeEqualTo null

        val current = envelope(
            eventType = NotificationEventType.CANCELLED,
            notificationSlot = NotificationSlot.CANCELLED,
            templateKey = "appointment.cancelled.sms",
            parameterType = NotificationParameterType.APPOINTMENT_CANCELLED,
            parameters = AppointmentCancelledParameters(
                clinicDisplayName = "Blue Clinic",
                appointmentDate = LocalDate.parse("2026-08-01"),
                startTime = LocalTime.parse("10:30"),
                cancellationReasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
                cancellationReasonDetail = CancellationReasonRegistry.CLINIC_SCHEDULE_CHANGED_DETAIL,
            ),
        )
        codec.decode(codec.encode(current)) shouldBeEqualTo current
    }

    @Test
    fun `codec rejects cancellation detail containing patient identifiers without payload leakage`() {
        val safe = codec.encode(
            envelope(
                eventType = NotificationEventType.CANCELLED,
                notificationSlot = NotificationSlot.CANCELLED,
                templateKey = "appointment.cancelled.sms",
                parameterType = NotificationParameterType.APPOINTMENT_CANCELLED,
                parameters = AppointmentCancelledParameters(
                    clinicDisplayName = "Blue Clinic",
                    appointmentDate = LocalDate.parse("2026-08-01"),
                    startTime = LocalTime.parse("10:30"),
                    cancellationReasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
                    cancellationReasonDetail = CancellationReasonRegistry.CLINIC_SCHEDULE_CHANGED_DETAIL,
                ),
            ),
        )
        val safeDetail = CancellationReasonRegistry.CLINIC_SCHEDULE_CHANGED_DETAIL
        val sensitive = "010-1234-5678"

        val failure = assertFailsWith<NotificationContractException> {
            codec.decode(safe.replace(safeDetail, sensitive))
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
        failure.message?.contains(sensitive).shouldBeFalse()
        failure.cause shouldBeEqualTo null
    }

    @Test
    fun `durable cancellation objects redact detail and nested parameters from toString`() {
        val detail = CancellationReasonRegistry.SCHEDULE_CHANGED_DETAIL
        val cancellation = AppointmentCancelledParameters(
            clinicDisplayName = "Blue Clinic",
            appointmentDate = LocalDate.parse("2026-08-01"),
            startTime = LocalTime.parse("10:30"),
            cancellationReasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
            cancellationReasonDetail = detail,
        )
        val envelope = envelope(
            eventType = NotificationEventType.CANCELLED,
            notificationSlot = NotificationSlot.CANCELLED,
            templateKey = "appointment.cancelled.sms",
            parameterType = NotificationParameterType.APPOINTMENT_CANCELLED,
            parameters = cancellation,
        )

        cancellation.toString().shouldNotContain(detail)
        envelope.toString().shouldNotContain(detail)
        envelope.toString().shouldNotContain("Blue Clinic")
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
    fun `codec rejects cancellation lowercase reason and parameter fields from another contract`() {
        val valid = codec.encode(
            envelope(
                eventType = NotificationEventType.CANCELLED,
                notificationSlot = NotificationSlot.CANCELLED,
                templateKey = "appointment.cancelled.sms",
                parameterType = NotificationParameterType.APPOINTMENT_CANCELLED,
                parameters = AppointmentCancelledParameters(
                    clinicDisplayName = "Blue Clinic",
                    appointmentDate = LocalDate.parse("2026-08-01"),
                    startTime = LocalTime.parse("10:30"),
                    cancellationReasonCode = CancellationReasonCode("CUSTOMER_REQUEST"),
                ),
            ),
        )

        val cases = listOf(
            valid.replace("\"cancellationReasonCode\":\"CUSTOMER_REQUEST\"", "\"cancellationReasonCode\":\"customer_request\""),
            valid.replace(
                "\"cancellationReasonCode\":\"CUSTOMER_REQUEST\"",
                "\"cancellationReasonCode\":\"CUSTOMER_REQUEST\",\"previousAppointmentDate\":\"2026-08-01\"",
            ),
        )

        cases.forEach { json ->
            val failure = assertFailsWith<NotificationContractException> {
                codec.decode(json)
            }

            failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
            failure.message?.contains("customer_request").shouldBeFalse()
            failure.message?.contains("previousAppointmentDate").shouldBeFalse()
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
    fun `codec rejects duplicate keys without accepting the last value`() {
        val valid = codec.encode(envelope())
        val duplicate = valid.replace(
            "\"eventId\":\"event-1\"",
            "\"eventId\":\"event-1\",\"eventId\":\"forged-event\"",
        )

        val failure = assertFailsWith<NotificationContractException> {
            codec.decode(duplicate)
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
        failure.message?.contains("forged-event").shouldBeFalse()
    }

    @Test
    fun `codec rejects trailing JSON tokens`() {
        val failure = assertFailsWith<NotificationContractException> {
            codec.decode(codec.encode(envelope()) + " {}")
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
        failure.cause shouldBeEqualTo null
    }

    @Test
    fun `codec rejects a string that exceeds the event document constraint`() {
        val oversized = codec.encode(envelope()).replace(
            "\"clinicDisplayName\":\"Blue Clinic\"",
            "\"clinicDisplayName\":\"${"x".repeat(4_097)}\"",
        )

        val failure = assertFailsWith<NotificationContractException> {
            codec.decode(oversized)
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
        failure.cause shouldBeEqualTo null
    }

    @Test
    fun `codec rejects an event document that exceeds the byte constraint`() {
        val oversized = codec.encode(envelope()).removeSuffix("}") + " ".repeat(64 * 1024) + "}"

        val failure = assertFailsWith<NotificationContractException> {
            codec.decode(oversized)
        }

        failure.failureCode shouldBeEqualTo NotificationFailureCode.TEMPLATE_PARAMETER_INVALID
        failure.cause shouldBeEqualTo null
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

    private fun envelope(
        eventType: NotificationEventType = NotificationEventType.CONFIRMED,
        notificationSlot: NotificationSlot = NotificationSlot.CONFIRMED,
        templateKey: String = "appointment.confirmed.sms",
        parameterType: NotificationParameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
        parameters: NotificationTemplateParameters = AppointmentConfirmedParameters(
            clinicDisplayName = "Blue Clinic",
            appointmentDate = LocalDate.parse("2026-08-01"),
            startTime = LocalTime.parse("10:30"),
        ),
    ): NotificationOutboxEnvelope =
        NotificationOutboxEnvelope(
            schemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
            eventId = NotificationEventId("event-1"),
            idempotencyKey = NotificationIdempotencyKey("tenant-10:clinic-20:appointment-30:member-40:confirmed"),
            tenantGroupId = TenantGroupId(10L),
            clinicId = ClinicId(20L),
            appointmentId = AppointmentId(30L),
            memberId = MemberId("member-40"),
            channel = NotificationChannelType.SMS,
            eventType = eventType,
            notificationSlot = notificationSlot,
            templateKey = NotificationTemplateKey(templateKey),
            templateVersion = NotificationTemplateVersion(
                if (eventType == NotificationEventType.CANCELLED) 2 else 1,
            ),
            parameterType = parameterType,
            parameters = parameters,
            occurredAt = Instant.parse("2026-07-31T01:00:00Z"),
            availableAt = Instant.parse("2026-07-31T01:05:00Z"),
        )
}
