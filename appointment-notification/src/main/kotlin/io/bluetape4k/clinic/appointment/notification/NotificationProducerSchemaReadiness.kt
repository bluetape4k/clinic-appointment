package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion

/**
 * 취소 notification schema v2 producer를 켤 수 있는 공통 readiness gate입니다.
 *
 * flag가 꺼진 상태는 정상적인 v1 운영으로 간주합니다. flag가 켜졌는데 schema 또는
 * 활성 channel의 cancellation template이 준비되지 않았으면 v1로 fail-closed 합니다.
 */
class NotificationProducerSchemaReadiness(
    private val properties: NotificationProperties,
    private val schemaReadiness: NotificationSchemaReadiness?,
    private val templateCatalog: NotificationTemplateCatalog = BuiltInWaitlistNotificationTemplateCatalog,
) {
    /** 현재 producer 설정과 schema/template 준비 상태를 평가합니다. */
    fun check(): NotificationReadiness {
        if (!properties.v2Producer) return NotificationReadiness.up()

        val schema = schemaReadiness?.check()
            ?: return NotificationReadiness.down("notification schema readiness bean is missing")
        if (!schema.available) return schema

        val activeChannels = NotificationChannelType.entries.filter { channel ->
            properties.worker.channels.containsKey(channel.name.lowercase())
        }
        if (activeChannels.isEmpty()) {
            return NotificationReadiness.down("notification worker has no configured channel")
        }
        val missingChannels = activeChannels.filter { channel ->
            !isRenderableCancellationTemplate(
                template = templateCatalog.findLegacyAppointmentCancellation(channel),
                version = APPOINTMENT_CANCELLED_LEGACY_TEMPLATE_VERSION,
                channel = channel,
                requiredFields = REQUIRED_LEGACY_APPOINTMENT_CANCELLATION_FIELDS,
            ) || !isRenderableCancellationTemplate(
                template = templateCatalog.findAppointmentCancellation(channel),
                version = APPOINTMENT_CANCELLED_TEMPLATE_VERSION,
                channel = channel,
                requiredFields = REQUIRED_APPOINTMENT_CANCELLATION_FIELDS,
            )
        }
        if (missingChannels.isNotEmpty()) {
            return NotificationReadiness.down(
                "appointment cancellation template v1 or v2 is missing for ${missingChannels.joinToString()}",
            )
        }
        return NotificationReadiness.up()
    }

    /** v2 producer를 안전하게 활성화할 수 있는지 반환합니다. */
    fun allowsV2(): Boolean = properties.v2Producer && check().available

    private fun isRenderableCancellationTemplate(
        template: NotificationTemplate?,
        version: NotificationTemplateVersion,
        channel: NotificationChannelType,
        requiredFields: Set<String>,
    ): Boolean =
        template != null &&
            template.key == APPOINTMENT_CANCELLED_TEMPLATE_KEY &&
            template.version == version &&
            template.channel == channel &&
            requiredFields.all(template.fields::contains)
}

private val REQUIRED_LEGACY_APPOINTMENT_CANCELLATION_FIELDS = setOf(
    "clinicDisplayName",
    "appointmentDate",
    "startTime",
    "cancellationReasonCode",
)

private val REQUIRED_APPOINTMENT_CANCELLATION_FIELDS = REQUIRED_LEGACY_APPOINTMENT_CANCELLATION_FIELDS + setOf(
    "cancellationReasonDetail",
)
