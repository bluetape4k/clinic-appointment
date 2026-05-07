package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.logging.KLogging
import io.lettuce.core.RedisClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class CacheConfig {
    companion object : KLogging()

    @Bean(destroyMethod = "shutdown")
    fun redisClient(
        @Value("\${spring.data.redis.url:redis://localhost:6379}") redisUrl: String,
    ): RedisClient = RedisClient.create(redisUrl)
}
