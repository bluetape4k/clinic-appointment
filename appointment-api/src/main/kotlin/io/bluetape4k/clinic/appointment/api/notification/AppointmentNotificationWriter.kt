package io.bluetape4k.clinic.appointment.api.notification

import io.bluetape4k.clinic.appointment.event.notification.AppointmentCancelledParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentConfirmedParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentCreatedParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.AppointmentReminderParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentRescheduledParameters
import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.LegacySuppressionDraft
import io.bluetape4k.clinic.appointment.event.notification.NotificationAuditInput
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationContractException
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyInput
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEnvelope
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxHasher
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateParameters
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.event.notification.SendableNotificationDraft
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.support.requireNotNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 예약 command transaction 안에서 알림 outbox를 기록하는 port다.
 *
 * 구현은 자체 transaction을 열지 않는다. 호출자는 예약 저장, 상태 이력과 이 writer의
 * 작업을 같은 Exposed transaction에 배치해야 한다.
 */
interface AppointmentNotificationWriter {
    fun appointmentCreated(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        resolution: MemberResolution,
    )

    fun statusChanged(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        from: AppointmentState,
        to: AppointmentState,
    )

    fun cancelled(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        reasonCode: CancellationReasonCode?,
    )

    fun rescheduled(
        tenantGroupId: Long,
        original: AppointmentRecord,
        replacement: AppointmentRecord,
        version: Long,
    )
}

/**
 * typed parameter와 HMAC 식별자를 사용해 legacy 예약 알림을 기록한다.
 *
 * 병원 표시명과 시간대는 caller transaction 안에서 tenant 범위로 다시 조회한다.
 * 회원 이름, 전화번호와 자유 입력 취소 사유는 durable payload에 넣지 않는다.
 */
class DefaultAppointmentNotificationWriter(
    private val repository: NotificationOutboxRepository,
    private val hasher: NotificationOutboxHasher,
    private val clinicRepository: ClinicRepository,
    private val clock: Clock,
    private val sameDayReminderLeadTime: Duration,
) : AppointmentNotificationWriter {

    init {
        require(!sameDayReminderLeadTime.isNegative && !sameDayReminderLeadTime.isZero) {
            "sameDayReminderLeadTime must be positive"
        }
    }

    override fun appointmentCreated(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        resolution: MemberResolution,
    ) {
        when (resolution) {
            is MemberResolution.Resolved -> {
                require(record.memberId == resolution.memberId) {
                    "resolved member must match the persisted appointment member"
                }
                val clinic = clinic(record, tenantGroupId)
                enqueue(
                    tenantGroupId = tenantGroupId,
                    record = record,
                    memberId = resolution.memberId,
                    version = version,
                    eventType = NotificationEventType.CREATED,
                    slot = NotificationSlot.CREATED,
                    templateKey = CREATED_TEMPLATE,
                    parameters = AppointmentCreatedParameters(
                        clinicDisplayName = clinic.displayName,
                        appointmentDate = record.appointmentDate,
                        startTime = record.startTime,
                    ),
                    availableAt = now(),
                )
            }

            MemberResolution.LegacyMissing -> suppressMissingMember(
                tenantGroupId = tenantGroupId,
                record = record,
                version = version,
            )
        }
    }

    override fun statusChanged(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        from: AppointmentState,
        to: AppointmentState,
    ) {
        if (from == to || to != AppointmentState.CONFIRMED) return
        val memberId = record.memberId
        if (memberId == null) {
            suppressMissingMember(
                tenantGroupId = tenantGroupId,
                record = record,
                version = version,
                eventType = NotificationEventType.CONFIRMED,
                slot = NotificationSlot.CONFIRMED,
            )
            suppressMissingMemberReminders(tenantGroupId, record, version)
            return
        }
        val clinic = clinic(record, tenantGroupId)
        val occurredAt = now()
        enqueue(
            tenantGroupId = tenantGroupId,
            record = record,
            memberId = memberId,
            version = version,
            eventType = NotificationEventType.CONFIRMED,
            slot = NotificationSlot.CONFIRMED,
            templateKey = CONFIRMED_TEMPLATE,
            parameters = AppointmentConfirmedParameters(
                clinicDisplayName = clinic.displayName,
                appointmentDate = record.appointmentDate,
                startTime = record.startTime,
            ),
            availableAt = occurredAt,
            occurredAt = occurredAt,
        )
        enqueueReminders(tenantGroupId, record, version, clinic, occurredAt)
    }

    override fun cancelled(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        reasonCode: CancellationReasonCode?,
    ) {
        repository.suppressOutstandingReminders(
            appointmentId = AppointmentId(record.id.requireNotNull("record.id")),
            suppressionReason = NotificationSuppressionReasonCode.APPOINTMENT_CHANGED,
        )
        val memberId = record.memberId
        if (memberId == null) {
            suppressMissingMember(
                tenantGroupId = tenantGroupId,
                record = record,
                version = version,
                eventType = NotificationEventType.CANCELLED,
                slot = NotificationSlot.CANCELLED,
            )
            return
        }
        val clinic = clinic(record, tenantGroupId)
        enqueue(
            tenantGroupId = tenantGroupId,
            record = record,
            memberId = memberId,
            version = version,
            eventType = NotificationEventType.CANCELLED,
            slot = NotificationSlot.CANCELLED,
            templateKey = CANCELLED_TEMPLATE,
            parameters = AppointmentCancelledParameters(
                clinicDisplayName = clinic.displayName,
                appointmentDate = record.appointmentDate,
                startTime = record.startTime,
                cancellationReasonCode = reasonCode,
            ),
            availableAt = now(),
        )
    }

    override fun rescheduled(
        tenantGroupId: Long,
        original: AppointmentRecord,
        replacement: AppointmentRecord,
        version: Long,
    ) {
        require(original.memberId == replacement.memberId) {
            "rescheduled appointment must preserve the verified member"
        }
        repository.suppressOutstandingReminders(
            appointmentId = AppointmentId(original.id.requireNotNull("original.id")),
            suppressionReason = NotificationSuppressionReasonCode.APPOINTMENT_CHANGED,
        )
        val memberId = replacement.memberId
        if (memberId == null) {
            suppressMissingMember(
                tenantGroupId = tenantGroupId,
                record = original,
                version = version,
                eventType = NotificationEventType.RESCHEDULED,
                slot = NotificationSlot.RESCHEDULED,
            )
            suppressMissingMemberReminders(tenantGroupId, replacement, replacement.version)
            return
        }
        val clinic = clinic(replacement, tenantGroupId)
        val occurredAt = now()
        enqueue(
            tenantGroupId = tenantGroupId,
            record = original,
            memberId = memberId,
            version = version,
            eventType = NotificationEventType.RESCHEDULED,
            slot = NotificationSlot.RESCHEDULED,
            templateKey = RESCHEDULED_TEMPLATE,
            parameters = AppointmentRescheduledParameters(
                clinicDisplayName = clinic.displayName,
                previousAppointmentDate = original.appointmentDate,
                previousStartTime = original.startTime,
                replacementAppointmentDate = replacement.appointmentDate,
                replacementStartTime = replacement.startTime,
            ),
            availableAt = occurredAt,
            occurredAt = occurredAt,
        )
        enqueueReminders(
            tenantGroupId = tenantGroupId,
            record = replacement,
            version = replacement.version,
            clinic = clinic,
            occurredAt = occurredAt,
        )
    }

    private fun enqueueReminders(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        clinic: NotificationClinic,
        occurredAt: Instant,
    ) {
        val memberId = record.memberId ?: throw memberUnavailable()
        val appointmentStart = record.appointmentDate
            .atTime(record.startTime)
            .atZone(clinic.zoneId)
            .toInstant()
        val parameters = AppointmentReminderParameters(
            clinicDisplayName = clinic.displayName,
            appointmentDate = record.appointmentDate,
            startTime = record.startTime,
        )
        enqueue(
            tenantGroupId = tenantGroupId,
            record = record,
            memberId = memberId,
            version = version,
            eventType = NotificationEventType.REMINDER,
            slot = NotificationSlot.REMINDER_24H,
            templateKey = REMINDER_24H_TEMPLATE,
            parameters = parameters,
            availableAt = appointmentStart.minus(Duration.ofHours(24)).coerceAtLeast(occurredAt),
            occurredAt = occurredAt,
        )
        enqueue(
            tenantGroupId = tenantGroupId,
            record = record,
            memberId = memberId,
            version = version,
            eventType = NotificationEventType.REMINDER,
            slot = NotificationSlot.REMINDER_SAME_DAY,
            templateKey = REMINDER_SAME_DAY_TEMPLATE,
            parameters = parameters,
            availableAt = appointmentStart.minus(sameDayReminderLeadTime).coerceAtLeast(occurredAt),
            occurredAt = occurredAt,
        )
    }

    private fun suppressMissingMember(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        eventType: NotificationEventType = NotificationEventType.CREATED,
        slot: NotificationSlot = NotificationSlot.CREATED,
    ) {
        require(record.memberId == null) { "legacy suppression requires a missing appointment member" }
        val input = idempotencyInput(
            tenantGroupId = tenantGroupId,
            record = record,
            version = version,
            eventType = eventType,
            slot = slot,
        )
        val digest = hasher.idempotencyCandidates(input).first()
        val audit = auditFingerprint(tenantGroupId, record, eventType)
        repository.suppressLegacy(
            LegacySuppressionDraft(
                idempotencyDigest = digest,
                auditFingerprint = audit,
                tenantGroupId = TenantGroupId(tenantGroupId),
                clinicId = ClinicId(record.clinicId),
                eventId = NotificationEventId(digest.value),
                suppressionReason = NotificationSuppressionReasonCode.MEMBER_ID_MISSING_LEGACY,
                availableAt = now(),
            )
        )
    }

    private fun suppressMissingMemberReminders(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
    ) {
        suppressMissingMember(
            tenantGroupId = tenantGroupId,
            record = record,
            version = version,
            eventType = NotificationEventType.REMINDER,
            slot = NotificationSlot.REMINDER_24H,
        )
        suppressMissingMember(
            tenantGroupId = tenantGroupId,
            record = record,
            version = version,
            eventType = NotificationEventType.REMINDER,
            slot = NotificationSlot.REMINDER_SAME_DAY,
        )
    }

    private fun enqueue(
        tenantGroupId: Long,
        record: AppointmentRecord,
        memberId: io.bluetape4k.clinic.appointment.model.identity.MemberId,
        version: Long,
        eventType: NotificationEventType,
        slot: NotificationSlot,
        templateKey: NotificationTemplateKey,
        parameters: NotificationTemplateParameters,
        availableAt: Instant,
        occurredAt: Instant = now(),
    ) {
        val input = idempotencyInput(tenantGroupId, record, version, eventType, slot)
        val digest = hasher.idempotencyCandidates(input).first()
        repository.enqueue(
            SendableNotificationDraft(
                envelope = NotificationOutboxEnvelope(
                    schemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
                    eventId = NotificationEventId(digest.value),
                    idempotencyKey = NotificationIdempotencyKey(digest.value),
                    tenantGroupId = TenantGroupId(tenantGroupId),
                    clinicId = ClinicId(record.clinicId),
                    appointmentId = AppointmentId(record.id.requireNotNull("record.id")),
                    memberId = memberId,
                    channel = NotificationChannelType.DUMMY,
                    eventType = eventType,
                    notificationSlot = slot,
                    templateKey = templateKey,
                    templateVersion = NotificationTemplateVersion(1),
                    parameterType = parameters.parameterType,
                    parameters = parameters,
                    occurredAt = occurredAt,
                    availableAt = availableAt,
                ),
                idempotencyDigest = digest,
                auditFingerprint = auditFingerprint(tenantGroupId, record, eventType),
                providerKey = DUMMY_PROVIDER,
            )
        )
    }

    private fun idempotencyInput(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        eventType: NotificationEventType,
        slot: NotificationSlot,
    ): NotificationIdempotencyInput =
        NotificationIdempotencyInput(
            tenantGroupId = TenantGroupId(tenantGroupId),
            clinicId = ClinicId(record.clinicId),
            appointmentId = AppointmentId(record.id.requireNotNull("record.id")),
            appointmentVersionOrRevision = version,
            eventType = eventType,
            channel = NotificationChannelType.DUMMY,
            notificationSlot = slot,
        )

    private fun auditFingerprint(
        tenantGroupId: Long,
        record: AppointmentRecord,
        eventType: NotificationEventType,
    ) = hasher.auditFingerprint(
        NotificationAuditInput(
            tenantGroupId = TenantGroupId(tenantGroupId),
            stableSubject = record.id.requireNotNull("record.id").toString(),
            purpose = eventType.name,
        )
    )

    private fun clinic(record: AppointmentRecord, tenantGroupId: Long): NotificationClinic {
        val clinic = clinicRepository.findByIdAndTenant(record.clinicId, tenantGroupId)
            ?: throw IllegalStateException("Appointment clinic is not available in the tenant scope")
        return NotificationClinic(
            displayName = clinic.name,
            zoneId = ZoneId.of(clinic.timezone),
        )
    }

    private fun now(): Instant = Instant.now(clock)

    private fun memberUnavailable(): NotificationContractException =
        NotificationContractException(
            failureCode = NotificationFailureCode.MEMBER_DIRECTORY_UNAVAILABLE,
            message = "Verified appointment member is unavailable",
        )

    private data class NotificationClinic(
        val displayName: String,
        val zoneId: ZoneId,
    )

    companion object {
        private const val DUMMY_PROVIDER = "dummy"
        private val CREATED_TEMPLATE = NotificationTemplateKey("appointment-created")
        private val CONFIRMED_TEMPLATE = NotificationTemplateKey("appointment-confirmed")
        private val CANCELLED_TEMPLATE = NotificationTemplateKey("appointment-cancelled")
        private val RESCHEDULED_TEMPLATE = NotificationTemplateKey("appointment-rescheduled")
        private val REMINDER_24H_TEMPLATE = NotificationTemplateKey("appointment-reminder-24h")
        private val REMINDER_SAME_DAY_TEMPLATE = NotificationTemplateKey("appointment-reminder-same-day")
    }
}

/**
 * key registry가 준비되지 않은 배포에서 예약 command를 fail-closed로 막는다.
 */
object UnavailableAppointmentNotificationWriter : AppointmentNotificationWriter {
    override fun appointmentCreated(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        resolution: MemberResolution,
    ): Nothing = unavailable()

    override fun statusChanged(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        from: AppointmentState,
        to: AppointmentState,
    ) {
        if (from != to && to == AppointmentState.CONFIRMED) {
            unavailable()
        }
    }

    override fun cancelled(
        tenantGroupId: Long,
        record: AppointmentRecord,
        version: Long,
        reasonCode: CancellationReasonCode?,
    ): Nothing = unavailable()

    override fun rescheduled(
        tenantGroupId: Long,
        original: AppointmentRecord,
        replacement: AppointmentRecord,
        version: Long,
    ): Nothing = unavailable()

    private fun unavailable(): Nothing =
        throw NotificationContractException(
            failureCode = NotificationFailureCode.HMAC_KEY_UNAVAILABLE,
            message = "Notification enqueue key registry is unavailable",
        )
}

private fun Instant.coerceAtLeast(minimum: Instant): Instant =
    if (isBefore(minimum)) minimum else this
