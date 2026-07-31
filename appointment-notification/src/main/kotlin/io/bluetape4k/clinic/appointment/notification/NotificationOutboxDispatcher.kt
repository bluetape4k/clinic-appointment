package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.NotificationFairCursor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * notification outbox 후보를 clinic cursor 기준으로 공정하게 claim하고 제한된 동시성으로 처리합니다.
 */
class NotificationOutboxDispatcher(
    private val store: NotificationOutboxWorkStore,
    private val worker: NotificationOutboxJobWorker,
    private val leaseOwner: String,
    private val globalConcurrency: Int,
    private val perClinicConcurrency: Int,
    private val readiness: NotificationSchemaReadiness? = null,
) {
    private val globalPermits: Semaphore
    private val clinicPermits: NotificationClinicPermitRegistry
    private val claimMutex = Mutex()
    private var cursor: NotificationFairCursor? = null
    private var recoveryTurn: Boolean = true

    init {
        require(leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
        require(globalConcurrency > 0) { "globalConcurrency must be positive" }
        require(perClinicConcurrency in 1..globalConcurrency) {
            "perClinicConcurrency must be between 1 and globalConcurrency"
        }
        globalPermits = Semaphore(globalConcurrency)
        clinicPermits = NotificationClinicPermitRegistry(perClinicConcurrency)
    }

    suspend fun dispatchOnce(): List<NotificationOutboxWorkerResult> {
        if (readiness?.check()?.available == false) return emptyList()
        val claimed = claimWorkBatch()
        return coroutineScope {
            claimed.map { notification ->
                async {
                    globalPermits.withPermit {
                        clinicPermits.withPermit(notification) {
                            worker.process(notification)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun claimWorkBatch(): List<ClaimedNotification> =
        claimMutex.withLock {
            if (globalConcurrency == 1) {
                return@withLock claimSingleWork()
            }
            val recovered = store.recoverExpired(globalConcurrency - 1, leaseOwner)
            val remaining = globalConcurrency - recovered.size
            if (remaining <= 0) return@withLock recovered
            recovered + claimFairBatch(remaining)
        }

    private suspend fun claimSingleWork(): List<ClaimedNotification> {
        if (recoveryTurn) {
            recoveryTurn = false
            val recovered = store.recoverExpired(1, leaseOwner)
            if (recovered.isNotEmpty()) return recovered
            return claimFairBatch(1)
        }
        recoveryTurn = true
        val claimed = claimFairBatch(1)
        if (claimed.isNotEmpty()) return claimed
        return store.recoverExpired(1, leaseOwner)
    }

    private suspend fun claimFairBatch(limit: Int): List<ClaimedNotification> {
        val page = store.findFairCandidates(limit, cursor)
        cursor = page.nextCursor
        return page.candidates.mapNotNull { candidate -> store.claim(candidate.id, leaseOwner) }
    }
}

private class NotificationClinicPermitRegistry(
    private val permits: Int,
) {
    private val entries = ConcurrentHashMap<NotificationClinicPermitKey, NotificationClinicPermitEntry>()

    suspend fun <T> withPermit(
        notification: ClaimedNotification,
        action: suspend () -> T,
    ): T {
        val key = NotificationClinicPermitKey(notification.tenantGroupId.value, notification.clinicId.value)
        val entry = retain(key)
        return try {
            entry.semaphore.withPermit { action() }
        } finally {
            release(key, entry)
        }
    }

    private fun retain(key: NotificationClinicPermitKey): NotificationClinicPermitEntry {
        var retained: NotificationClinicPermitEntry? = null
        entries.compute(key) { _, current ->
            val entry = current ?: NotificationClinicPermitEntry(Semaphore(permits))
            entry.referenceCount++
            retained = entry
            entry
        }
        return checkNotNull(retained)
    }

    private fun release(
        key: NotificationClinicPermitKey,
        retained: NotificationClinicPermitEntry,
    ) {
        entries.compute(key) { _, current ->
            check(current === retained) { "clinic permit entry changed while referenced" }
            check(retained.referenceCount > 0) { "clinic permit reference count must be positive" }
            retained.referenceCount--
            if (retained.referenceCount == 0) null else retained
        }
    }
}

private data class NotificationClinicPermitKey(
    val tenantGroupId: Long,
    val clinicId: Long,
)

private class NotificationClinicPermitEntry(
    val semaphore: Semaphore,
    var referenceCount: Int = 0,
)
