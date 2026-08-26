package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.coroutines.support.awaitSuspending
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.bluetape4k.clinic.appointment.notification.persistence.ClaimedNotification
import io.bluetape4k.redis.lettuce.synchronizer.ExpirablePermitHandle
import io.bluetape4k.redis.lettuce.synchronizer.ExpirableSemaphoreConfig
import io.bluetape4k.redis.lettuce.synchronizer.LettuceSuspendPermitExpirableSemaphore
import io.bluetape4k.redis.lettuce.synchronizer.PermitAcquireResult
import io.bluetape4k.redis.lettuce.synchronizer.PermitMutationResult
import io.bluetape4k.redis.lettuce.synchronizer.PermitReconcileResult
import io.bluetape4k.redis.lettuce.synchronizer.PermitRenewResult
import io.bluetape4k.redis.lettuce.synchronizer.SemaphoreConfig
import io.bluetape4k.redis.lettuce.synchronizer.SemaphoreInitializationResult
import io.bluetape4k.redis.lettuce.synchronizer.SemaphoreOwnerId
import io.bluetape4k.redis.lettuce.synchronizer.SemaphoreRequestId
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Redis 권위 동시성 조정기가 dispatcher에 제공하는 내부 admission port입니다. */
internal interface NotificationOutboxConcurrencyCoordinator : AutoCloseable {
    val mode: NotificationConcurrencyMode

    suspend fun <T> withPermit(
        notification: ClaimedNotification,
        action: suspend () -> T,
    ): NotificationOutboxAdmission<T>
}

internal sealed interface NotificationOutboxAdmission<out T> {
    data class Acquired<T>(val value: T) : NotificationOutboxAdmission<T>

    data class Backpressured(
        val reason: NotificationPermitFailureReason,
    ) : NotificationOutboxAdmission<Nothing>
}

internal enum class NotificationPermitFailureReason {
    UNAVAILABLE,
    CAPACITY_EXCEEDED,
    TIMED_OUT,
    BACKEND_FAILURE,
    INTEGRITY_FAILURE,
    AMBIGUOUS,
    OWNERSHIP_LOST,
    EXPIRED,
    RELEASED,
    CLOSED,
    RELEASE_FAILURE,
}

internal data class NotificationPermitHandle(
    val value: Any,
    val owner: SemaphoreOwnerId,
    val request: SemaphoreRequestId,
)

internal sealed interface NotificationPermitInitialization {
    data object Initialized : NotificationPermitInitialization
    data object AlreadyInitialized : NotificationPermitInitialization
    data object CapacityMismatch : NotificationPermitInitialization
    data object BackendFailure : NotificationPermitInitialization
    data object IntegrityFailure : NotificationPermitInitialization
    data object Closed : NotificationPermitInitialization
}

internal sealed interface NotificationPermitAcquire {
    data class Acquired(val handle: NotificationPermitHandle) : NotificationPermitAcquire
    data object Unavailable : NotificationPermitAcquire
    data object CapacityExceeded : NotificationPermitAcquire
    data object TimedOut : NotificationPermitAcquire
    data object BackendFailure : NotificationPermitAcquire
    data object IntegrityFailure : NotificationPermitAcquire
    data object Closed : NotificationPermitAcquire
    data object Ambiguous : NotificationPermitAcquire
}

internal sealed interface NotificationPermitReconcile {
    data class Owned(val handle: NotificationPermitHandle) : NotificationPermitReconcile
    data object Released : NotificationPermitReconcile
    data object NotFound : NotificationPermitReconcile
    data object StaleGeneration : NotificationPermitReconcile
    data object BackendFailure : NotificationPermitReconcile
    data object IntegrityFailure : NotificationPermitReconcile
    data object Closed : NotificationPermitReconcile
}

internal sealed interface NotificationPermitRenew {
    data class Renewed(val handle: NotificationPermitHandle) : NotificationPermitRenew
    data object Released : NotificationPermitRenew
    data object Expired : NotificationPermitRenew
    data object OwnershipLost : NotificationPermitRenew
    data object StaleGeneration : NotificationPermitRenew
    data object BackendFailure : NotificationPermitRenew
    data object IntegrityFailure : NotificationPermitRenew
    data object Closed : NotificationPermitRenew
    data object Ambiguous : NotificationPermitRenew
}

internal sealed interface NotificationPermitMutation {
    data object Released : NotificationPermitMutation
    data object AlreadyReleased : NotificationPermitMutation
    data object Expired : NotificationPermitMutation
    data object StaleGeneration : NotificationPermitMutation
    data object BackendFailure : NotificationPermitMutation
    data object IntegrityFailure : NotificationPermitMutation
    data object Closed : NotificationPermitMutation
    data object Ambiguous : NotificationPermitMutation
}

/** Redis semaphore adapter를 주입해 coordinator를 deterministic하게 검증할 수 있게 합니다. */
internal interface NotificationPermitSemaphore : AutoCloseable {
    suspend fun initialize(capacity: Int): NotificationPermitInitialization

    suspend fun acquire(
        owner: SemaphoreOwnerId,
        request: SemaphoreRequestId,
        waitTime: Duration,
    ): NotificationPermitAcquire

    suspend fun reconcile(
        owner: SemaphoreOwnerId,
        request: SemaphoreRequestId,
    ): NotificationPermitReconcile

    suspend fun renew(
        handle: NotificationPermitHandle,
        extension: Duration,
    ): NotificationPermitRenew

    suspend fun release(handle: NotificationPermitHandle): NotificationPermitMutation
}

internal fun interface NotificationPermitSemaphoreFactory {
    fun create(name: String, capacity: Int): NotificationPermitSemaphore
}

/** 기존 단일 프로세스 동작을 명시적인 LOCAL 모드로 보존합니다. */
internal class LocalNotificationOutboxConcurrencyCoordinator(
    globalConcurrency: Int,
    perClinicConcurrency: Int,
) : NotificationOutboxConcurrencyCoordinator {
    override val mode: NotificationConcurrencyMode = NotificationConcurrencyMode.LOCAL
    private val globalPermits = Semaphore(globalConcurrency)
    private val clinicPermits = LocalNotificationClinicPermitRegistry(perClinicConcurrency)

    override suspend fun <T> withPermit(
        notification: ClaimedNotification,
        action: suspend () -> T,
    ): NotificationOutboxAdmission<T> = NotificationOutboxAdmission.Acquired(
        globalPermits.withPermit {
            clinicPermits.withPermit(notification, action)
        },
    )

    override fun close() = Unit
}

/**
 * Redis expirable permit을 clinic-first 순서로 취득하고, Redis 장애는 backpressure로
 * 변환하는 coordinator입니다.
 */
internal class RedisNotificationOutboxConcurrencyCoordinator(
    private val properties: NotificationProperties.WorkerProperties,
    private val global: NotificationPermitSemaphore,
    private val clinicFactory: NotificationPermitSemaphoreFactory,
    private val owner: SemaphoreOwnerId = SemaphoreOwnerId.random(),
    private val onFailure: (NotificationPermitFailureReason) -> Unit = {},
) : NotificationOutboxConcurrencyCoordinator {
    override val mode: NotificationConcurrencyMode = NotificationConcurrencyMode.REDIS
    private val globalEntry = DistributedPermitEntry(global)
    private val clinics = DistributedClinicPermitRegistry(clinicFactory, properties.perClinicConcurrency)
    private val closed = AtomicBoolean(false)

    private val leaseTime = properties.leaseDuration
    private val renewInterval = leaseTime.dividedBy(3)
    private val renewTimeout = minOf(
        leaseTime.dividedBy(4),
        maxOf(Duration.ofMillis(250), properties.pollInterval.multipliedBy(4)),
    )
    private val acquireWaitTime = minOf(properties.pollInterval, leaseTime.dividedBy(4))

    init {
        properties.validate()
        require(properties.concurrencyMode == NotificationConcurrencyMode.REDIS) {
            "Redis coordinator requires REDIS concurrency mode"
        }
        require(leaseTime >= REDIS_MIN_LEASE) {
            "Redis coordinator requires a lease duration of at least $REDIS_MIN_LEASE"
        }
        require(!renewInterval.isZero && renewInterval < leaseTime) {
            "Redis renew interval must be positive and shorter than the lease duration"
        }
        require(!acquireWaitTime.isNegative && acquireWaitTime <= leaseTime.dividedBy(4)) {
            "Redis acquire wait must be bounded by one quarter of the lease duration"
        }
    }

    override suspend fun <T> withPermit(
        notification: ClaimedNotification,
        action: suspend () -> T,
    ): NotificationOutboxAdmission<T> {
        if (closed.get()) return backpressure(NotificationPermitFailureReason.CLOSED)

        val clinicKey = NotificationClinicPermitKey(
            tenantGroupId = notification.tenantGroupId.value,
            clinicId = notification.clinicId.value,
        )
        return clinics.withEntry<NotificationOutboxAdmission<T>>(clinicKey) { clinicEntry ->
            val clinicAttempt = acquire(
                entry = clinicEntry,
                capacity = properties.perClinicConcurrency,
            )
            if (clinicAttempt is PermitAcquireAttempt.Backpressured) {
                return@withEntry backpressure(clinicAttempt.reason)
            }
            val clinicPermit = (clinicAttempt as PermitAcquireAttempt.Acquired).handle

            val globalPermit = try {
                acquire(
                    entry = globalEntry,
                    capacity = properties.globalConcurrency,
                )
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { release(clinicEntry, clinicPermit) }
                throw cancelled
            }
            if (globalPermit is PermitAcquireAttempt.Backpressured) {
                withContext(NonCancellable) { release(clinicEntry, clinicPermit) }
                return@withEntry backpressure(globalPermit.reason)
            }
            val globalHandle = (globalPermit as PermitAcquireAttempt.Acquired).handle
            val clinicReference = AtomicReference(clinicPermit)
            val globalReference = AtomicReference(globalHandle)
            try {
                val outcome: NotificationOutboxAdmission<T> = try {
                    NotificationOutboxAdmission.Acquired(
                        runWithRenew(
                            clinicEntry = clinicEntry,
                            clinicReference = clinicReference,
                            globalReference = globalReference,
                            action = action,
                        ),
                    )
                } catch (failure: NotificationPermitLostException) {
                    backpressure(failure.reason)
                }
                outcome
            } finally {
                withContext(NonCancellable) {
                    release(clinicEntry, clinicReference.get())
                    release(globalEntry, globalReference.get())
                }
            }
        }
    }

    private suspend fun acquire(
        entry: DistributedPermitEntry,
        capacity: Int,
    ): PermitAcquireAttempt {
        val initializationFailure = entry.initialize(capacity)
        if (initializationFailure != null) {
            return PermitAcquireAttempt.Backpressured(initializationFailure)
        }
        val request = SemaphoreRequestId.random()
        return try {
            when (val result = entry.semaphore.acquire(owner, request, acquireWaitTime)) {
                is NotificationPermitAcquire.Acquired -> PermitAcquireAttempt.Acquired(result.handle)
                NotificationPermitAcquire.Ambiguous -> when (val reconciled = entry.semaphore.reconcile(owner, request)) {
                    is NotificationPermitReconcile.Owned -> PermitAcquireAttempt.Acquired(reconciled.handle)
                    else -> PermitAcquireAttempt.Backpressured(reconciled.toFailureReason())
                }
                else -> PermitAcquireAttempt.Backpressured(result.toFailureReason())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PermitAcquireAttempt.Backpressured(NotificationPermitFailureReason.BACKEND_FAILURE)
        }
    }

    private suspend fun <T> runWithRenew(
        clinicEntry: DistributedPermitEntry,
        clinicReference: AtomicReference<NotificationPermitHandle>,
        globalReference: AtomicReference<NotificationPermitHandle>,
        action: suspend () -> T,
    ): T = coroutineScope {
        val failure = CompletableDeferred<NotificationPermitFailureReason>()
        val actionDeferred = async(start = CoroutineStart.DEFAULT) { action() }
        val renewer = launch {
            try {
                while (isActive) {
                    delay(renewInterval.toMillis())
                    val renewResults = awaitAll(
                        async { renew(clinicEntry, clinicReference) },
                        async { renew(globalEntry, globalReference) },
                    )
                    val firstFailure = renewResults.filterIsInstance<NotificationPermitFailureReason>().firstOrNull()
                    if (firstFailure != null) {
                        failure.complete(firstFailure)
                        return@launch
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure.complete(NotificationPermitFailureReason.BACKEND_FAILURE)
            }
        }
        try {
            select {
                actionDeferred.onAwait { it }
                failure.onAwait { reason -> throw NotificationPermitLostException(reason) }
            }
        } finally {
            renewer.cancelAndJoin()
        }
    }

    private suspend fun renew(
        entry: DistributedPermitEntry,
        reference: AtomicReference<NotificationPermitHandle>,
    ): Any = withTimeoutOrNull(renewTimeout.toMillis().coerceAtLeast(1L)) {
        when (val result = entry.semaphore.renew(reference.get(), leaseTime)) {
            is NotificationPermitRenew.Renewed -> {
                reference.set(result.handle)
                Unit
            }
            else -> result.toFailureReason()
        }
    } ?: NotificationPermitFailureReason.BACKEND_FAILURE

    private suspend fun release(
        entry: DistributedPermitEntry,
        handle: NotificationPermitHandle,
    ) {
        try {
            when (val result = entry.semaphore.release(handle)) {
                NotificationPermitMutation.Released,
                NotificationPermitMutation.AlreadyReleased,
                NotificationPermitMutation.Expired,
                NotificationPermitMutation.StaleGeneration,
                -> Unit
                NotificationPermitMutation.Ambiguous -> when (
                    val reconciled = entry.semaphore.reconcile(handle.owner, handle.request)
                ) {
                    is NotificationPermitReconcile.Owned -> when (entry.semaphore.release(reconciled.handle)) {
                        NotificationPermitMutation.Released,
                        NotificationPermitMutation.AlreadyReleased,
                        NotificationPermitMutation.Expired,
                        NotificationPermitMutation.StaleGeneration,
                        -> Unit
                        else -> onFailure(NotificationPermitFailureReason.RELEASE_FAILURE)
                    }
                    NotificationPermitReconcile.Released,
                    NotificationPermitReconcile.NotFound,
                    NotificationPermitReconcile.StaleGeneration,
                    -> Unit
                    else -> onFailure(NotificationPermitFailureReason.RELEASE_FAILURE)
                }
                else -> onFailure(NotificationPermitFailureReason.RELEASE_FAILURE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onFailure(NotificationPermitFailureReason.RELEASE_FAILURE)
        }
    }

    private fun backpressure(reason: NotificationPermitFailureReason): NotificationOutboxAdmission.Backpressured {
        onFailure(reason)
        return NotificationOutboxAdmission.Backpressured(reason)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clinics.close()
        global.close()
    }

    companion object {
        private val REDIS_MIN_LEASE: Duration = Duration.ofSeconds(1)
    }
}

private class NotificationPermitLostException(
    val reason: NotificationPermitFailureReason,
) : RuntimeException("Redis notification permit lost: $reason")

private sealed interface PermitAcquireAttempt {
    data class Acquired(val handle: NotificationPermitHandle) : PermitAcquireAttempt
    data class Backpressured(val reason: NotificationPermitFailureReason) : PermitAcquireAttempt
}

private class DistributedPermitEntry(
    val semaphore: NotificationPermitSemaphore,
) {
    private val initializationMutex = Mutex()
    private var initialized = false

    suspend fun initialize(capacity: Int): NotificationPermitFailureReason? = initializationMutex.withLock {
        if (initialized) return@withLock null
        when (val result = semaphore.initialize(capacity)) {
            NotificationPermitInitialization.Initialized,
            NotificationPermitInitialization.AlreadyInitialized,
            -> {
                initialized = true
                null
            }
            NotificationPermitInitialization.CapacityMismatch,
            NotificationPermitInitialization.IntegrityFailure,
            -> NotificationPermitFailureReason.INTEGRITY_FAILURE
            NotificationPermitInitialization.BackendFailure -> NotificationPermitFailureReason.BACKEND_FAILURE
            NotificationPermitInitialization.Closed -> NotificationPermitFailureReason.CLOSED
        }
    }

    fun close() = semaphore.close()
}

private class DistributedClinicPermitRegistry(
    private val factory: NotificationPermitSemaphoreFactory,
    private val capacity: Int,
) : AutoCloseable {
    private val entries = ConcurrentHashMap<NotificationClinicPermitKey, DistributedClinicEntry>()
    private val closed = AtomicBoolean(false)
    private val lock = Any()

    suspend fun <T> withEntry(
        key: NotificationClinicPermitKey,
        action: suspend (DistributedPermitEntry) -> T,
    ): T {
        check(!closed.get()) { "clinic permit registry is closed" }
        val entry = retain(key)
        return try {
            action(entry.permit)
        } finally {
            release(key, entry)
        }
    }

    private fun retain(key: NotificationClinicPermitKey): DistributedClinicEntry {
        return synchronized(lock) {
            check(!closed.get()) { "clinic permit registry is closed" }
            entries.compute(key) { _, current ->
                val entry = current ?: DistributedClinicEntry(
                    permit = DistributedPermitEntry(
                        factory.create("clinic-${key.tenantGroupId}-${key.clinicId}", capacity),
                    ),
                )
                entry.referenceCount.incrementAndGet()
                entry.lastReleasedNanos = 0L
                entry
            }.let(::checkNotNull)
        }
    }

    private fun release(key: NotificationClinicPermitKey, retained: DistributedClinicEntry) {
        val evicted = synchronized(lock) {
            entries.compute(key) { _, current ->
                check(current === retained) { "clinic permit entry changed while referenced" }
                check(retained.referenceCount.get() > 0) { "clinic permit reference count must be positive" }
                if (retained.referenceCount.decrementAndGet() == 0) {
                    retained.lastReleasedNanos = System.nanoTime()
                }
                if (closed.get() && retained.referenceCount.get() == 0) null else retained
            }
            evictIdleEntriesLocked()
        }
        evicted.forEach { it.permit.close() }
    }

    private fun evictIdleEntriesLocked(): List<DistributedClinicEntry> {
        val idle = entries.entries
            .asSequence()
            .filter { it.value.referenceCount.get() == 0 }
            .sortedBy { it.value.lastReleasedNanos }
            .toList()
        val excess = idle.size - DistributedClinicEntry.MAX_IDLE_ENTRIES
        if (excess <= 0) return emptyList()

        return idle.take(excess).mapNotNull { candidate ->
            if (entries.remove(candidate.key, candidate.value)) candidate.value else null
        }
    }

    override fun close() {
        val retained = synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            val current = entries.values.toList()
            current.filter { it.referenceCount.get() == 0 }.forEach { entry ->
                entries.entries.removeIf { it.value === entry }
            }
            current
        }
        retained.forEach { it.permit.close() }
    }
}

private class DistributedClinicEntry(
    val permit: DistributedPermitEntry,
    val referenceCount: AtomicInteger = AtomicInteger(),
) {
    @Volatile
    var lastReleasedNanos: Long = 0L

    companion object {
        const val MAX_IDLE_ENTRIES: Int = 256
    }
}

private class LocalNotificationClinicPermitRegistry(
    private val permits: Int,
) {
    private val entries = ConcurrentHashMap<NotificationClinicPermitKey, LocalClinicEntry>()

    suspend fun <T> withPermit(
        notification: ClaimedNotification,
        action: suspend () -> T,
    ): T = withPermit(notification.tenantGroupId.value, notification.clinicId.value, action)

    private suspend fun <T> withPermit(
        tenantGroupId: Long,
        clinicId: Long,
        action: suspend () -> T,
    ): T {
        val key = NotificationClinicPermitKey(tenantGroupId, clinicId)
        val entry = retain(key)
        return try {
            entry.semaphore.withPermit { action() }
        } finally {
            release(key, entry)
        }
    }

    private fun retain(key: NotificationClinicPermitKey): LocalClinicEntry {
        var retained: LocalClinicEntry? = null
        entries.compute(key) { _, current ->
            val entry = current ?: LocalClinicEntry(Semaphore(permits))
            entry.referenceCount.incrementAndGet()
            retained = entry
            entry
        }
        return checkNotNull(retained)
    }

    private fun release(key: NotificationClinicPermitKey, retained: LocalClinicEntry) {
        entries.compute(key) { _, current ->
            check(current === retained) { "clinic permit entry changed while referenced" }
            check(retained.referenceCount.get() > 0) { "clinic permit reference count must be positive" }
            if (retained.referenceCount.decrementAndGet() == 0) null else retained
        }
    }
}

private class LocalClinicEntry(
    val semaphore: Semaphore,
    val referenceCount: AtomicInteger = AtomicInteger(),
)

/** 전환기 direct delivery가 사용하는 프로세스 로컬 clinic permit registry입니다. */
internal class NotificationClinicPermitRegistry(
    private val permits: Int,
) {
    private val entries = ConcurrentHashMap<DirectClinicPermitKey, DirectClinicPermitEntry>()

    suspend fun <T> withPermit(
        tenantGroupId: Long,
        clinicId: Long,
        action: suspend () -> T,
    ): T {
        val key = DirectClinicPermitKey(tenantGroupId, clinicId)
        val entry = retain(key)
        return try {
            entry.semaphore.withPermit { action() }
        } finally {
            release(key, entry)
        }
    }

    private fun retain(key: DirectClinicPermitKey): DirectClinicPermitEntry {
        var retained: DirectClinicPermitEntry? = null
        entries.compute(key) { _, current ->
            val entry = current ?: DirectClinicPermitEntry(Semaphore(permits))
            entry.referenceCount.incrementAndGet()
            retained = entry
            entry
        }
        return checkNotNull(retained)
    }

    private fun release(key: DirectClinicPermitKey, retained: DirectClinicPermitEntry) {
        entries.compute(key) { _, current ->
            check(current === retained) { "clinic permit entry changed while referenced" }
            check(retained.referenceCount.get() > 0) { "clinic permit reference count must be positive" }
            if (retained.referenceCount.decrementAndGet() == 0) null else retained
        }
    }
}

private data class DirectClinicPermitKey(
    val tenantGroupId: Long,
    val clinicId: Long,
)

private class DirectClinicPermitEntry(
    val semaphore: Semaphore,
    val referenceCount: AtomicInteger = AtomicInteger(),
)

private data class NotificationClinicPermitKey(
    val tenantGroupId: Long,
    val clinicId: Long,
)

/** cache/leader용 임의 Redis connection과 분리된 notification 전용 connection port입니다. */
fun interface NotificationConcurrencyRedisConnection {
    fun connection(): StatefulRedisConnection<String, String>
}

/** Auto-configuration이 만든 dedicated connection의 소유권을 표현합니다. */
class OwnedNotificationConcurrencyRedisConnection(
    private val delegate: StatefulRedisConnection<String, String>,
) : NotificationConcurrencyRedisConnection, AutoCloseable {
    override fun connection(): StatefulRedisConnection<String, String> = delegate

    override fun close() {
        delegate.close()
    }
}

internal class LettuceNotificationPermitSemaphoreFactory(
    private val connection: StatefulRedisConnection<String, String>,
    private val leaseTime: Duration,
    private val pollInterval: Duration,
    private val namespace: String = REDIS_NAMESPACE,
    private val hashTag: String = REDIS_HASH_TAG,
) : NotificationPermitSemaphoreFactory {
    override fun create(name: String, capacity: Int): NotificationPermitSemaphore =
        LettuceNotificationPermitSemaphore(
            connection = connection,
            name = name,
            capacity = capacity,
            leaseTime = leaseTime,
            pollInterval = pollInterval,
            namespace = namespace,
            hashTag = hashTag,
        )
}

private class LettuceNotificationPermitSemaphore(
    connection: StatefulRedisConnection<String, String>,
    private val name: String,
    capacity: Int,
    leaseTime: Duration,
    pollInterval: Duration,
    namespace: String,
    hashTag: String,
) : NotificationPermitSemaphore {
    private val semaphore = LettuceSuspendPermitExpirableSemaphore.create(
        connection,
        name,
        ExpirableSemaphoreConfig(
            semaphore = SemaphoreConfig(
                namespace = namespace,
                hashTag = hashTag,
                maxPermits = capacity,
                pollInterval = pollInterval,
            ),
            leaseTime = leaseTime,
            maxPermitsPerAcquire = 1,
            cleanupBatchLimit = 64,
        ),
    )
    private val capacityKey = "$namespace:{$hashTag}:capacity-contract:$name"
    private val closed = AtomicBoolean(false)
    private val redis = connection.async()

    override suspend fun initialize(capacity: Int): NotificationPermitInitialization {
        if (closed.get()) return NotificationPermitInitialization.Closed
        return try {
            val existing = redis.get(capacityKey).awaitSuspending()
            if (existing == null) {
                redis.set(capacityKey, capacity.toString(), SetArgs.Builder.nx()).awaitSuspending()
            }
            val actual = redis.get(capacityKey).awaitSuspending()
            if (actual != capacity.toString()) {
                NotificationPermitInitialization.CapacityMismatch
            } else {
                when (semaphore.trySetPermits(capacity)) {
                    is SemaphoreInitializationResult.Initialized,
                    SemaphoreInitializationResult.AlreadyInitialized,
                    -> NotificationPermitInitialization.AlreadyInitialized
                    SemaphoreInitializationResult.InvalidCapacity -> NotificationPermitInitialization.CapacityMismatch
                    SemaphoreInitializationResult.Closed -> NotificationPermitInitialization.Closed
                    is SemaphoreInitializationResult.BackendFailure -> NotificationPermitInitialization.BackendFailure
                    is SemaphoreInitializationResult.IntegrityFailure -> NotificationPermitInitialization.IntegrityFailure
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NotificationPermitInitialization.BackendFailure
        }
    }

    override suspend fun acquire(
        owner: SemaphoreOwnerId,
        request: SemaphoreRequestId,
        waitTime: Duration,
    ): NotificationPermitAcquire = try {
        semaphore.acquire(owner, request, 1, waitTime).toNotificationResult()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NotificationPermitAcquire.BackendFailure
    }

    override suspend fun reconcile(
        owner: SemaphoreOwnerId,
        request: SemaphoreRequestId,
    ): NotificationPermitReconcile = try {
        semaphore.reconcile(owner, request).toNotificationResult()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NotificationPermitReconcile.BackendFailure
    }

    override suspend fun renew(
        handle: NotificationPermitHandle,
        extension: Duration,
    ): NotificationPermitRenew = try {
        semaphore.renew(handle.value.asExpirableHandle(), extension).toNotificationResult()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NotificationPermitRenew.BackendFailure
    }

    override suspend fun release(handle: NotificationPermitHandle): NotificationPermitMutation = try {
        semaphore.release(handle.value.asExpirableHandle()).toNotificationResult()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NotificationPermitMutation.BackendFailure
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) semaphore.close()
    }

    private fun Any.asExpirableHandle(): ExpirablePermitHandle =
        this as? ExpirablePermitHandle ?: error("unexpected Redis permit handle")

}

private fun PermitAcquireResult<ExpirablePermitHandle>.toNotificationResult(): NotificationPermitAcquire = when (this) {
    is PermitAcquireResult.Acquired -> NotificationPermitAcquire.Acquired(
        handle = handle.toNotificationHandle(),
    )
    PermitAcquireResult.Unavailable -> NotificationPermitAcquire.Unavailable
    PermitAcquireResult.CapacityExceeded -> NotificationPermitAcquire.CapacityExceeded
    PermitAcquireResult.TimedOut -> NotificationPermitAcquire.TimedOut
    is PermitAcquireResult.BackendFailure -> NotificationPermitAcquire.BackendFailure
    is PermitAcquireResult.IntegrityFailure -> NotificationPermitAcquire.IntegrityFailure
    PermitAcquireResult.Closed -> NotificationPermitAcquire.Closed
    is PermitAcquireResult.Ambiguous -> NotificationPermitAcquire.Ambiguous
}

private fun PermitReconcileResult<ExpirablePermitHandle>.toNotificationResult(): NotificationPermitReconcile = when (this) {
    is PermitReconcileResult.Owned -> NotificationPermitReconcile.Owned(handle.toNotificationHandle())
    PermitReconcileResult.Released -> NotificationPermitReconcile.Released
    PermitReconcileResult.NotFound -> NotificationPermitReconcile.NotFound
    PermitReconcileResult.StaleGeneration -> NotificationPermitReconcile.StaleGeneration
    is PermitReconcileResult.BackendFailure -> NotificationPermitReconcile.BackendFailure
    is PermitReconcileResult.IntegrityFailure -> NotificationPermitReconcile.IntegrityFailure
    PermitReconcileResult.Closed -> NotificationPermitReconcile.Closed
}

private fun PermitRenewResult<ExpirablePermitHandle>.toNotificationResult(): NotificationPermitRenew = when (this) {
    is PermitRenewResult.Renewed -> NotificationPermitRenew.Renewed(handle.toNotificationHandle())
    PermitRenewResult.Released -> NotificationPermitRenew.Released
    PermitRenewResult.Expired -> NotificationPermitRenew.Expired
    PermitRenewResult.OwnershipLost -> NotificationPermitRenew.OwnershipLost
    PermitRenewResult.StaleGeneration -> NotificationPermitRenew.StaleGeneration
    is PermitRenewResult.BackendFailure -> NotificationPermitRenew.BackendFailure
    is PermitRenewResult.IntegrityFailure -> NotificationPermitRenew.IntegrityFailure
    PermitRenewResult.Closed -> NotificationPermitRenew.Closed
    is PermitRenewResult.Ambiguous -> NotificationPermitRenew.Ambiguous
}

private fun PermitMutationResult<ExpirablePermitHandle>.toNotificationResult(): NotificationPermitMutation = when (this) {
    is PermitMutationResult.Released -> NotificationPermitMutation.Released
    PermitMutationResult.AlreadyReleased -> NotificationPermitMutation.AlreadyReleased
    PermitMutationResult.Expired -> NotificationPermitMutation.Expired
    PermitMutationResult.StaleGeneration -> NotificationPermitMutation.StaleGeneration
    is PermitMutationResult.BackendFailure -> NotificationPermitMutation.BackendFailure
    is PermitMutationResult.IntegrityFailure -> NotificationPermitMutation.IntegrityFailure
    PermitMutationResult.Closed -> NotificationPermitMutation.Closed
    is PermitMutationResult.Ambiguous -> NotificationPermitMutation.Ambiguous
}

private fun ExpirablePermitHandle.toNotificationHandle(): NotificationPermitHandle =
    NotificationPermitHandle(
        value = this,
        owner = permit.ownerId,
        request = permit.requestId,
    )

private fun NotificationPermitAcquire.toFailureReason(): NotificationPermitFailureReason = when (this) {
    NotificationPermitAcquire.Unavailable -> NotificationPermitFailureReason.UNAVAILABLE
    NotificationPermitAcquire.CapacityExceeded -> NotificationPermitFailureReason.CAPACITY_EXCEEDED
    NotificationPermitAcquire.TimedOut -> NotificationPermitFailureReason.TIMED_OUT
    NotificationPermitAcquire.BackendFailure -> NotificationPermitFailureReason.BACKEND_FAILURE
    NotificationPermitAcquire.IntegrityFailure -> NotificationPermitFailureReason.INTEGRITY_FAILURE
    NotificationPermitAcquire.Closed -> NotificationPermitFailureReason.CLOSED
    NotificationPermitAcquire.Ambiguous -> NotificationPermitFailureReason.AMBIGUOUS
    is NotificationPermitAcquire.Acquired -> error("acquired result has no failure reason")
}

private fun NotificationPermitReconcile.toFailureReason(): NotificationPermitFailureReason = when (this) {
    NotificationPermitReconcile.Released,
    NotificationPermitReconcile.NotFound,
    -> NotificationPermitFailureReason.RELEASED
    NotificationPermitReconcile.StaleGeneration -> NotificationPermitFailureReason.OWNERSHIP_LOST
    NotificationPermitReconcile.BackendFailure -> NotificationPermitFailureReason.BACKEND_FAILURE
    NotificationPermitReconcile.IntegrityFailure -> NotificationPermitFailureReason.INTEGRITY_FAILURE
    NotificationPermitReconcile.Closed -> NotificationPermitFailureReason.CLOSED
    is NotificationPermitReconcile.Owned -> error("owned result has no failure reason")
}

private fun NotificationPermitRenew.toFailureReason(): NotificationPermitFailureReason = when (this) {
    NotificationPermitRenew.Released -> NotificationPermitFailureReason.RELEASED
    NotificationPermitRenew.Expired -> NotificationPermitFailureReason.EXPIRED
    NotificationPermitRenew.OwnershipLost,
    NotificationPermitRenew.StaleGeneration,
    -> NotificationPermitFailureReason.OWNERSHIP_LOST
    NotificationPermitRenew.BackendFailure -> NotificationPermitFailureReason.BACKEND_FAILURE
    NotificationPermitRenew.IntegrityFailure -> NotificationPermitFailureReason.INTEGRITY_FAILURE
    NotificationPermitRenew.Closed -> NotificationPermitFailureReason.CLOSED
    NotificationPermitRenew.Ambiguous -> NotificationPermitFailureReason.AMBIGUOUS
    is NotificationPermitRenew.Renewed -> error("renewed result has no failure reason")
}

private const val REDIS_NAMESPACE = "clinic-notification-outbox-v1"
private const val REDIS_HASH_TAG = "notification-outbox"
