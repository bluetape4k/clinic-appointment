package io.bluetape4k.clinic.appointment.event.notification

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * worker 발송을 기다리는 알림 전용 durable outbox 테이블이다.
 *
 * 이 테이블은 provider 호출에 필요한 수신자 원문, 렌더링 본문, key material을 저장하지
 * 않는다. 실제 수신 정보와 template rendering은 worker가 claim 이후 짧은 DB
 * transaction 밖에서 수행해야 한다.
 */
object NotificationOutboxEvents : LongIdTable("clinic_notification_outbox") {
    /** 발송 가능한 행과 발송하지 않는 legacy 억제 행을 분리한다. */
    val rowKind = enumerationByName<NotificationOutboxRowKind>("row_kind", 32)

    /** delivery lifecycle의 현재 상태다. */
    val status = enumerationByName<NotificationOutboxStatus>("status", 32)

    /** HMAC key rotation과 함께 관리되는 idempotency digest version이다. */
    val idempotencyKeyVersion = integer("idempotency_key_version")

    /** 원문 key가 아닌 HMAC digest 값이다. */
    val idempotencyKey = varchar("idempotency_key", 128)

    /** idempotency digest를 만든 HMAC key 식별자다. key material은 저장하지 않는다. */
    val idempotencyKeyId = varchar("idempotency_key_id", 128)

    /** 감사용 비식별 fingerprint version이다. */
    val auditFingerprintVersion = integer("audit_fingerprint_version")

    /** 감사용 비식별 fingerprint 값이다. */
    val auditFingerprint = varchar("audit_fingerprint", 128)

    /** 감사 fingerprint를 만든 HMAC key 식별자다. */
    val auditFingerprintKeyId = varchar("audit_fingerprint_key_id", 128)

    /** tenant ownership boundary다. */
    val tenantGroupId = long("tenant_group_id")

    /** clinic fairness와 routing boundary다. */
    val clinicId = long("clinic_id")

    /** durable event identity다. */
    val eventId = varchar("event_id", 128)

    /** 예약 aggregate ID다. legacy suppression에서는 null이어야 한다. */
    val appointmentId = long("appointment_id").nullable()

    /** 최신 수신 정보를 조회할 opaque member ID다. legacy suppression에서는 null이어야 한다. */
    val memberId = varchar("member_id", 255).nullable()

    /** 발송 channel이다. legacy suppression에서는 null이어야 한다. */
    val channel = enumerationByName<NotificationChannelType>("channel", 32).nullable()

    /** 예약 이벤트 분류다. legacy suppression에서는 null이어야 한다. */
    val eventType = enumerationByName<NotificationEventType>("event_type", 32).nullable()

    /** template/idempotency slot이다. legacy suppression에서는 null이어야 한다. */
    val notificationSlot = enumerationByName<NotificationSlot>("notification_slot", 32).nullable()

    /** provider routing key다. provider credential이나 recipient는 아니다. */
    val providerKey = varchar("provider_key", 128).nullable()

    /** template key다. legacy suppression에서는 null이어야 한다. */
    val templateKey = varchar("template_key", 128).nullable()

    /** template version이다. legacy suppression에서는 null이어야 한다. */
    val templateVersion = integer("template_version").nullable()

    /** parameter discriminator다. legacy suppression에서는 null이어야 한다. */
    val parameterType = enumerationByName<NotificationParameterType>("parameter_type", 64).nullable()

    /** privacy-safe template parameter JSON이다. legacy suppression에서는 null이어야 한다. */
    val parametersJson = text("parameters_json").nullable()

    /** legacy suppression의 종료 원인이다. sendable row에서는 null이다. */
    val suppressionReason = enumerationByName<NotificationSuppressionReasonCode>("suppression_reason", 64).nullable()

    /** terminal failure의 닫힌 code다. raw exception message는 저장하지 않는다. */
    val failureCode = enumerationByName<NotificationFailureCode>("failure_code", 64).nullable()

    /** provider가 검증해 반환한 낮은 cardinality message reference다. */
    val providerMessageReference = varchar("provider_message_reference", 128).nullable()

    /** 실제 수신자가 아닌 비식별 destination fingerprint다. */
    val destinationFingerprint = varchar("destination_fingerprint", 128).nullable()

    /** workflow correlation metadata다. */
    val correlationId = varchar("correlation_id", 128).nullable()

    /** distributed trace metadata다. */
    val traceId = varchar("trace_id", 128).nullable()

    /** 최초 발송 또는 retry가 가능해지는 UTC 시각이다. */
    val availableAt = timestamp("available_at")

    /** retry 대기 종료 UTC 시각이다. retry 상태가 아니면 null일 수 있다. */
    val nextRetryAt = timestamp("next_retry_at").nullable()

    /** claim된 worker owner다. 열려 있는 lease가 없으면 null이다. */
    val leaseOwner = varchar("lease_owner", 128).nullable()

    /** claim fencing token이다. key material이나 provider credential이 아니다. */
    val leaseToken = varchar("lease_token", 128).nullable()

    /** claim lease 만료 UTC 시각이다. */
    val leaseUntil = timestamp("lease_until").nullable()

    /** 현재 또는 마지막 delivery attempt 번호다. */
    val attemptNumber = integer("attempt_number").default(0)

    /** outbox 행 생성 UTC 시각이다. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    /** outbox 행 갱신 UTC 시각이다. */
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    /** terminal 상태 진입 UTC 시각이다. */
    val terminalAt = timestamp("terminal_at").nullable()

    init {
        uniqueIndex(
            NotificationOutboxIndexes.idempotency.name,
            idempotencyKeyVersion,
            idempotencyKey,
        )
        index(
            NotificationOutboxIndexes.readyClinicCursor.name,
            false,
            rowKind,
            tenantGroupId,
            clinicId,
            status,
            availableAt,
            nextRetryAt,
        )
        index(
            NotificationOutboxIndexes.readyWithinClinic.name,
            false,
            tenantGroupId,
            clinicId,
            rowKind,
            status,
            availableAt,
            id,
            nextRetryAt,
        )
        index(
            NotificationOutboxIndexes.directLookup.name,
            false,
            clinicId,
            appointmentId,
            eventType,
            rowKind,
            status,
            availableAt,
            nextRetryAt,
            id,
        )
        index(
            NotificationOutboxIndexes.tenantDirectLookup.name,
            false,
            tenantGroupId,
            clinicId,
            appointmentId,
            eventType,
            rowKind,
            status,
            availableAt,
            nextRetryAt,
            id,
        )
        index(
            NotificationOutboxIndexes.reminderSuppression.name,
            false,
            tenantGroupId,
            clinicId,
            appointmentId,
            rowKind,
            notificationSlot,
            status,
            id,
        )
        index(
            NotificationOutboxIndexes.leaseRecovery.name,
            false,
            rowKind,
            status,
            leaseUntil,
            id,
        )
        index(
            NotificationOutboxIndexes.terminalRetention.name,
            false,
            rowKind,
            status,
            terminalAt,
            id,
        )
        index(
            NotificationOutboxIndexes.pendingOldest.name,
            false,
            rowKind,
            status,
            availableAt,
            createdAt,
        )
    }
}

/** query contract와 migration index 정의를 함께 고정하는 metadata다. */
data class NotificationOutboxIndexContract(
    val name: String,
    val columns: List<String>,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** worker query가 의존하는 outbox index 이름과 column order다. */
object NotificationOutboxIndexes {
    val idempotency = NotificationOutboxIndexContract(
        name = "uk_notification_outbox_idempotency",
        columns = listOf("idempotency_key_version", "idempotency_key"),
    )
    val readyClinicCursor = NotificationOutboxIndexContract(
        name = "idx_notification_outbox_ready_clinic_cursor",
        columns = listOf("row_kind", "tenant_group_id", "clinic_id", "status", "available_at", "next_retry_at"),
    )
    val readyWithinClinic = NotificationOutboxIndexContract(
        name = "idx_notification_outbox_ready_within_clinic",
        columns = listOf("tenant_group_id", "clinic_id", "row_kind", "status", "available_at", "id", "next_retry_at"),
    )
    val directLookup = NotificationOutboxIndexContract(
        name = "idx_notification_outbox_direct_lookup",
        columns = listOf(
            "clinic_id",
            "appointment_id",
            "event_type",
            "row_kind",
            "status",
            "available_at",
            "next_retry_at",
            "id",
        ),
    )
    val tenantDirectLookup = NotificationOutboxIndexContract(
        name = "idx_notification_outbox_tenant_direct_lookup",
        columns = listOf(
            "tenant_group_id",
            "clinic_id",
            "appointment_id",
            "event_type",
            "row_kind",
            "status",
            "available_at",
            "next_retry_at",
            "id",
        ),
    )
    val reminderSuppression = NotificationOutboxIndexContract(
        name = "idx_notification_outbox_reminder_suppression",
        columns = listOf(
            "tenant_group_id",
            "clinic_id",
            "appointment_id",
            "row_kind",
            "notification_slot",
            "status",
            "id",
        ),
    )
    val leaseRecovery = NotificationOutboxIndexContract(
        name = "idx_notification_outbox_lease_recovery",
        columns = listOf("row_kind", "status", "lease_until", "id"),
    )
    val terminalRetention = NotificationOutboxIndexContract(
        name = "idx_notification_outbox_terminal_retention",
        columns = listOf("row_kind", "status", "terminal_at", "id"),
    )
    val pendingOldest = NotificationOutboxIndexContract(
        name = "idx_notification_outbox_pending_oldest",
        columns = listOf("row_kind", "status", "available_at", "created_at"),
    )

    fun names(): List<String> =
        listOf(
            idempotency.name,
            readyClinicCursor.name,
            readyWithinClinic.name,
            directLookup.name,
            tenantDirectLookup.name,
            reminderSuppression.name,
            leaseRecovery.name,
            terminalRetention.name,
            pendingOldest.name,
        )
}

/** repository query의 filter/order contract다. */
data class NotificationOutboxQueryContract(
    val name: String,
    val filters: List<String>,
    val orderBy: List<String>,
    val indexColumns: List<String>,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** migration 작성 전에 고정하는 worker query contract metadata다. */
object NotificationOutboxQueryContracts {
    val readyClinicCursor = NotificationOutboxQueryContract(
        name = "readyClinicCursor",
        filters = listOf("row_kind", "tenant_group_id", "clinic_id", "status", "available_at", "next_retry_at"),
        orderBy = listOf("tenant_group_id", "clinic_id"),
        indexColumns = NotificationOutboxIndexes.readyClinicCursor.columns,
    )
    val readyWithinClinic = NotificationOutboxQueryContract(
        name = "readyWithinClinic",
        filters = listOf("tenant_group_id", "clinic_id", "row_kind", "status", "available_at", "id", "next_retry_at"),
        orderBy = listOf("available_at", "id"),
        indexColumns = NotificationOutboxIndexes.readyWithinClinic.columns,
    )
    val directLookup = NotificationOutboxQueryContract(
        name = "directLookup",
        filters = listOf(
            "clinic_id",
            "appointment_id",
            "event_type",
            "row_kind",
            "status",
            "available_at",
            "next_retry_at",
        ),
        orderBy = listOf("available_at", "id"),
        indexColumns = NotificationOutboxIndexes.directLookup.columns,
    )
    val tenantDirectLookup = NotificationOutboxQueryContract(
        name = "tenantDirectLookup",
        filters = listOf(
            "tenant_group_id",
            "clinic_id",
            "appointment_id",
            "event_type",
            "row_kind",
            "status",
            "available_at",
            "next_retry_at",
        ),
        orderBy = listOf("available_at", "id"),
        indexColumns = NotificationOutboxIndexes.tenantDirectLookup.columns,
    )
    val reminderSuppression = NotificationOutboxQueryContract(
        name = "reminderSuppression",
        filters = listOf(
            "tenant_group_id",
            "clinic_id",
            "appointment_id",
            "row_kind",
            "notification_slot",
            "status",
        ),
        orderBy = listOf("id"),
        indexColumns = NotificationOutboxIndexes.reminderSuppression.columns,
    )
    val leaseRecovery = NotificationOutboxQueryContract(
        name = "leaseRecovery",
        filters = listOf("row_kind", "status", "lease_until", "id"),
        orderBy = listOf("lease_until", "id"),
        indexColumns = NotificationOutboxIndexes.leaseRecovery.columns,
    )
    val terminalRetention = NotificationOutboxQueryContract(
        name = "terminalRetention",
        filters = listOf("row_kind", "status", "terminal_at", "id"),
        orderBy = listOf("terminal_at", "id"),
        indexColumns = NotificationOutboxIndexes.terminalRetention.columns,
    )
    val pendingOldest = NotificationOutboxQueryContract(
        name = "pendingOldest",
        filters = listOf("row_kind", "status", "available_at", "created_at"),
        orderBy = listOf("available_at", "created_at"),
        indexColumns = NotificationOutboxIndexes.pendingOldest.columns,
    )
}
