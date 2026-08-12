package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.cache.LettuceCaches
import io.bluetape4k.cache.nearcache.LettuceNearCacheConfig
import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.io.compressor.LZ4Compressor
import io.bluetape4k.io.serializer.BinarySerializer
import io.bluetape4k.io.serializer.CompressableBinarySerializer
import io.bluetape4k.io.serializer.ForyBinarySerializer
import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.lettuce.codec.LettuceBinaryCodecs
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.RedisCodec
import org.apache.fory.Fory
import org.apache.fory.ThreadSafeFory
import org.apache.fory.config.CompatibleMode
import org.apache.fory.config.Language
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
        internal const val DOCTORS_CACHE_NAME = "clinic-doctors"
        internal const val EQUIPMENTS_CACHE_NAME = "clinic-equipments"
        internal const val TREATMENT_TYPES_CACHE_NAME = "clinic-treatment-types"

        internal const val DOCTORS_REMOTE_CACHE_NAME = "clinic-doctors-v3"
        internal const val EQUIPMENTS_REMOTE_CACHE_NAME = "clinic-equipments-v3"
        internal const val TREATMENT_TYPES_REMOTE_CACHE_NAME = "clinic-treatment-types-v3"

        internal const val DOCTOR_REGISTRATION_ID = 1001
        internal const val EQUIPMENT_REGISTRATION_ID = 1002
        internal const val TREATMENT_TYPE_REGISTRATION_ID = 1003

        internal val secureThreadSafeFory: ThreadSafeFory by lazy {
            Fory.builder()
                .withLanguage(Language.JAVA)
                .withCompatibleMode(CompatibleMode.COMPATIBLE)
                .withRefTracking(true)
                .withRefCopy(true)
                .withStringCompressed(true)
                .withAsyncCompilation(true)
                .withCodegen(true)
                .requireClassRegistration(true)
                .withDeserializeUnknownClass(false)
                .withMaxDepth(32)
                .withMaxGraphMemoryBytes(8L * 1024 * 1024)
                .buildThreadSafeForyPool(4)
                .also { threadSafeFory ->
                    threadSafeFory.register(DoctorRecord::class.java, DOCTOR_REGISTRATION_ID)
                    threadSafeFory.register(EquipmentRecord::class.java, EQUIPMENT_REGISTRATION_ID)
                    threadSafeFory.register(
                        TreatmentTypeRecord::class.java,
                        TREATMENT_TYPE_REGISTRATION_ID,
                    )
                }
        }

        internal val secureCacheSerializer: BinarySerializer by lazy {
            val fory = secureThreadSafeFory
            CompressableBinarySerializer(ForyBinarySerializer(fory), LZ4Compressor())
        }

        private const val MASTER_CACHE_LOCAL_SIZE = 500L
        private val MASTER_CACHE_LOCAL_TTL: Duration = Duration.ofMinutes(10)
        private val MASTER_CACHE_REDIS_TTL: Duration = Duration.ofHours(1)
        private val DEFAULT_REDIS_COMMAND_TIMEOUT: Duration = Duration.ofSeconds(3)
    }

    @Bean(destroyMethod = "shutdown")
    fun redisClient(
        @Value("\${spring.data.redis.url:redis://localhost:6379}") url: String,
        @Value("\${scheduling.cache.redis.require-tls:false}") requireTls: Boolean,
        @Value("\${scheduling.cache.redis.command-timeout:3s}") commandTimeout: Duration,
    ): RedisClient = createRedisClient(url, requireTls, commandTimeout)

    /**
     * 테스트에서 Spring 바인딩 없이 TLS URL 정책을 검증하기 위한 기본 timeout wiring이다.
     */
    internal fun redisClient(url: String, requireTls: Boolean): RedisClient =
        createRedisClient(url, requireTls, DEFAULT_REDIS_COMMAND_TIMEOUT)

    /**
     * Redis 명령 timeout을 명시적으로 고정한 client를 생성한다.
     *
     * Near-cache의 RESP3 `CLIENT TRACKING OFF` 정리는 Redis가 응답하지 않는
     * 종료 경로에서도 bounded wait를 가져야 Spring context와 Testcontainers가
     * 정해진 순서로 종료될 수 있다.
     */
    internal fun redisClientWithTimeout(
        url: String,
        requireTls: Boolean,
        commandTimeout: Duration,
    ): RedisClient = createRedisClient(url, requireTls, commandTimeout)

    private fun createRedisClient(
        url: String,
        requireTls: Boolean,
        commandTimeout: Duration,
    ): RedisClient {
        require(!commandTimeout.isNegative && !commandTimeout.isZero) {
            "Redis command timeout must be positive"
        }
        val redisUri = RedisURI.create(RedisCacheSecurityPolicy().validate(url, requireTls).toString())
        redisUri.timeout = commandTimeout
        return RedisClient.create(redisUri)
    }

    @Bean(destroyMethod = "close")
    fun clinicDoctorsCache(redisClient: RedisClient): NearCacheOperations<List<DoctorRecord>> =
        nearCache(
            redisClient = redisClient,
            codec = LettuceBinaryCodecs.codec<List<DoctorRecord>>(secureCacheSerializer),
            cacheName = DOCTORS_REMOTE_CACHE_NAME,
        )

    @Bean(destroyMethod = "close")
    fun clinicEquipmentsCache(redisClient: RedisClient): NearCacheOperations<List<EquipmentRecord>> =
        nearCache(
            redisClient = redisClient,
            codec = LettuceBinaryCodecs.codec<List<EquipmentRecord>>(secureCacheSerializer),
            cacheName = EQUIPMENTS_REMOTE_CACHE_NAME,
        )

    @Bean(destroyMethod = "close")
    fun clinicTreatmentTypesCache(redisClient: RedisClient): NearCacheOperations<List<TreatmentTypeRecord>> =
        nearCache(
            redisClient = redisClient,
            codec = LettuceBinaryCodecs.codec<List<TreatmentTypeRecord>>(secureCacheSerializer),
            cacheName = TREATMENT_TYPES_REMOTE_CACHE_NAME,
        )

    private fun <V : Any> nearCache(
        redisClient: RedisClient,
        codec: RedisCodec<String, V>,
        cacheName: String,
    ): NearCacheOperations<V> = LettuceCaches.nearCache(
        redisClient,
        codec,
        LettuceNearCacheConfig(
            cacheName = cacheName,
            maxLocalSize = MASTER_CACHE_LOCAL_SIZE,
            frontExpireAfterWrite = MASTER_CACHE_LOCAL_TTL,
            frontExpireAfterAccess = null,
            redisTtl = MASTER_CACHE_REDIS_TTL,
            useRespProtocol3 = true,
            recordStats = false,
        ),
    )

    @Bean
    fun cacheManager(
        clinicDoctorsCache: NearCacheOperations<List<DoctorRecord>>,
        clinicEquipmentsCache: NearCacheOperations<List<EquipmentRecord>>,
        clinicTreatmentTypesCache: NearCacheOperations<List<TreatmentTypeRecord>>,
    ): CacheManager = NearCacheCacheManager(
        listOf(
            NearCacheAdapter(DOCTORS_CACHE_NAME, clinicDoctorsCache),
            NearCacheAdapter(EQUIPMENTS_CACHE_NAME, clinicEquipmentsCache),
            NearCacheAdapter(TREATMENT_TYPES_CACHE_NAME, clinicTreatmentTypesCache),
        )
    )
}
