package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.waitlist.WaitlistFencingToken
import io.bluetape4k.redis.lettuce.lock.FencedBootstrapResult
import io.bluetape4k.redis.lettuce.lock.FencedLockHandle
import io.bluetape4k.redis.lettuce.lock.LeasePolicy
import io.bluetape4k.redis.lettuce.lock.LockAcquireResult
import io.bluetape4k.redis.lettuce.lock.LockBackendFailure
import io.bluetape4k.redis.lettuce.lock.LockBackendFailureKind
import io.bluetape4k.redis.lettuce.lock.LockGeneration
import io.bluetape4k.redis.lettuce.lock.LockHandle
import io.bluetape4k.redis.lettuce.lock.LockIntegrityFailure
import io.bluetape4k.redis.lettuce.lock.LockIntegrityFailureKind
import io.bluetape4k.redis.lettuce.lock.LockKind
import io.bluetape4k.redis.lettuce.lock.LockMutationResult
import io.bluetape4k.redis.lettuce.lock.LockOwnerId
import io.bluetape4k.redis.lettuce.lock.LockReconcileResult
import io.bluetape4k.redis.lettuce.lock.LockRecoveryAction
import io.bluetape4k.redis.lettuce.lock.LockRequestId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class WaitlistFencedLeaderLeaseTest {

    private val now = Instant.parse("2026-08-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val properties = WaitlistDeliveryProperties(jobLease = Duration.ofSeconds(30))

    @Test
    fun `acquired and reentered expose only opaque owner and fencing token`() {
        val native = nativeHandle()
        val operations = FakeLockOperations(
            acquireResults = ArrayDeque(
                listOf(
                    LockAcquireResult.Acquired(native),
                    LockAcquireResult.Reentered(native, 2),
                )
            ),
        )
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)

        val acquired = lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Acquired>()
        acquired.handle.owner.length shouldBeEqualTo 8
        acquired.handle.owner.matches(Regex("[1-9A-HJ-NP-Za-km-z]{8}")).shouldBeTrue()
        acquired.handle.token shouldBeEqualTo WaitlistFencingToken(epoch = 7, sequence = 11)
        acquired.handle.leaseUntil shouldBeEqualTo now.plusSeconds(30)
        acquired.handle.toString().contains("LockOwnerId").shouldBeFalse()
        acquired.handle.toString().contains(acquired.handle.owner).shouldBeFalse()
        acquired.handle.toString().contains("7").shouldBeFalse()

        val reentered = lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Reentered>()
        reentered.holdCount shouldBeEqualTo 2
        operations.ownerRequests.size shouldBeEqualTo 2
            operations.ownerRequests[0].first shouldBeEqualTo operations.ownerRequests[1].first
        operations.bootstrapCalls shouldBeEqualTo 1
    }

    @Test
    fun `reentered acquisitions release one native hold per acquisition`() {
        val native = nativeHandle()
        val operations = FakeLockOperations(
            acquireResults = ArrayDeque(
                listOf(
                    LockAcquireResult.Acquired(native),
                    LockAcquireResult.Reentered(native, 2),
                ),
            ),
            releaseResult = LockMutationResult.Released(0),
        )
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)

        val first = lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Acquired>().handle
        val second = lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Reentered>().handle

        lease.release(first) shouldBeEqualTo WaitlistLeaseRelease.RELEASED
        lease.release(second) shouldBeEqualTo WaitlistLeaseRelease.RELEASED
        lease.release(second) shouldBeEqualTo WaitlistLeaseRelease.ALREADY_RELEASED
        operations.releaseCalls shouldBeEqualTo 2
    }

    @Test
    fun `contended timeout and backend failures never produce a business handle`() {
        val failure = LockBackendFailure(LockBackendFailureKind.TIMEOUT, LockRecoveryAction.RECONCILE_REQUEST)
        val operations = FakeLockOperations(
            acquireResults = ArrayDeque(
                listOf(
                    LockAcquireResult.Contended(120),
                    LockAcquireResult.TimedOut,
                    LockAcquireResult.BackendFailure(failure),
                )
            ),
        )
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)

        lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Contended>().remainingTtlMillis shouldBeEqualTo 120
        lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.TimedOut>().category shouldBeEqualTo
            WaitlistLeaseFailure.TIMEOUT
        lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Failed>().category shouldBeEqualTo
            WaitlistLeaseFailure.BACKEND_FAILURE
        operations.releaseCalls shouldBeEqualTo 0
    }

    @Test
    fun `ambiguous acquisition reconciles with the identical owner and request`() {
        val native = nativeHandle()
        val operations = FakeLockOperations(
            ambiguousAcquire = true,
            reconcileResult = LockReconcileResult.Owned(native, holdCount = 1, remainingTtlMillis = 28_000),
        )
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)

        val ambiguous = lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Ambiguous>()
        ambiguous.category shouldBeEqualTo WaitlistLeaseFailure.AMBIGUOUS
        lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Ambiguous>().category shouldBeEqualTo
            WaitlistLeaseFailure.RECONCILE_REQUIRED

        val recovered = lease.reconcile(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Acquired>()
        recovered.handle.token shouldBeEqualTo WaitlistFencingToken(7, 11)
        operations.reconcileCalls shouldBeEqualTo 1
        operations.reconciledOwner shouldBeEqualTo operations.ownerRequests.single().first
        operations.reconciledRequest shouldBeEqualTo operations.ownerRequests.single().second
    }

    @Test
    fun `ownership loss terminalizes a handle and duplicate release is idempotent`() {
        val native = nativeHandle()
        val operations = FakeLockOperations(
            acquireResults = ArrayDeque(listOf(LockAcquireResult.Acquired(native))),
            releaseResult = LockMutationResult.OwnershipLost,
        )
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)
        val handle = lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Acquired>().handle

        lease.release(handle) shouldBeEqualTo WaitlistLeaseRelease.OWNERSHIP_LOST
        lease.release(handle) shouldBeEqualTo WaitlistLeaseRelease.ALREADY_RELEASED
        operations.releaseCalls shouldBeEqualTo 1
    }

    @Test
    fun `unknown release retains a pending handle for one bounded retry`() {
        val failure = LockBackendFailure(LockBackendFailureKind.COMMAND, LockRecoveryAction.RECONCILE_REQUEST)
        val operations = FakeLockOperations(
            acquireResults = ArrayDeque(listOf(LockAcquireResult.Acquired(nativeHandle()))),
            releaseResults = ArrayDeque(
                listOf(
                    LockMutationResult.BackendFailure(failure),
                    LockMutationResult.Released(0),
                ),
            ),
        )
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)
        val handle = lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Acquired>().handle

        lease.release(handle) shouldBeEqualTo WaitlistLeaseRelease.UNKNOWN
        lease.release(handle) shouldBeEqualTo WaitlistLeaseRelease.RELEASED
        lease.release(handle) shouldBeEqualTo WaitlistLeaseRelease.ALREADY_RELEASED
        operations.releaseCalls shouldBeEqualTo 2
    }

    @Test
    fun `close prevents new acquisition and closes native operations once`() {
        val operations = FakeLockOperations()
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)

        lease.close()
        lease.close()

        lease.tryAcquire(now).shouldBeInstanceOf<WaitlistLeaseAttempt.Failed>().category shouldBeEqualTo
            WaitlistLeaseFailure.CLOSED
        operations.closeCalls shouldBeEqualTo 1
        operations.acquireCalls shouldBeEqualTo 0
    }

    @Test
    fun `lease duration must be bounded`() {
        assertFailsWith<IllegalArgumentException> {
            FencedWaitlistLeaderLease(
                operations = FakeLockOperations(),
                properties = properties.copy(jobLease = Duration.ofMinutes(6)),
                clock = clock,
            )
        }
    }

    private fun nativeHandle(): FencedLockHandle {
        val owner = LockOwnerId.from("native-owner")
        val request = LockRequestId.from("native-request")
        return FencedLockHandle(
            lock = LockHandle(
                objectFingerprint = "waitlist-delivery",
                ownerId = owner,
                generation = LockGeneration(1),
                requestId = request,
                leasePolicy = LeasePolicy.Fixed(Duration.ofSeconds(30)),
                kind = LockKind.FENCED,
            ),
            epoch = 7,
            fencingToken = 11,
        )
    }

    private class FakeLockOperations(
        private val acquireResults: ArrayDeque<LockAcquireResult<FencedLockHandle>> = ArrayDeque(),
        private val ambiguousAcquire: Boolean = false,
        private val reconcileResult: LockReconcileResult<FencedLockHandle> = LockReconcileResult.NotFound,
        private val releaseResult: LockMutationResult<FencedLockHandle> = LockMutationResult.AlreadyReleased,
        private val releaseResults: ArrayDeque<LockMutationResult<FencedLockHandle>> = ArrayDeque(),
    ) : WaitlistFencedLockOperations {
        val ownerRequests = mutableListOf<Pair<LockOwnerId, LockRequestId>>()
        var reconciledOwner: LockOwnerId? = null
        var reconciledRequest: LockRequestId? = null
        var bootstrapCalls = 0
        var acquireCalls = 0
        var reconcileCalls = 0
        var releaseCalls = 0
        var closeCalls = 0

        override fun bootstrap(): FencedBootstrapResult {
            bootstrapCalls++
            return FencedBootstrapResult.Initialized
        }

        override fun tryAcquire(
            owner: LockOwnerId,
            request: LockRequestId,
            policy: LeasePolicy,
        ): LockAcquireResult<FencedLockHandle> {
            ownerRequests += owner to request
            acquireCalls++
            if (ambiguousAcquire) {
                return LockAcquireResult.Ambiguous(owner, request, LockRecoveryAction.RECONCILE_REQUEST)
            }
            return acquireResults.removeFirstOrNull() ?: LockAcquireResult.TimedOut
        }

        override fun reconcile(
            owner: LockOwnerId,
            request: LockRequestId,
        ): LockReconcileResult<FencedLockHandle> {
            reconciledOwner = owner
            reconciledRequest = request
            reconcileCalls++
            return reconcileResult
        }

        override fun release(handle: FencedLockHandle): LockMutationResult<FencedLockHandle> {
            releaseCalls++
            return releaseResults.removeFirstOrNull() ?: releaseResult
        }

        override fun close() {
            closeCalls++
        }
    }
}
