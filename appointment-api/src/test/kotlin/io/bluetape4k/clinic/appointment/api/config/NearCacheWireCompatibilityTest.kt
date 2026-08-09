package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.lettuce.core.RedisClient
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NearCacheWireCompatibilityTest {

    private lateinit var redisUrl: String

    @BeforeAll
    fun startRedis() {
        redisUrl = Containers.Redis.url
    }

    @Test
    fun `의사 캐시는 v2 Redis namespace에서 독립 client로 round-trip 한다`() {
        verifyRoundTrip(
            logicalName = CacheConfig.DOCTORS_CACHE_NAME,
            remoteName = CacheConfig.DOCTORS_REMOTE_CACHE_NAME,
            expected = listOf(
                DoctorRecord(
                    id = 11L,
                    clinicId = 7L,
                    name = "김의사",
                    specialty = "내과",
                    maxConcurrentPatients = 2,
                )
            ),
            cacheFactory = CacheConfig::clinicDoctorsCache,
        )
    }

    @Test
    fun `장비 캐시는 v2 Redis namespace에서 독립 client로 round-trip 한다`() {
        verifyRoundTrip(
            logicalName = CacheConfig.EQUIPMENTS_CACHE_NAME,
            remoteName = CacheConfig.EQUIPMENTS_REMOTE_CACHE_NAME,
            expected = listOf(
                EquipmentRecord(
                    id = 21L,
                    clinicId = 7L,
                    name = "초음파",
                    usageDurationMinutes = 30,
                    quantity = 2,
                )
            ),
            cacheFactory = CacheConfig::clinicEquipmentsCache,
        )
    }

    @Test
    fun `진료 유형 캐시는 v2 Redis namespace에서 독립 client로 round-trip 한다`() {
        verifyRoundTrip(
            logicalName = CacheConfig.TREATMENT_TYPES_CACHE_NAME,
            remoteName = CacheConfig.TREATMENT_TYPES_REMOTE_CACHE_NAME,
            expected = listOf(
                TreatmentTypeRecord(
                    id = 31L,
                    clinicId = 7L,
                    name = "초진",
                    defaultDurationMinutes = 30,
                    consultationMethod = "IN_PERSON",
                )
            ),
            cacheFactory = CacheConfig::clinicTreatmentTypesCache,
        )
    }

    private fun <T : Any> verifyRoundTrip(
        logicalName: String,
        remoteName: String,
        expected: List<T>,
        cacheFactory: (CacheConfig, RedisClient) -> NearCacheOperations<List<T>>,
    ) {
        val cacheKey = "1:7"
        val v2Key = "$remoteName:$cacheKey"
        val v1Key = "$logicalName:$cacheKey"
        val cleanupActions = mutableListOf<() -> Unit>()
        var primaryFailure: Throwable? = null

        try {
            val config = CacheConfig()
            val writerClient = RedisClient.create(redisUrl).also { client ->
                cleanupActions += { client.shutdown() }
            }
            val readerClient = RedisClient.create(redisUrl).also { client ->
                cleanupActions += { client.shutdown() }
            }
            val writerCache = cacheFactory(config, writerClient).also { cache ->
                cleanupActions += { cache.close() }
            }
            val readerCache = cacheFactory(config, readerClient).also { cache ->
                cleanupActions += { cache.close() }
            }
            val rawConnection = writerClient.connect().also { connection ->
                cleanupActions += { connection.close() }
            }
            val rawCommands = rawConnection.sync()
            cleanupActions += { rawCommands.unlink(v2Key, v1Key) }

            rawCommands.unlink(v2Key, v1Key)
            writerCache.put(cacheKey, expected)

            readerCache.get(cacheKey) shouldBeEqualTo expected
            rawCommands.exists(v2Key) shouldBeEqualTo 1L
            rawCommands.exists(v1Key) shouldBeEqualTo 0L
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = cleanupActions.asReversed().mapNotNull { action ->
                runCatching(action).exceptionOrNull()
            }
            if (cleanupFailures.isNotEmpty()) {
                primaryFailure?.let { primary ->
                    cleanupFailures.forEach(primary::addSuppressed)
                } ?: throw AssertionError("Redis cache test cleanup failed").also { failure ->
                    cleanupFailures.forEach(failure::addSuppressed)
                }
            }
        }
    }
}
