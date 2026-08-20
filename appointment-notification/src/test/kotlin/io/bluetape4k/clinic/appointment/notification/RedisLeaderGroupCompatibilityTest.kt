package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.lettuce.leaderGroupElection
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class RedisLeaderGroupCompatibilityTest {

    @Test
    fun `Redis 8 launcher에서 Lua fallback과 leader lifecycle을 검증한다`() {
        val redis = RedisServer.Launcher.redis
        val client = RedisServer.Launcher.LettuceLib.getRedisClient()
        val connection = client.connect()
        val commands = connection.sync()
        val lockName = "clinic-appointment:redis-contract:${UUID.randomUUID()}"
        val slotKey = "lg{$lockName}"

        try {
            redis.dockerImageName shouldBeEqualTo "${RedisServer.IMAGE}:${RedisServer.TAG}"
            RedisServer.TAG shouldBeEqualTo "8"
            commands.del(slotKey, "$slotKey:meta")
            commands.scriptFlush()

            val elector = connection.leaderGroupElection(
                LeaderGroupElectionOptions(
                    maxLeaders = 1,
                    waitTime = 1.seconds,
                    leaseTime = 5.seconds,
                )
            )

            elector.runIfLeader(lockName) { "leader" } shouldBeEqualTo "leader"
            elector.activeCount(lockName) shouldBeEqualTo 0
            elector.availableSlots(lockName) shouldBeEqualTo 1
        } finally {
            runCatching { commands.del(slotKey, "$slotKey:meta") }
            connection.close()
        }

        connection.isOpen.shouldBeFalse()
    }
}
