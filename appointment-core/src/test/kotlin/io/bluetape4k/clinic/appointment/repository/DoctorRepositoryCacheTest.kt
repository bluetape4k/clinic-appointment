package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.cache.LettuceCaches
import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.lettuce.core.RedisClient
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

class DoctorRepositoryCacheTest : AbstractExposedTest() {

    companion object : KLogging() {

        private val redis: RedisServer by lazy { RedisServer.Launcher.redis }
        private lateinit var redisClient: RedisClient

        private val allTables = arrayOf(Clinics, Doctors)

        @JvmStatic
        @BeforeAll
        fun setupRedis() {
            redisClient = RedisClient.create(redis.url)
            ShutdownQueue.register { redisClient.shutdown() }
        }

        @JvmStatic
        @AfterAll
        fun teardownRedis() {
            if (::redisClient.isInitialized) {
                redisClient.shutdown()
            }
        }

        private fun createCache(name: String): NearCacheOperations<List<DoctorRecord>> =
            LettuceCaches.nearCache(redisClient) {
                cacheName = name
                maxLocalSize = 100
                frontExpireAfterWrite = Duration.ofMinutes(1)
                redisTtl = Duration.ofMinutes(5)
            }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `findByClinicId - DB 조회 결과를 캐시에 저장하고 재사용`(testDB: TestDB) {
        val cache = createCache("test-doctors-01")
        val repo = DoctorRepository(clinicDoctorsCache = cache)

        withTables(testDB, *allTables) {
            val clinicId = Clinics.insertAndGetId {
                it[name] = "테스트 병원"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 3
            }.value

            Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId
                it[name] = "김의사"
                it[specialty] = "내과"
                it[providerType] = "DOCTOR"
            }

            val result1 = repo.findByClinicId(clinicId)
            result1 shouldHaveSize 1
            result1[0].name shouldBeEqualTo "김의사"
            result1[0].specialty shouldBeEqualTo "내과"

            val result2 = repo.findByClinicId(clinicId)
            result2 shouldHaveSize 1
            result2[0].name shouldBeEqualTo result1[0].name
        }

        cache.close()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `findByClinicId - 캐시 없이도 정상 동작`(testDB: TestDB) {
        val repo = DoctorRepository(clinicDoctorsCache = null)

        withTables(testDB, *allTables) {
            val clinicId = Clinics.insertAndGetId {
                it[name] = "캐시없는 병원"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }.value

            val empty = repo.findByClinicId(clinicId)
            empty shouldHaveSize 0

            Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId
                it[name] = "박의사"
                it[specialty] = "피부과"
            }

            val result = repo.findByClinicId(clinicId)
            result shouldHaveSize 1
            result[0].name shouldBeEqualTo "박의사"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `findByClinicId - 다른 clinicId는 별도 캐시 키로 독립 관리`(testDB: TestDB) {
        val cache = createCache("test-doctors-02")
        val repo = DoctorRepository(clinicDoctorsCache = cache)

        withTables(testDB, *allTables) {
            val clinicId1 = Clinics.insertAndGetId {
                it[name] = "병원 A"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }.value

            val clinicId2 = Clinics.insertAndGetId {
                it[name] = "병원 B"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }.value

            Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId1
                it[name] = "의사 A"
            }
            Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId2
                it[name] = "의사 B1"
            }
            Doctors.insertAndGetId {
                it[Doctors.clinicId] = clinicId2
                it[name] = "의사 B2"
            }

            repo.findByClinicId(clinicId1) shouldHaveSize 1
            repo.findByClinicId(clinicId2) shouldHaveSize 2
        }

        cache.close()
    }
}
