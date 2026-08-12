package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.api.Test
import java.time.Duration

/** Redis 운영 URL 검증이 local fallback과 production TLS 경계를 분리하는지 확인합니다. */
class CacheConfigSecurityTest {

    private val policy = RedisCacheSecurityPolicy()

    @Test
    fun `TLS가 꺼지면 local redis URL을 허용한다`() {
        val uri = policy.validate("redis://localhost:6379", requireTls = false)

        uri.scheme shouldBeEqualTo "redis"
        uri.host shouldBeEqualTo "localhost"
    }

    @Test
    fun `TLS가 켜지면 인증된 비 loopback rediss URL을 허용한다`() {
        val uri = policy.validate(
            "rediss://cache-user:cache-secret@cache.example.internal:6380",
            requireTls = true,
        )

        uri.scheme shouldBeEqualTo "rediss"
        uri.host shouldBeEqualTo "cache.example.internal"
    }

    @Test
    fun `TLS가 켜지면 plain URI와 local host를 거부하고 credential을 노출하지 않는다`() {
        listOf(
            "redis://cache-user:cache-secret@cache.example.internal:6379",
            "rediss://cache-user:cache-secret@localhost:6380",
            "rediss://:cache-secret@cache.example.internal:6380",
            "rediss://cache-user@cache.example.internal:6380",
            "not a URI",
        ).forEach { url ->
            val failure = assertFailsWith<IllegalArgumentException> {
                policy.validate(url, requireTls = true)
            }

            failure.message.orEmpty().contains("cache-secret").shouldBeFalse()
        }
    }

    @Test
    fun `CacheConfig Redis client wiring은 TLS 정책을 먼저 적용한다`() {
        val config = CacheConfig()
        val client = config.redisClient("redis://localhost:6379", requireTls = false)
        try {
            assertFailsWith<IllegalArgumentException> {
                config.redisClient("redis://localhost:6379", requireTls = true)
            }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `Redis command timeout은 양수만 허용한다`() {
        assertFailsWith<IllegalArgumentException> {
            CacheConfig().redisClientWithTimeout(
                url = "redis://localhost:6379",
                requireTls = false,
                commandTimeout = Duration.ZERO,
            )
        }
    }
}
