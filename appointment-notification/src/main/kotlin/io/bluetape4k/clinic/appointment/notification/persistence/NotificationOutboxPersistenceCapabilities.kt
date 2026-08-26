package io.bluetape4k.clinic.appointment.notification.persistence

import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import java.time.Duration
import java.time.Instant

/**
 * 알림 worker가 caller-owned transaction 안에서 필요한 persistence capability입니다.
 *
 * 이 port는 transaction을 열거나 dispatcher를 선택하지 않습니다. 구현체가 제공하는
 * query·claim·lifecycle 연산은 호출자가 연 트랜잭션의 원자성·lease fence·재시도 의미를
 * 그대로 따라야 합니다.
 */
interface NotificationOutboxWorkPersistence {
    fun currentDatabaseTime(): Instant

    fun findReadyClinicKeys(
        cursor: NotificationFairCursor?,
        limit: Int,
        eligibleScopes: Set<TenantClinicScope>? = null,
    ): List<NotificationClinicKey>

    fun findReadyCandidates(
        key: NotificationClinicKey,
        cursorId: Long?,
        limit: Int,
    ): List<NotificationCandidate>

    fun findExpiredProcessingIds(
        limit: Int,
        eligibleScopes: Set<TenantClinicScope>? = null,
    ): List<Long>

    fun claim(
        candidateId: Long,
        owner: String,
        token: String,
    ): ClaimedNotification?

    fun claimReadyForDirect(
        scope: TenantClinicScope,
        appointmentId: AppointmentId,
        eventType: NotificationEventType,
        owner: String,
        token: String,
    ): ClaimedNotification?

    fun recoverExpired(
        candidateId: Long,
        owner: String,
        token: String,
    ): ClaimedNotification?

    fun complete(command: CompleteNotificationCommand): Boolean

    fun scheduleRetry(command: RetryNotificationCommand): Boolean

    fun deleteTerminalBatch(
        status: NotificationOutboxStatus,
        retention: Duration,
        limit: Int,
    ): Int
}

/**
 * 알림 outbox의 제한된 ready 관찰 결과를 제공하는 persistence capability입니다.
 *
 * 구현체는 caller transaction을 요구하며, 관찰 상한과 시각 계산은 기존 persistence
 * 구현의 의미를 보존해야 합니다.
 */
fun interface NotificationOutboxObservationPersistence {
    fun observeReady(limit: Int): NotificationOutboxObservation
}
