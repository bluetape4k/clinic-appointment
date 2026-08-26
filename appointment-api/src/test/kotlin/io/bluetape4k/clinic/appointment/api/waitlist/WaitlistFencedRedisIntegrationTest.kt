package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.redis.lettuce.lock.FencedLockConfig
import io.bluetape4k.redis.lettuce.lock.FencedLockHandle
import io.bluetape4k.redis.lettuce.lock.LeasePolicy
import io.bluetape4k.redis.lettuce.lock.LettuceFencedLock
import io.bluetape4k.redis.lettuce.lock.LockAcquireResult
import io.bluetape4k.redis.lettuce.lock.LockConfig
import io.bluetape4k.redis.lettuce.lock.LockOwnerId
import io.bluetape4k.redis.lettuce.lock.LockRecoveryAction
import io.bluetape4k.redis.lettuce.lock.LockRequestId
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.time.Duration
import java.util.concurrent.TimeUnit

@Execution(ExecutionMode.SAME_THREAD)
@Isolated
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class WaitlistFencedRedisIntegrationTest {

    @Test
    fun `고정 lease 만료 뒤 새 owner는 더 큰 token을 받고 이전 release는 차단된다`() {
        withRedisLockPair { first, second ->
            val properties = WaitlistDeliveryProperties(
                jobLease = Duration.ofMillis(400),
                fenceEpoch = 1,
            )
            val firstLease = FencedWaitlistLeaderLease(
                operations = LettuceWaitlistFencedLockOperations(first),
                properties = properties,
                ownerRef = Base58.randomString(8),
            )
            val secondLease = FencedWaitlistLeaderLease(
                operations = LettuceWaitlistFencedLockOperations(second),
                properties = properties,
                ownerRef = Base58.randomString(8),
            )

            firstLease.use { firstOwner ->
                secondLease.use { secondOwner ->
                    val firstHandle = firstOwner.tryAcquire().shouldBeInstanceOf<WaitlistLeaseAttempt.Acquired>().handle
                    val secondHandle = awaitAcquired(secondOwner, Duration.ofSeconds(5))

                    secondHandle.token.isStrictlyGreaterThan(firstHandle.token).shouldBeTrue()
                    (secondHandle.owner == firstHandle.owner).shouldBeFalse()
                    firstOwner.release(firstHandle) shouldBeEqualTo WaitlistLeaseRelease.OWNERSHIP_LOST
                    secondOwner.release(secondHandle) shouldBeEqualTo WaitlistLeaseRelease.RELEASED
                }
            }
        }
    }

    @Test
    fun `ambiguous 응답은 실제 Redis handle을 같은 owner request로 reconcile한다`() {
        withRedisLock { lock ->
            val properties = WaitlistDeliveryProperties(
                jobLease = Duration.ofSeconds(2),
                fenceEpoch = 1,
            )
            val operations = AmbiguousAfterAcquireOperations(LettuceWaitlistFencedLockOperations(lock))
            val lease = FencedWaitlistLeaderLease(
                operations = operations,
                properties = properties,
                ownerRef = Base58.randomString(8),
            )

            lease.use {
                it.tryAcquire().shouldBeInstanceOf<WaitlistLeaseAttempt.Ambiguous>()
                val recovered = it.reconcile().shouldBeInstanceOf<WaitlistLeaseAttempt.Acquired>()
                recovered.handle.token.epoch shouldBeEqualTo 1L
                val reconciledOwner = operations.reconciledOwner.shouldNotBeNull()
                val reconciledRequest = operations.reconciledRequest.shouldNotBeNull()
                reconciledOwner shouldBeEqualTo operations.acquiredOwner
                reconciledRequest shouldBeEqualTo operations.acquiredRequest
                it.release(recovered.handle) shouldBeEqualTo WaitlistLeaseRelease.RELEASED
            }
        }
    }

    @Test
    fun `운영 metric은 허용된 outcome과 mode만 기록하고 native identity를 노출하지 않는다`() {
        val registry = SimpleMeterRegistry()
        val metrics = WaitlistDeliveryMetrics(registry)

        metrics.recordLeaseAcquire(WaitlistLeaseMetricOutcome.ACQUIRED, Duration.ofMillis(3))
        metrics.recordSchedulerTick(DeliveryMode.ACTIVE, Duration.ofMillis(4))
        metrics.recordOwnershipLoss(WaitlistOwnershipLossSource.REDIS)

        registry.meters.forEach { meter ->
            meter.id.tags.map { it.value }.forEach { value ->
                value.assertDoesNotContain("native-owner")
                value.assertDoesNotContain("native-request")
                value.assertDoesNotContain("waitlist-delivery")
            }
        }
        registry.get(WaitlistDeliveryMetrics.LEASE_ACQUIRE_TOTAL)
            .tag("outcome", "acquired")
            .counter()
            .count() shouldBeGreaterThan 0.0
        registry.get(WaitlistDeliveryMetrics.SCHEDULER_TICK)
            .tag("mode", "active")
            .timer()
            .count() shouldBeGreaterThan 0L
    }

    private fun awaitAcquired(
        lease: FencedWaitlistLeaderLease,
        timeout: Duration,
    ): WaitlistLeaseHandle {
        val deadline = System.nanoTime() + timeout.toNanos()
        var lastAttempt: WaitlistLeaseAttempt? = null
        while (System.nanoTime() < deadline) {
            when (val attempt = lease.tryAcquire()) {
                is WaitlistLeaseAttempt.Acquired -> return attempt.handle
                is WaitlistLeaseAttempt.Reentered -> return attempt.handle
                else -> {
                    lastAttempt = attempt
                    TimeUnit.MILLISECONDS.sleep(50)
                }
            }
        }
        error("timed out waiting for a new fenced lease: $lastAttempt")
    }

    private fun withRedisLockPair(block: (LettuceFencedLock, LettuceFencedLock) -> Unit) {
        RedisClient.create(Containers.Redis.url).use { client ->
            client.connect().use { firstConnection ->
                client.connect().use { secondConnection ->
                    val resource = "waitlist-${Base58.randomString(8)}"
                    createLock(firstConnection, resource).use { first ->
                        createLock(secondConnection, resource).use { second ->
                            try {
                                block(first, second)
                            } finally {
                                deleteLockKeys(firstConnection, resource)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun withRedisLock(block: (LettuceFencedLock) -> Unit) {
        RedisClient.create(Containers.Redis.url).use { client ->
            client.connect().use { connection ->
                val resource = "waitlist-${Base58.randomString(8)}"
                createLock(connection, resource).use { lock ->
                    try {
                        block(lock)
                    } finally {
                        deleteLockKeys(connection, resource)
                    }
                }
            }
        }
    }

    private fun createLock(
        connection: StatefulRedisConnection<String, String>,
        resource: String,
    ): LettuceFencedLock = LettuceFencedLock.create(
        connection,
        resource,
        FencedLockConfig(
            lock = LockConfig(namespace = WaitlistFencedSchedulingConfiguration.WAITLIST_LOCK_NAMESPACE),
            epoch = 1,
        ),
    )

    private fun deleteLockKeys(
        connection: StatefulRedisConnection<String, String>,
        resource: String,
    ) {
        val commands = connection.sync()
        commands.keys("*${resource}*").takeIf { it.isNotEmpty() }?.let { keys ->
            commands.del(*keys.toTypedArray())
        }
    }

    private class AmbiguousAfterAcquireOperations(
        private val delegate: WaitlistFencedLockOperations,
    ) : WaitlistFencedLockOperations {
        private var ambiguous = true
        var acquiredOwner: LockOwnerId? = null
        var acquiredRequest: LockRequestId? = null
        var reconciledOwner: LockOwnerId? = null
        var reconciledRequest: LockRequestId? = null

        override fun bootstrap() = delegate.bootstrap()

        override fun tryAcquire(
            owner: LockOwnerId,
            request: LockRequestId,
            policy: LeasePolicy,
        ): LockAcquireResult<FencedLockHandle> {
            val result = delegate.tryAcquire(owner, request, policy)
            if (ambiguous && result is LockAcquireResult.Acquired) {
                ambiguous = false
                acquiredOwner = owner
                acquiredRequest = request
                return LockAcquireResult.Ambiguous(owner, request, LockRecoveryAction.RECONCILE_REQUEST)
            }
            return result
        }

        override fun reconcile(
            owner: LockOwnerId,
            request: LockRequestId,
        ) = delegate.reconcile(owner, request).also {
            reconciledOwner = owner
            reconciledRequest = request
        }

        override fun release(handle: FencedLockHandle) = delegate.release(handle)

        override fun close() = delegate.close()
    }
}

private fun String.assertDoesNotContain(fragment: String) {
    if (contains(fragment)) error("metric tag unexpectedly contained '$fragment'")
}
