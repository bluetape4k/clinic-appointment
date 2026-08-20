package io.bluetape4k.clinic.appointment.api.test

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.Test

class RedisServerContractTest {

    @Test
    fun `API 테스트는 Redis 8 launcher singleton을 사용한다`() {
        (Containers.Redis === RedisServer.Launcher.redis).shouldBeTrue()
        Containers.Redis.dockerImageName shouldBeEqualTo "${RedisServer.IMAGE}:${RedisServer.TAG}"
        RedisServer.TAG shouldBeEqualTo "8"
    }
}
