package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.KotlinFeature
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * `NotificationOutboxEnvelope`를 저장 가능한 JSON 계약으로 변환한다.
 *
 * schema version과 parameter type은 closed allow-list로만 해석하며, 알 수 없는 값은
 * fallback 없이 `TEMPLATE_PARAMETER_INVALID` 계약 오류로 거부한다.
 */
class NotificationOutboxCodec {

    private val mapper = jsonMapper {
        addModule(
            kotlinModule {
                enable(KotlinFeature.StrictNullChecks)
            },
        )
        enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
    }

    fun encode(envelope: NotificationOutboxEnvelope): String =
        mapper.writeValueAsString(envelope.toJson())

    fun decode(json: String): NotificationOutboxEnvelope =
        try {
            decodeStrict(json)
        } catch (e: NotificationContractException) {
            throw e
        } catch (e: Exception) {
            throw invalidPayload()
        }

    private fun decodeStrict(json: String): NotificationOutboxEnvelope {
        val encoded = mapper.readValue<NotificationOutboxEnvelopeJson>(json)
        if (encoded.schemaVersion !in NotificationOutboxEnvelope.SUPPORTED_SCHEMA_VERSIONS) {
            throw invalidPayload()
        }

        val parameterType = encoded.parameterType.toParameterType()
        return encoded.toEnvelope(parameterType).also { envelope ->
            if (
                envelope.schemaVersion == NotificationOutboxEnvelope.LEGACY_SCHEMA_VERSION &&
                envelope.parameters is AppointmentCancelledParameters &&
                envelope.parameters.cancellationReasonDetail != null
            ) {
                throw invalidPayload()
            }
        }
    }

    private fun NotificationOutboxEnvelope.toJson(): NotificationOutboxEnvelopeJson =
        NotificationOutboxEnvelopeJson(
            schemaVersion = schemaVersion,
            eventId = eventId.value,
            idempotencyKey = idempotencyKey.value,
            tenantGroupId = tenantGroupId.value,
            clinicId = clinicId.value,
            appointmentId = appointmentId.value,
            memberId = memberId.value,
            channel = channel.name,
            eventType = eventType.name,
            notificationSlot = notificationSlot.name,
            templateKey = templateKey.value,
            templateVersion = templateVersion.value,
            parameterType = parameterType.name,
            parameters = when (val typed = parameters) {
                is AppointmentCreatedParameters -> NotificationParametersJson.AppointmentCreated(
                    clinicDisplayName = typed.clinicDisplayName,
                    appointmentDate = typed.appointmentDate.toString(),
                    startTime = typed.startTime.toString(),
                )
                is AppointmentConfirmedParameters -> NotificationParametersJson.AppointmentConfirmed(
                    clinicDisplayName = typed.clinicDisplayName,
                    appointmentDate = typed.appointmentDate.toString(),
                    startTime = typed.startTime.toString(),
                )
                is AppointmentReminderParameters -> NotificationParametersJson.AppointmentReminder(
                    clinicDisplayName = typed.clinicDisplayName,
                    appointmentDate = typed.appointmentDate.toString(),
                    startTime = typed.startTime.toString(),
                )
                is AppointmentCancelledParameters -> NotificationParametersJson.AppointmentCancelled(
                    clinicDisplayName = typed.clinicDisplayName,
                    appointmentDate = typed.appointmentDate.toString(),
                    startTime = typed.startTime.toString(),
                    cancellationReasonCode = typed.cancellationReasonCode?.value,
                    cancellationReasonDetail = typed.cancellationReasonDetail,
                )
                is AppointmentRescheduledParameters -> NotificationParametersJson.AppointmentRescheduled(
                    clinicDisplayName = typed.clinicDisplayName,
                    previousAppointmentDate = typed.previousAppointmentDate.toString(),
                    previousStartTime = typed.previousStartTime.toString(),
                    replacementAppointmentDate = typed.replacementAppointmentDate.toString(),
                    replacementStartTime = typed.replacementStartTime.toString(),
                )
            },
            occurredAt = occurredAt.toString(),
            availableAt = availableAt.toString(),
        )

    private fun NotificationOutboxEnvelopeJson.toEnvelope(
        parameterType: NotificationParameterType,
    ): NotificationOutboxEnvelope =
        NotificationOutboxEnvelope(
            schemaVersion = schemaVersion,
            eventId = NotificationEventId(eventId),
            idempotencyKey = NotificationIdempotencyKey(idempotencyKey),
            tenantGroupId = TenantGroupId(tenantGroupId),
            clinicId = ClinicId(clinicId),
            appointmentId = AppointmentId(appointmentId),
            memberId = MemberId(memberId),
            channel = NotificationChannelType.valueOf(channel),
            eventType = NotificationEventType.valueOf(eventType),
            notificationSlot = NotificationSlot.valueOf(notificationSlot),
            templateKey = NotificationTemplateKey(templateKey),
            templateVersion = NotificationTemplateVersion(templateVersion),
            parameterType = parameterType,
            parameters = parameters.toParameters(parameterType),
            occurredAt = Instant.parse(occurredAt),
            availableAt = Instant.parse(availableAt),
        )

    private fun String.toParameterType(): NotificationParameterType =
        try {
            NotificationParameterType.valueOf(this)
        } catch (e: IllegalArgumentException) {
            throw invalidPayload()
        }

    private fun Any.toParameters(
        parameterType: NotificationParameterType,
    ): NotificationTemplateParameters =
        when (parameterType) {
            NotificationParameterType.APPOINTMENT_CREATED -> decodeAs<NotificationParametersJson.AppointmentCreated>()
                .toParameters()
            NotificationParameterType.APPOINTMENT_CONFIRMED -> decodeAs<NotificationParametersJson.AppointmentConfirmed>()
                .toParameters()
            NotificationParameterType.APPOINTMENT_REMINDER -> decodeAs<NotificationParametersJson.AppointmentReminder>()
                .toParameters()
            NotificationParameterType.APPOINTMENT_CANCELLED -> decodeAs<NotificationParametersJson.AppointmentCancelled>()
                .toParameters()
            NotificationParameterType.APPOINTMENT_RESCHEDULED -> decodeAs<NotificationParametersJson.AppointmentRescheduled>()
                .toParameters()
        }

    private inline fun <reified T : Any> Any.decodeAs(): T =
        mapper.readValue(mapper.writeValueAsString(this))

    private fun invalidPayload(): NotificationContractException =
        NotificationContractException(
            failureCode = NotificationFailureCode.TEMPLATE_PARAMETER_INVALID,
            message = "Invalid notification outbox payload",
        )
}

private data class NotificationOutboxEnvelopeJson(
    val schemaVersion: Int,
    val eventId: String,
    val idempotencyKey: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val appointmentId: Long,
    val memberId: String,
    val channel: String,
    val eventType: String,
    val notificationSlot: String,
    val templateKey: String,
    val templateVersion: Int,
    val parameterType: String,
    val parameters: Any,
    val occurredAt: String,
    val availableAt: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private interface NotificationParametersJson : Serializable {

    data class AppointmentCreated(
        val clinicDisplayName: String,
        val appointmentDate: String,
        val startTime: String,
    ) : NotificationParametersJson {

        fun toParameters(): AppointmentCreatedParameters =
            AppointmentCreatedParameters(
                clinicDisplayName = clinicDisplayName,
                appointmentDate = LocalDate.parse(appointmentDate),
                startTime = LocalTime.parse(startTime),
            )

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class AppointmentConfirmed(
        val clinicDisplayName: String,
        val appointmentDate: String,
        val startTime: String,
    ) : NotificationParametersJson {

        fun toParameters(): AppointmentConfirmedParameters =
            AppointmentConfirmedParameters(
                clinicDisplayName = clinicDisplayName,
                appointmentDate = LocalDate.parse(appointmentDate),
                startTime = LocalTime.parse(startTime),
            )

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class AppointmentReminder(
        val clinicDisplayName: String,
        val appointmentDate: String,
        val startTime: String,
    ) : NotificationParametersJson {

        fun toParameters(): AppointmentReminderParameters =
            AppointmentReminderParameters(
                clinicDisplayName = clinicDisplayName,
                appointmentDate = LocalDate.parse(appointmentDate),
                startTime = LocalTime.parse(startTime),
            )

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class AppointmentCancelled(
        val clinicDisplayName: String,
        val appointmentDate: String,
        val startTime: String,
        val cancellationReasonCode: String?,
        val cancellationReasonDetail: String? = null,
    ) : NotificationParametersJson {

        fun toParameters(): AppointmentCancelledParameters =
            AppointmentCancelledParameters(
                clinicDisplayName = clinicDisplayName,
                appointmentDate = LocalDate.parse(appointmentDate),
                startTime = LocalTime.parse(startTime),
                cancellationReasonCode = cancellationReasonCode?.let(::CancellationReasonCode),
                cancellationReasonDetail = cancellationReasonDetail,
            )

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class AppointmentRescheduled(
        val clinicDisplayName: String,
        val previousAppointmentDate: String,
        val previousStartTime: String,
        val replacementAppointmentDate: String,
        val replacementStartTime: String,
    ) : NotificationParametersJson {

        fun toParameters(): AppointmentRescheduledParameters =
            AppointmentRescheduledParameters(
                clinicDisplayName = clinicDisplayName,
                previousAppointmentDate = LocalDate.parse(previousAppointmentDate),
                previousStartTime = LocalTime.parse(previousStartTime),
                replacementAppointmentDate = LocalDate.parse(replacementAppointmentDate),
                replacementStartTime = LocalTime.parse(replacementStartTime),
            )

        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
