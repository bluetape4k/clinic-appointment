package io.bluetape4k.clinic.appointment.event.notification

/**
 * notification envelope의 event/slot/template/parameter 조합을 닫힌 계약으로 검증합니다.
 *
 * JSON discriminator와 DB row metadata가 서로 다른 조합을 가리키면 renderer나
 * provider 단계까지 진행하지 않고 계약 오류로 재시도합니다.
 */
object NotificationOutboxContractRegistry {

    /**
     * envelope 내부 discriminator와 typed parameter의 event 계약을 검증합니다.
     *
     * @param envelope 검증할 notification envelope입니다.
     * @throws NotificationContractException event, slot, template, parameter 조합이 유효하지 않을 때.
     */
    fun validate(envelope: NotificationOutboxEnvelope) {
        val expectedParameterType = when (envelope.eventType) {
            NotificationEventType.CREATED -> NotificationParameterType.APPOINTMENT_CREATED
            NotificationEventType.CONFIRMED -> NotificationParameterType.APPOINTMENT_CONFIRMED
            NotificationEventType.CANCELLED -> NotificationParameterType.APPOINTMENT_CANCELLED
            NotificationEventType.RESCHEDULED -> NotificationParameterType.APPOINTMENT_RESCHEDULED
            NotificationEventType.REMINDER -> NotificationParameterType.APPOINTMENT_REMINDER
        }
        if (envelope.parameterType != expectedParameterType || envelope.parameters.parameterType != expectedParameterType) {
            throw invalidContract()
        }

        val allowedSlots = when (envelope.eventType) {
            NotificationEventType.REMINDER -> setOf(NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY)
            NotificationEventType.CREATED -> setOf(NotificationSlot.CREATED)
            NotificationEventType.CONFIRMED -> setOf(NotificationSlot.CONFIRMED)
            NotificationEventType.CANCELLED -> setOf(NotificationSlot.CANCELLED)
            NotificationEventType.RESCHEDULED -> setOf(NotificationSlot.RESCHEDULED)
        }
        if (envelope.notificationSlot !in allowedSlots) throw invalidContract()

        val templateKey = envelope.templateKey.value
        if (templateKey !in allowedTemplateKeys(envelope.eventType)) throw invalidContract()
        if (envelope.notificationSlot !in allowedSlotsForTemplateKey(envelope.eventType, templateKey)) {
            throw invalidContract()
        }

        val isCancellation = envelope.eventType == NotificationEventType.CANCELLED
        val expectedTemplateVersion = if (isCancellation && envelope.schemaVersion == NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION) {
            2
        } else {
            1
        }
        if (envelope.templateVersion.value != expectedTemplateVersion) throw invalidContract()

        if (envelope.schemaVersion == NotificationOutboxEnvelope.LEGACY_SCHEMA_VERSION &&
            envelope.parameters is AppointmentCancelledParameters &&
            envelope.parameters.cancellationReasonDetail != null
        ) {
            throw invalidContract()
        }
    }

    /**
     * outbox row metadata가 envelope의 canonical 계약과 일치하는지 검증합니다.
     *
     * @param envelope codec에서 복원한 envelope입니다.
     * @param channel 저장된 notification channel입니다.
     * @param eventType 저장된 event type입니다.
     * @param notificationSlot 저장된 notification slot입니다.
     * @param templateKey 저장된 template key입니다.
     * @param templateVersion 저장된 template version입니다.
     * @param parameterType 저장된 parameter type입니다.
     * @throws NotificationContractException envelope와 row metadata가 불일치할 때.
     */
    fun validateStoredMetadata(
        envelope: NotificationOutboxEnvelope,
        channel: NotificationChannelType,
        eventType: NotificationEventType,
        notificationSlot: NotificationSlot,
        templateKey: NotificationTemplateKey,
        templateVersion: NotificationTemplateVersion,
        parameterType: NotificationParameterType,
    ) {
        validate(envelope)
        if (
            envelope.channel != channel ||
            envelope.eventType != eventType ||
            envelope.notificationSlot != notificationSlot ||
            envelope.templateKey != templateKey ||
            envelope.templateVersion != templateVersion ||
            envelope.parameterType != parameterType
        ) {
            throw invalidContract()
        }
    }

    private fun allowedTemplateKeys(eventType: NotificationEventType): Set<String> = when (eventType) {
        NotificationEventType.CREATED -> setOf("appointment-created", "appointment.created", "appointment.created.sms")
        NotificationEventType.CONFIRMED -> setOf("appointment-confirmed", "appointment.confirmed", "appointment.confirmed.sms")
        NotificationEventType.CANCELLED -> setOf("appointment-cancelled", "appointment.cancelled", "appointment.cancelled.sms")
        NotificationEventType.RESCHEDULED -> setOf("appointment-rescheduled", "appointment.rescheduled", "appointment.rescheduled.sms")
        NotificationEventType.REMINDER -> setOf("appointment-reminder-24h", "appointment-reminder-same-day", "appointment.reminder", "appointment.reminder.sms")
    }

    private fun allowedSlotsForTemplateKey(
        eventType: NotificationEventType,
        templateKey: String,
    ): Set<NotificationSlot> = when (eventType) {
        NotificationEventType.CREATED -> setOf(NotificationSlot.CREATED)
        NotificationEventType.CONFIRMED -> setOf(NotificationSlot.CONFIRMED)
        NotificationEventType.CANCELLED -> setOf(NotificationSlot.CANCELLED)
        NotificationEventType.RESCHEDULED -> setOf(NotificationSlot.RESCHEDULED)
        NotificationEventType.REMINDER -> when (templateKey) {
            "appointment-reminder-24h" -> setOf(NotificationSlot.REMINDER_24H)
            "appointment-reminder-same-day" -> setOf(NotificationSlot.REMINDER_SAME_DAY)
            "appointment.reminder", "appointment.reminder.sms" -> setOf(
                NotificationSlot.REMINDER_24H,
                NotificationSlot.REMINDER_SAME_DAY,
            )
            else -> emptySet()
        }
    }

    private fun invalidContract(): NotificationContractException =
        NotificationContractException(
            failureCode = NotificationFailureCode.TEMPLATE_PARAMETER_INVALID,
            message = "Invalid notification envelope contract",
        )
}
