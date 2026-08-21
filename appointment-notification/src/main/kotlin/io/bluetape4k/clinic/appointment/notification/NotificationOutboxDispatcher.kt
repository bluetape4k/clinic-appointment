package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.NotificationFairCursor
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * notification outbox 후보를 clinic cursor 기준으로 공정하게 claim하고 제한된 동시성으로 처리합니다.
 */
class NotificationOutboxDispatcher private constructor(
    private val store: NotificationOutboxWorkStore,
    private val worker: NotificationOutboxJobWorker,
    private val leaseOwner: String,
    private val globalConcurrency: Int,
    private val perClinicConcurrency: Int,
    private val readiness: NotificationSchemaReadiness? = null,
    private val routeGate: NotificationDeliveryRouteGate = NotificationDeliveryRouteGate.active(),
    private val metrics: NotificationOutboxMetrics? = null,
    private val concurrencyCoordinator: NotificationOutboxConcurrencyCoordinator,
) : AutoCloseable {
    constructor(
        store: NotificationOutboxWorkStore,
        worker: NotificationOutboxJobWorker,
        leaseOwner: String,
        globalConcurrency: Int,
        perClinicConcurrency: Int,
        readiness: NotificationSchemaReadiness? = null,
        routeGate: NotificationDeliveryRouteGate = NotificationDeliveryRouteGate.active(),
        metrics: NotificationOutboxMetrics? = null,
    ) : this(
        store = store,
        worker = worker,
        leaseOwner = leaseOwner,
        globalConcurrency = globalConcurrency,
        perClinicConcurrency = perClinicConcurrency,
        readiness = readiness,
        routeGate = routeGate,
        metrics = metrics,
        concurrencyCoordinator = LocalNotificationOutboxConcurrencyCoordinator(
            globalConcurrency = globalConcurrency,
            perClinicConcurrency = perClinicConcurrency,
        ),
    )

    companion object {
        internal fun withCoordinator(
            store: NotificationOutboxWorkStore,
            worker: NotificationOutboxJobWorker,
            leaseOwner: String,
            globalConcurrency: Int,
            perClinicConcurrency: Int,
            readiness: NotificationSchemaReadiness? = null,
            routeGate: NotificationDeliveryRouteGate = NotificationDeliveryRouteGate.active(),
            metrics: NotificationOutboxMetrics? = null,
            concurrencyCoordinator: NotificationOutboxConcurrencyCoordinator,
        ): NotificationOutboxDispatcher = NotificationOutboxDispatcher(
            store = store,
            worker = worker,
            leaseOwner = leaseOwner,
            globalConcurrency = globalConcurrency,
            perClinicConcurrency = perClinicConcurrency,
            readiness = readiness,
            routeGate = routeGate,
            metrics = metrics,
            concurrencyCoordinator = concurrencyCoordinator,
        )
    }

    private val claimMutex = Mutex()
    private var cursor: NotificationFairCursor? = null
    private var recoveryTurn: Boolean = true

    init {
        require(leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
        require(globalConcurrency > 0) { "globalConcurrency must be positive" }
        require(perClinicConcurrency in 1..globalConcurrency) {
            "perClinicConcurrency must be between 1 and globalConcurrency"
        }
    }

    suspend fun dispatchOnce(): List<NotificationOutboxWorkerResult> {
        if (!routeGate.hasWorkerRoute || readiness?.check()?.available == false) return emptyList()
        val claimed = claimWorkBatch()
        return coroutineScope {
            claimed.map { notification ->
                async {
                    when (val admission = concurrencyCoordinator.withPermit(notification) {
                        worker.process(notification)
                    }) {
                        is NotificationOutboxAdmission.Acquired -> admission.value
                        is NotificationOutboxAdmission.Backpressured -> {
                            metrics?.recordConcurrencyAdmission(
                                mode = concurrencyCoordinator.mode,
                                reason = admission.reason,
                            )
                            NotificationOutboxWorkerResult.NOT_READY
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
            val recovered = recoverExpired(globalConcurrency - 1)
            val remaining = globalConcurrency - recovered.size
            if (remaining <= 0) return@withLock recovered
            recovered + claimFairBatch(remaining)
        }

    private suspend fun claimSingleWork(): List<ClaimedNotification> {
        if (recoveryTurn) {
            recoveryTurn = false
            val recovered = recoverExpired(1)
            if (recovered.isNotEmpty()) return recovered
            return claimFairBatch(1)
        }
        recoveryTurn = true
        val claimed = claimFairBatch(1)
        if (claimed.isNotEmpty()) return claimed
        return recoverExpired(1)
    }

    private suspend fun recoverExpired(limit: Int): List<ClaimedNotification> =
        store.recoverExpired(limit, leaseOwner, routeGate.workerScopes)
            .filter { recovered ->
                routeGate.allows(
                    NotificationDeliveryRoute.OUTBOX_WORKER,
                    TenantClinicScope(recovered.tenantGroupId.value, recovered.clinicId.value),
                )
            }
            .also { recovered ->
            recovered.forEach { metrics?.recordLeaseRecovered(it.channel, it.eventType) }
        }

    private suspend fun claimFairBatch(limit: Int): List<ClaimedNotification> {
        val page = store.findFairCandidatesForRoute(
            limit = limit,
            cursor = cursor,
            perClinicLimit = perClinicConcurrency,
            eligibleScopes = routeGate.workerScopes,
        )
        cursor = page.nextCursor
        return page.candidates
            .filter { candidate ->
                routeGate.allows(
                    NotificationDeliveryRoute.OUTBOX_WORKER,
                    TenantClinicScope(candidate.tenantGroupId.value, candidate.clinicId.value),
                )
            }
            .mapNotNull { candidate -> store.claim(candidate.id, leaseOwner) }
    }

    override fun close() {
        concurrencyCoordinator.close()
    }
}
