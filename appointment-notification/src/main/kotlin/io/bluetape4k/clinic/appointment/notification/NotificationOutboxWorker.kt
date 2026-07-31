package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification

/**
 * claim된 notification outbox row 하나를 처리하는 worker 계약입니다.
 *
 * 실제 provider 호출과 retry 정책은 후속 작업에서 확장하며, Task 8은 lease 복구와
 * dispatcher lifecycle 경계를 고정합니다.
 */
fun interface NotificationOutboxJobWorker {
    suspend fun process(claimed: ClaimedNotification): NotificationOutboxWorkerResult
}

enum class NotificationOutboxWorkerResult {
    COMPLETED,
    RETRY_SCHEDULED,
    LEASE_LOST,
    FAILED,
}

class NotificationOutboxWorker(
    private val workStore: NotificationOutboxWorkStore,
    private val leaseOwner: String,
    private val readiness: NotificationSchemaReadiness? = null,
) {

    init {
        require(leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
    }

    suspend fun recoverExpiredOnce(limit: Int): List<ClaimedNotification> {
        require(limit > 0) { "limit must be positive" }
        if (readiness?.check()?.available == false) return emptyList()
        return workStore.recoverExpired(limit, leaseOwner)
    }
}
