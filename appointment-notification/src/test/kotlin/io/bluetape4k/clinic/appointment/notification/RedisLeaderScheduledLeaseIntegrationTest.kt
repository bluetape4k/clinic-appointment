package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.lettuce.LettuceLeaderElectorFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** `@LeaderScheduled`가 사용하는 단일 Redis lease의 만료와 다음 tick 재취득을 검증합니다. */
internal class RedisLeaderScheduledLeaseIntegrationTest {

    @Test
    fun `Redis 8점8에서 고정 lease가 만료되면 다음 tick이 리더를 재취득한다`() {
        val redis = Redis88Launcher.redis
        val clientA = Redis88Launcher.client()
        val clientB = Redis88Launcher.client()
        val connectionA = clientA.connect()
        val connectionB = clientB.connect()
        val executor = Executors.newFixedThreadPool(2)
        val lockName = "clinic-appointment:reminder-lease:${UUID.randomUUID()}"
        val leaseOptions = LeaderElectionOptions(
            waitTime = 4.seconds,
            leaseTime = 1.seconds,
            nodeId = "clinic-appointment-lease-test-${UUID.randomUUID()}",
            autoExtend = false,
        )
        val electorA = LettuceLeaderElectorFactory(connectionA).create(leaseOptions)
        val electorB = LettuceLeaderElectorFactory(connectionB).create(
            leaseOptions.copy(nodeId = "clinic-appointment-lease-test-${UUID.randomUUID()}"),
        )
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)

        try {
            redis.dockerImageName shouldBeEqualTo Redis88Launcher.IMAGE_NAME
            Redis88Launcher.TAG shouldBeEqualTo "8.8"

            val firstStartedAt = System.nanoTime()
            val first = CompletableFuture.supplyAsync({
                electorA.runIfLeader(lockName) {
                    firstStarted.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS).shouldBeTrue()
                    "first"
                }
            }, executor)

            firstStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()

            val second = CompletableFuture.supplyAsync({
                electorB.runIfLeader(lockName) { "second" }
            }, executor)

            second.get(8, TimeUnit.SECONDS) shouldBeEqualTo "second"
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstStartedAt)
            (elapsedMillis >= 500).shouldBeTrue()

            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS) shouldBeEqualTo "first"
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            connectionA.close()
            connectionB.close()
            clientA.shutdown()
            clientB.shutdown()
        }

        connectionA.isOpen shouldBeEqualTo false
        connectionB.isOpen shouldBeEqualTo false
    }
}
