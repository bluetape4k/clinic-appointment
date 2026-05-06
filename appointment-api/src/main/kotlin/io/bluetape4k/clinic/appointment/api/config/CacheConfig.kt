package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.cache.LettuceCaches
import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.logging.KLogging
import io.lettuce.core.RedisClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {

    companion object : KLogging() {
        private const val MASTER_CACHE_LOCAL_SIZE = 500L
        private val MASTER_CACHE_LOCAL_TTL: Duration = Duration.ofMinutes(10)
        private val MASTER_CACHE_REDIS_TTL: Duration = Duration.ofHours(1)
    }

    @Bean(destroyMethod = "shutdown")
    fun redisClient(
        @Value("\${spring.data.redis.url:redis://localhost:6379}") url: String,
    ): RedisClient = RedisClient.create(url)

    @Bean(destroyMethod = "close")
    fun clinicDoctorsCache(redisClient: RedisClient): NearCacheOperations<List<DoctorRecord>> =
        LettuceCaches.nearCache(redisClient) {
            cacheName = "clinic-doctors"
            maxLocalSize = MASTER_CACHE_LOCAL_SIZE
            frontExpireAfterWrite = MASTER_CACHE_LOCAL_TTL
            redisTtl = MASTER_CACHE_REDIS_TTL
        }

    @Bean(destroyMethod = "close")
    fun clinicEquipmentsCache(redisClient: RedisClient): NearCacheOperations<List<EquipmentRecord>> =
        LettuceCaches.nearCache(redisClient) {
            cacheName = "clinic-equipments"
            maxLocalSize = MASTER_CACHE_LOCAL_SIZE
            frontExpireAfterWrite = MASTER_CACHE_LOCAL_TTL
            redisTtl = MASTER_CACHE_REDIS_TTL
        }

    @Bean(destroyMethod = "close")
    fun clinicTreatmentTypesCache(redisClient: RedisClient): NearCacheOperations<List<TreatmentTypeRecord>> =
        LettuceCaches.nearCache(redisClient) {
            cacheName = "clinic-treatment-types"
            maxLocalSize = MASTER_CACHE_LOCAL_SIZE
            frontExpireAfterWrite = MASTER_CACHE_LOCAL_TTL
            redisTtl = MASTER_CACHE_REDIS_TTL
        }

    @Bean
    fun cacheManager(
        clinicDoctorsCache: NearCacheOperations<List<DoctorRecord>>,
        clinicEquipmentsCache: NearCacheOperations<List<EquipmentRecord>>,
        clinicTreatmentTypesCache: NearCacheOperations<List<TreatmentTypeRecord>>,
    ): CacheManager = NearCacheCacheManager(
        listOf(
            NearCacheAdapter("clinic-doctors", clinicDoctorsCache),
            NearCacheAdapter("clinic-equipments", clinicEquipmentsCache),
            NearCacheAdapter("clinic-treatment-types", clinicTreatmentTypesCache),
        )
    )
}
