package io.bluetape4k.clinic.appointment.event.notification

import java.io.Serializable
import java.time.Instant

/**
 * event 모듈이 알림 outbox에 기록할 수 있는 최소 write port다.
 *
 * 이 계약은 저장소의 row 상태, lease, retry, attempt 같은 persistence 세부사항을
 * 노출하지 않는다. 구현체는 caller가 연 Exposed transaction 경계를 그대로 사용해야
 * 하며, 반환값은 저장된 durable row의 불투명 식별자만 전달한다.
 */
interface NotificationOutboxWriter {
    /** 발송 가능한 알림 draft를 caller transaction 안에서 기록한다. */
    fun enqueue(draft: SendableNotificationDraft): NotificationOutboxWriteReceipt

    /** legacy 예약 억제 기록을 caller transaction 안에서 기록한다. */
    fun suppressLegacy(draft: LegacySuppressionDraft): NotificationOutboxWriteReceipt

    /** 같은 idempotency digest의 기록이 현재 transaction에서 보이는지 확인한다. */
    fun containsIdempotency(digest: NotificationIdempotencyDigest): Boolean

    /** 예약 변경으로 더 이상 유효하지 않은 미래 리마인더를 종료한다. */
    fun suppressOutstandingReminders(
        tenantGroupId: TenantGroupId,
        clinicId: ClinicId,
        appointmentId: AppointmentId,
        suppressionReason: NotificationSuppressionReasonCode,
    ): Int
}

/**
 * write port가 반환하는 persistence 식별자다.
 *
 * 상태, row kind, lease token, attempt 같은 worker 전용 속성은 의도적으로 제공하지
 * 않는다. notification 모듈의 persistence record가 필요한 경우 이 타입을 확장한다.
 */
open class NotificationOutboxWriteReceipt(
    id: Long,
) : Serializable {
    open val id: Long = id.also { require(it > 0L) { "id must be positive" } }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 새로 발송 가능한 알림을 기록하기 위한 순수 event draft다.
 *
 * [idempotencyDigest]는 HMAC digest이며 원문 idempotency key를 직렬화하거나 저장하지
 * 않는다.
 */
data class SendableNotificationDraft(
    val envelope: NotificationOutboxEnvelope,
    val idempotencyDigest: NotificationIdempotencyDigest,
    val auditFingerprint: NotificationAuditFingerprint,
    val providerKey: String,
) : Serializable {
    init {
        validateDurableOpaqueString(providerKey, "providerKey", 128)
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * legacy 예약의 회원 ID 누락을 발송하지 않는 terminal row로 기록하는 순수 event draft다.
 */
data class LegacySuppressionDraft(
    val idempotencyDigest: NotificationIdempotencyDigest,
    val auditFingerprint: NotificationAuditFingerprint,
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val eventId: NotificationEventId,
    val suppressionReason: NotificationSuppressionReasonCode,
    val availableAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
