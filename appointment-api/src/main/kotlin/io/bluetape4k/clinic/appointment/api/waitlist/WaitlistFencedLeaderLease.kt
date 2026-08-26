package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.clinic.appointment.waitlist.WaitlistFencingToken
import io.bluetape4k.codec.Base58
import io.bluetape4k.redis.lettuce.lock.FencedBootstrapResult
import io.bluetape4k.redis.lettuce.lock.FencedLockHandle
import io.bluetape4k.redis.lettuce.lock.LeasePolicy
import io.bluetape4k.redis.lettuce.lock.LettuceFencedLock
import io.bluetape4k.redis.lettuce.lock.LockAcquireResult
import io.bluetape4k.redis.lettuce.lock.LockBackendFailure
import io.bluetape4k.redis.lettuce.lock.LockIntegrityFailure
import io.bluetape4k.redis.lettuce.lock.LockMutationResult
import io.bluetape4k.redis.lettuce.lock.LockOwnerId
import io.bluetape4k.redis.lettuce.lock.LockRequestId
import io.bluetape4k.redis.lettuce.lock.LockReconcileResult
import io.bluetape4k.redis.lettuce.lock.LockRecoveryAction
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Lettuce fenced lock의 세부 결과를 scheduler가 안전하게 해석하는 닫힌 실패 분류입니다. */
enum class WaitlistLeaseFailure {
    BOOTSTRAP_FAILED,
    BACKEND_FAILURE,
    INTEGRITY_FAILURE,
    TIMEOUT,
    AMBIGUOUS,
    CLEANUP_PENDING,
    CAPACITY_EXCEEDED,
    CLOSED,
    OWNERSHIP_LOST,
    EXPIRED,
    STALE_GENERATION,
    RECONCILE_REQUIRED,
    RECONCILE_NOT_FOUND,
    RECONCILE_FAILED,
}

/** DB write에 전달할 때 필요한 정보만 포함하는 fenced lease handle입니다. */
@ConsistentCopyVisibility
data class WaitlistLeaseHandle internal constructor(
    val owner: String,
    val token: WaitlistFencingToken,
    val leaseUntil: Instant,
    internal val nativeHandle: FencedLockHandle,
) {
    /** native owner/request/key가 로그나 직렬화 경계로 새지 않도록 opaque 값만 표시합니다. */
    override fun toString(): String =
        "WaitlistLeaseHandle(owner=$owner, token=$token, leaseUntil=$leaseUntil)"
}

/** scheduler가 business mutation을 시작할 수 있는 유일한 lease 결과입니다. */
sealed interface WaitlistLeaseAttempt {
    data class Acquired(val handle: WaitlistLeaseHandle) : WaitlistLeaseAttempt
    data class Reentered(val handle: WaitlistLeaseHandle, val holdCount: Int) : WaitlistLeaseAttempt
    data class Contended(val remainingTtlMillis: Long) : WaitlistLeaseAttempt
    data class TimedOut(val category: WaitlistLeaseFailure) : WaitlistLeaseAttempt
    data class Ambiguous(val category: WaitlistLeaseFailure) : WaitlistLeaseAttempt
    data class Failed(val category: WaitlistLeaseFailure) : WaitlistLeaseAttempt
}

/** native release 결과를 식별자 없이 기록하기 위한 닫힌 결과입니다. */
enum class WaitlistLeaseRelease {
    RELEASED,
    ALREADY_RELEASED,
    OWNERSHIP_LOST,
    EXPIRED,
    UNKNOWN,
    CLOSED,
}

/** production adapter가 의존하는 최소 Lettuce fenced lock 경계입니다. */
internal interface WaitlistFencedLockOperations : AutoCloseable {
    fun bootstrap(): FencedBootstrapResult

    fun tryAcquire(
        owner: LockOwnerId,
        request: LockRequestId,
        policy: LeasePolicy,
    ): LockAcquireResult<FencedLockHandle>

    fun reconcile(owner: LockOwnerId, request: LockRequestId): LockReconcileResult<FencedLockHandle>

    fun release(handle: FencedLockHandle): LockMutationResult<FencedLockHandle>

    override fun close()
}

/** 이미 생성된 bluetape4k Lettuce fenced lock을 application adapter로 연결합니다. */
internal class LettuceWaitlistFencedLockOperations(
    private val delegate: LettuceFencedLock,
) : WaitlistFencedLockOperations {
    override fun bootstrap(): FencedBootstrapResult = delegate.bootstrapFencing()

    override fun tryAcquire(
        owner: LockOwnerId,
        request: LockRequestId,
        policy: LeasePolicy,
    ): LockAcquireResult<FencedLockHandle> = delegate.tryAcquire(owner, request, policy)

    override fun reconcile(owner: LockOwnerId, request: LockRequestId): LockReconcileResult<FencedLockHandle> =
        delegate.reconcile(owner, request)

    override fun release(handle: FencedLockHandle): LockMutationResult<FencedLockHandle> = delegate.release(handle)

    override fun close() = delegate.close()
}

/**
 * bluetape4k [LettuceFencedLock]을 waitlist scheduler의 typed lease port로 감쌉니다.
 *
 * 이 adapter는 native identity를 DB/API 경계로 노출하지 않습니다. acquire가 ambiguous이면
 * 동일한 owner/request pair만 보류하고 reconcile하며, 명확한 handle을 얻기 전에는 어떤 DB
 * mutation도 허용하지 않습니다.
 */
internal class FencedWaitlistLeaderLease(
    private val operations: WaitlistFencedLockOperations,
    private val properties: WaitlistDeliveryProperties,
    private val clock: Clock = Clock.systemUTC(),
    private val ownerRef: String = Base58.randomString(8),
) : AutoCloseable {

    private val ownerId: LockOwnerId
    private val leasePolicy: LeasePolicy.Fixed
    private var bootstrapResult: FencedBootstrapResult? = null
    private var pendingRequest: PendingRequest? = null
    private val activeHandleCounts = mutableMapOf<WaitlistLeaseHandle, Int>()
    private var closed = false

    init {
        require(ownerRef.matches(OPAQ_OWNER_PATTERN)) { "ownerRef must be an 8-character Base58 value" }
        require(!properties.jobLease.isNegative && !properties.jobLease.isZero) {
            "jobLease must be positive"
        }
        require(properties.jobLease <= MAX_LEASE) {
            "jobLease must not exceed $MAX_LEASE"
        }
        ownerId = LockOwnerId.from(ownerRef)
        leasePolicy = LeasePolicy.Fixed(properties.jobLease)
    }

    /** bootstrap은 한 번만 실행되며, 첫 traffic 전에 호출되지 않았다면 acquire가 지연 호출합니다. */
    @Synchronized
    fun bootstrap(): FencedBootstrapResult =
        bootstrapResult ?: runCatching { operations.bootstrap() }
            .getOrElse { FencedBootstrapResult.BackendFailure(fallbackBackendFailure()) }
            .also { bootstrapResult = it }

    /** 이번 tick에서 사용할 fenced lease를 요청합니다. */
    @Synchronized
    fun tryAcquire(now: Instant = clock.instant()): WaitlistLeaseAttempt {
        if (closed) return WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.CLOSED)
        bootstrap().failureOrNull()?.let { return WaitlistLeaseAttempt.Failed(it) }
        if (pendingRequest != null) {
            return WaitlistLeaseAttempt.Ambiguous(WaitlistLeaseFailure.RECONCILE_REQUIRED)
        }

        val request = LockRequestId.random()
        pendingRequest = PendingRequest(ownerId, request)
        val result = runCatching { operations.tryAcquire(ownerId, request, leasePolicy) }
            .getOrElse {
                pendingRequest = null
                return WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.BACKEND_FAILURE)
            }

        return when (result) {
            is LockAcquireResult.Acquired -> acquired(result.handle, now)
            is LockAcquireResult.Reentered -> reentered(result.handle, result.holdCount, now)
            is LockAcquireResult.Contended -> {
                pendingRequest = null
                WaitlistLeaseAttempt.Contended(result.remainingTtlMillis.coerceAtLeast(0L))
            }
            LockAcquireResult.TimedOut -> {
                pendingRequest = null
                WaitlistLeaseAttempt.TimedOut(WaitlistLeaseFailure.TIMEOUT)
            }
            is LockAcquireResult.Ambiguous -> {
                if (result.ownerId != ownerId || result.requestId != request) {
                    pendingRequest = null
                    WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.INTEGRITY_FAILURE)
                } else {
                    WaitlistLeaseAttempt.Ambiguous(WaitlistLeaseFailure.AMBIGUOUS)
                }
            }
            is LockAcquireResult.BackendFailure -> {
                pendingRequest = null
                WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.BACKEND_FAILURE)
            }
            is LockAcquireResult.IntegrityFailure -> {
                pendingRequest = null
                WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.INTEGRITY_FAILURE)
            }
            LockAcquireResult.CleanupPending -> {
                pendingRequest = null
                WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.CLEANUP_PENDING)
            }
            LockAcquireResult.CapacityExceeded -> {
                pendingRequest = null
                WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.CAPACITY_EXCEEDED)
            }
            LockAcquireResult.Closed -> {
                pendingRequest = null
                WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.CLOSED)
            }
        }
    }

    /** ambiguous acquire를 같은 owner/request pair로 단 한 번 확인합니다. */
    @Synchronized
    fun reconcile(now: Instant = clock.instant()): WaitlistLeaseAttempt {
        if (closed) return WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.CLOSED)
        bootstrap().failureOrNull()?.let { return WaitlistLeaseAttempt.Failed(it) }
        val pending = pendingRequest ?: return WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.RECONCILE_NOT_FOUND)
        val result = runCatching { operations.reconcile(pending.owner, pending.request) }
            .getOrElse { return WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.RECONCILE_FAILED) }

        return when (result) {
            is LockReconcileResult.Owned -> {
                pendingRequest = null
                val handle = remember(result.handle, now)
                if (result.holdCount > 1) {
                    WaitlistLeaseAttempt.Reentered(handle, result.holdCount)
                } else {
                    WaitlistLeaseAttempt.Acquired(handle)
                }
            }
            is LockReconcileResult.Queued,
            is LockReconcileResult.Ambiguous,
            -> WaitlistLeaseAttempt.Ambiguous(WaitlistLeaseFailure.AMBIGUOUS)
            LockReconcileResult.NotFound,
            LockReconcileResult.Removed,
            LockReconcileResult.Released,
            -> {
                pendingRequest = null
                WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.RECONCILE_NOT_FOUND)
            }
            LockReconcileResult.StaleGeneration -> {
                pendingRequest = null
                WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.STALE_GENERATION)
            }
            is LockReconcileResult.BackendFailure,
            is LockReconcileResult.IntegrityFailure,
            -> WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.RECONCILE_FAILED)
            LockReconcileResult.Closed -> {
                pendingRequest = null
                WaitlistLeaseAttempt.Failed(WaitlistLeaseFailure.CLOSED)
            }
        }
    }

    /** handle당 native release를 최대 한 번만 수행하고 stale ownership을 terminal 처리합니다. */
    @Synchronized
    fun release(handle: WaitlistLeaseHandle): WaitlistLeaseRelease {
        if (closed) return WaitlistLeaseRelease.CLOSED
        val activeCount = activeHandleCounts[handle] ?: return WaitlistLeaseRelease.ALREADY_RELEASED
        if (activeCount == 1) {
            activeHandleCounts.remove(handle)
        } else {
            activeHandleCounts[handle] = activeCount - 1
        }

        val result = runCatching { operations.release(handle.nativeHandle) }
            .getOrElse { return WaitlistLeaseRelease.UNKNOWN }
        return when (result) {
            is LockMutationResult.Released -> WaitlistLeaseRelease.RELEASED
            LockMutationResult.AlreadyReleased -> WaitlistLeaseRelease.ALREADY_RELEASED
            LockMutationResult.Expired -> WaitlistLeaseRelease.EXPIRED
            LockMutationResult.OwnershipLost,
            LockMutationResult.StaleGeneration,
            -> WaitlistLeaseRelease.OWNERSHIP_LOST
            LockMutationResult.Closed -> WaitlistLeaseRelease.CLOSED
            is LockMutationResult.Renewed,
            is LockMutationResult.Ambiguous,
            is LockMutationResult.BackendFailure,
            is LockMutationResult.IntegrityFailure,
            -> WaitlistLeaseRelease.UNKNOWN
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        operations.close()
    }

    private fun acquired(nativeHandle: FencedLockHandle, now: Instant): WaitlistLeaseAttempt.Acquired {
        pendingRequest = null
        return WaitlistLeaseAttempt.Acquired(remember(nativeHandle, now))
    }

    private fun reentered(
        nativeHandle: FencedLockHandle,
        holdCount: Int,
        now: Instant,
    ): WaitlistLeaseAttempt.Reentered {
        pendingRequest = null
        return WaitlistLeaseAttempt.Reentered(remember(nativeHandle, now), holdCount)
    }

    private fun remember(nativeHandle: FencedLockHandle, now: Instant): WaitlistLeaseHandle {
        val handle = WaitlistLeaseHandle(
            owner = ownerRef,
            token = WaitlistFencingToken(nativeHandle.epoch, nativeHandle.fencingToken),
            leaseUntil = now.plus(properties.jobLease),
            nativeHandle = nativeHandle,
        )
        activeHandleCounts.merge(handle, 1, Int::plus)
        return handle
    }

    private fun FencedBootstrapResult.failureOrNull(): WaitlistLeaseFailure? = when (this) {
        FencedBootstrapResult.Initialized,
        FencedBootstrapResult.AlreadyInitialized,
        -> null
        is FencedBootstrapResult.Ambiguous -> WaitlistLeaseFailure.AMBIGUOUS
        is FencedBootstrapResult.BackendFailure -> WaitlistLeaseFailure.BACKEND_FAILURE
        is FencedBootstrapResult.IntegrityFailure -> WaitlistLeaseFailure.INTEGRITY_FAILURE
        FencedBootstrapResult.Closed -> WaitlistLeaseFailure.CLOSED
    }

    private fun fallbackBackendFailure(): LockBackendFailure =
        LockBackendFailure(
            kind = io.bluetape4k.redis.lettuce.lock.LockBackendFailureKind.COMMAND,
            recoveryAction = LockRecoveryAction.RECONCILE_REQUEST,
        )

    private data class PendingRequest(
        val owner: LockOwnerId,
        val request: LockRequestId,
    )

    companion object {
        private val MAX_LEASE: Duration = Duration.ofMinutes(5)
        private val OPAQ_OWNER_PATTERN = Regex("[1-9A-HJ-NP-Za-km-z]{8}")
    }
}
