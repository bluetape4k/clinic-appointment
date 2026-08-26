package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.waitlist.WaitlistFencingToken
import io.bluetape4k.redis.lettuce.lock.FencedBootstrapResult
import io.bluetape4k.redis.lettuce.lock.FencedLockHandle
import io.bluetape4k.redis.lettuce.lock.LeasePolicy
import io.bluetape4k.redis.lettuce.lock.LockAcquireResult
import io.bluetape4k.redis.lettuce.lock.LockGeneration
import io.bluetape4k.redis.lettuce.lock.LockHandle
import io.bluetape4k.redis.lettuce.lock.LockKind
import io.bluetape4k.redis.lettuce.lock.LockMutationResult
import io.bluetape4k.redis.lettuce.lock.LockOwnerId
import io.bluetape4k.redis.lettuce.lock.LockReconcileResult
import io.bluetape4k.redis.lettuce.lock.LockRequestId
import io.bluetape4k.redis.lettuce.lock.LockRecoveryAction
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class WaitlistFencedDeliverySchedulingTest {

    private val now = Instant.parse("2026-08-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val properties = WaitlistDeliveryProperties(enabled = true, jobLease = Duration.ofSeconds(30))

    @Test
    fun `acquired lease runs safety jobs before typed dispatch and records allowlisted metrics`() {
        val operations = FakeLockOperations(acquireResults = ArrayDeque(listOf(LockAcquireResult.Acquired(nativeHandle())))
        )
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)
        val calls = mutableListOf<String>()
        val registry = SimpleMeterRegistry()
        val dispatcher = WaitlistFencedVacancyDispatcher { limit, instant, handle ->
            calls += "dispatch:$limit:$instant:${handle.token.epoch}:${handle.token.sequence}"
            4
        }
        val runner = WaitlistFencedDeliverySchedulingRunner(
            lease = lease,
            dispatcher = dispatcher,
            expiry = WaitlistOfferExpiryRunner { _, _ -> calls += "expiry"; 1 },
            suppression = WaitlistNotificationSuppressionRunner { _, _ -> calls += "suppression"; 2 },
            holdReconciler = WaitlistHoldReconciler { _, _ -> calls += "reconcile"; 3 },
            properties = properties,
            metrics = WaitlistDeliveryMetrics(registry),
            clock = clock,
        )

        val result = runner.tick()

        result.mode shouldBeEqualTo DeliveryMode.ACTIVE
        result.leaseOutcome shouldBeEqualTo WaitlistFencedLeaseOutcome.ACQUIRED
        result.dispatchCount shouldBeEqualTo 4
        result.expiryCount shouldBeEqualTo 1
        result.suppressionCount shouldBeEqualTo 2
        result.holdReconcileCount shouldBeEqualTo 3
        result.duration.isNegative.shouldBeFalse()
        calls shouldBeEqualTo listOf(
            "expiry",
            "suppression",
            "reconcile",
            "dispatch:25:$now:7:11",
        )
        registry.get(WaitlistDeliveryMetrics.LEASE_ACQUIRE_TOTAL)
            .tag("outcome", "acquired").counter().count() shouldBeEqualTo 1.0
        registry.get(WaitlistDeliveryMetrics.SCHEDULER_TICK)
            .tag("mode", "active").timer().count() shouldBeEqualTo 1L
    }

    @Test
    fun `contended lease skips every database mutating port`() {
        val operations = FakeLockOperations(
            acquireResults = ArrayDeque(listOf(io.bluetape4k.redis.lettuce.lock.LockAcquireResult.Contended(500))),
        )
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)
        var calls = 0
        val registry = SimpleMeterRegistry()
        val runner = WaitlistFencedDeliverySchedulingRunner(
            lease = lease,
            dispatcher = WaitlistFencedVacancyDispatcher { _, _, _ -> calls++; 1 },
            expiry = WaitlistOfferExpiryRunner { _, _ -> calls++; 1 },
            suppression = WaitlistNotificationSuppressionRunner { _, _ -> calls++; 1 },
            holdReconciler = WaitlistHoldReconciler { _, _ -> calls++; 1 },
            properties = properties,
            metrics = WaitlistDeliveryMetrics(registry),
            clock = clock,
        )

        val result = runner.tick()

        result.leaseOutcome shouldBeEqualTo WaitlistFencedLeaseOutcome.CONTENDED
        result.leaderAcquired.shouldBeFalse()
        result.dispatchCount shouldBeEqualTo 0
        calls shouldBeEqualTo 0
        registry.get(WaitlistDeliveryMetrics.LEASE_ACQUIRE_TOTAL)
            .tag("outcome", "contended").counter().count() shouldBeEqualTo 1.0
    }

    @Test
    fun `ambiguous lease reconciles once before running jobs`() {
        val native = nativeHandle()
        val operations = FakeLockOperations(
            ambiguousAcquire = true,
            reconcileResult = LockReconcileResult.Owned(native, holdCount = 1, remainingTtlMillis = 28_000),
        )
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)
        val calls = mutableListOf<String>()
        val runner = WaitlistFencedDeliverySchedulingRunner(
            lease = lease,
            dispatcher = WaitlistFencedVacancyDispatcher { _, _, handle ->
                handle.token shouldBeEqualTo WaitlistFencingToken(7, 11)
                calls += "dispatch"
                1
            },
            expiry = WaitlistOfferExpiryRunner { _, _ -> calls += "expiry"; 1 },
            suppression = WaitlistNotificationSuppressionRunner { _, _ -> calls += "suppression"; 1 },
            holdReconciler = WaitlistHoldReconciler { _, _ -> calls += "reconcile"; 1 },
            properties = properties,
            metrics = WaitlistDeliveryMetrics(SimpleMeterRegistry()),
            clock = clock,
        )

        val result = runner.tick()

        result.leaseOutcome shouldBeEqualTo WaitlistFencedLeaseOutcome.ACQUIRED
        operations.reconcileCalls shouldBeEqualTo 1
        calls shouldBeEqualTo listOf("expiry", "suppression", "reconcile", "dispatch")
    }

    @Test
    fun `close and concurrent tick gate prevent a second execution`() {
        val operations = FakeLockOperations(acquireResults = ArrayDeque(listOf(LockAcquireResult.Acquired(nativeHandle()))))
        val lease = FencedWaitlistLeaderLease(operations, properties, clock)
        val runner = WaitlistFencedDeliverySchedulingRunner(
            lease = lease,
            dispatcher = WaitlistFencedVacancyDispatcher { _, _, _ -> 1 },
            expiry = WaitlistOfferExpiryRunner { _, _ -> 1 },
            suppression = WaitlistNotificationSuppressionRunner { _, _ -> 1 },
            holdReconciler = WaitlistHoldReconciler { _, _ -> 1 },
            properties = properties,
            metrics = WaitlistDeliveryMetrics(SimpleMeterRegistry()),
            clock = clock,
        )

        runner.close()
        val result = runner.tick()

        result.leaseOutcome shouldBeEqualTo WaitlistFencedLeaseOutcome.CLOSED
        result.leaderAcquired.shouldBeFalse()
        operations.acquireCalls shouldBeEqualTo 0
    }

    @Test
    fun `fence epoch must not be negative`() {
        assertFailsWith<IllegalArgumentException> {
            WaitlistDeliveryProperties(fenceEpoch = -1)
        }
    }

    private fun nativeHandle(): FencedLockHandle = FencedLockHandle(
        lock = LockHandle(
            objectFingerprint = "waitlist-delivery",
            ownerId = LockOwnerId.from("native-owner"),
            generation = LockGeneration(1),
            requestId = LockRequestId.from("native-request"),
            leasePolicy = LeasePolicy.Fixed(Duration.ofSeconds(30)),
            kind = LockKind.FENCED,
        ),
        epoch = 7,
        fencingToken = 11,
    )

    private class FakeLockOperations(
        private val acquireResults: ArrayDeque<LockAcquireResult<FencedLockHandle>> = ArrayDeque(),
        private val ambiguousAcquire: Boolean = false,
        private val reconcileResult: LockReconcileResult<FencedLockHandle> = LockReconcileResult.NotFound,
    ) : WaitlistFencedLockOperations {
        var reconcileCalls = 0
        var acquireCalls = 0

        override fun bootstrap(): FencedBootstrapResult = FencedBootstrapResult.Initialized

        override fun tryAcquire(
            owner: LockOwnerId,
            request: LockRequestId,
            policy: LeasePolicy,
        ): LockAcquireResult<FencedLockHandle> {
            acquireCalls++
            return if (ambiguousAcquire) {
                LockAcquireResult.Ambiguous(owner, request, LockRecoveryAction.RECONCILE_REQUEST)
            } else {
                acquireResults.removeFirstOrNull() ?: LockAcquireResult.TimedOut
            }
        }

        override fun reconcile(owner: LockOwnerId, request: LockRequestId): LockReconcileResult<FencedLockHandle> {
            reconcileCalls++
            return reconcileResult
        }

        override fun release(handle: FencedLockHandle): LockMutationResult<FencedLockHandle> =
            LockMutationResult.Released(0)

        override fun close() = Unit
    }
}
