package io.bluetape4k.clinic.appointment.api.test

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class RedisServerContractTest {

    @Test
    fun `API 테스트는 Redis 8점 8 명시적 launcher와 이미지 계약을 사용한다`() {
        (Containers.Redis === Redis88Launcher.redis).shouldBeTrue()
        Containers.Redis.dockerImageName shouldBeEqualTo Redis88Launcher.IMAGE_NAME
        Redis88Launcher.TAG shouldBeEqualTo "8.8"
    }
}
