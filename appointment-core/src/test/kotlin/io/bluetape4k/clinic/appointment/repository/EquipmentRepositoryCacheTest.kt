package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.cache.LettuceCaches
import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

class EquipmentRepositoryCacheTest : AbstractExposedTest() {

    companion object : KLogging() {

        private val redis: RedisServer by lazy { RedisServer.Launcher.redis }
        private lateinit var redisClient: RedisClient

        private val allTables = arrayOf(Clinics, Equipments)

        @JvmStatic
        @BeforeAll
        fun setupRedis() {
            redisClient = RedisClient.create(redis.url)
        }

        @JvmStatic
        @AfterAll
        fun teardownRedis() {
            if (::redisClient.isInitialized) {
                redisClient.shutdown()
            }
        }

        private fun createCache(name: String): NearCacheOperations<List<EquipmentRecord>> =
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
        val cache = createCache("test-equipments-01-${testDB.name}")
        val repo = EquipmentRepository(clinicEquipmentsCache = cache)

        withTables(testDB, *allTables) {
            val clinicId = Clinics.insertAndGetId {
                it[name] = "테스트 병원"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 3
            }.value

            Equipments.insertAndGetId {
                it[Equipments.clinicId] = clinicId
                it[name] = "MRI 장비"
                it[usageDurationMinutes] = 30
                it[quantity] = 2
            }

            val result1 = repo.findByClinicId(clinicId)
            result1 shouldHaveSize 1
            result1[0].name shouldBeEqualTo "MRI 장비"

            // DB 조회 후 캐시에 실제로 저장됐는지 검증
            cache.get(clinicId.toString()).shouldNotBeNull() shouldHaveSize 1

            val result2 = repo.findByClinicId(clinicId)
            result2 shouldHaveSize 1
            result2[0].name shouldBeEqualTo result1[0].name
        }

        cache.close()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `findByClinicId - 캐시 없이도 정상 동작`(testDB: TestDB) {
        val repo = EquipmentRepository(clinicEquipmentsCache = null)

        withTables(testDB, *allTables) {
            val clinicId = Clinics.insertAndGetId {
                it[name] = "캐시없는 병원"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }.value

            val empty = repo.findByClinicId(clinicId)
            empty shouldHaveSize 0

            Equipments.insertAndGetId {
                it[Equipments.clinicId] = clinicId
                it[name] = "초음파 장비"
                it[usageDurationMinutes] = 15
            }

            val result = repo.findByClinicId(clinicId)
            result shouldHaveSize 1
            result[0].name shouldBeEqualTo "초음파 장비"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `findByClinicId - 빈 결과는 캐시에 저장하지 않음`(testDB: TestDB) {
        val cache = createCache("test-equipments-03-${testDB.name}")
        val repo = EquipmentRepository(clinicEquipmentsCache = cache)

        withTables(testDB, *allTables) {
            val clinicId = Clinics.insertAndGetId {
                it[name] = "장비 없는 병원"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }.value

            // 장비 없이 조회 — emptyList는 캐시 저장 안 됨
            val empty = repo.findByClinicId(clinicId)
            empty shouldHaveSize 0
            cache.get(clinicId.toString()) shouldBeEqualTo null

            // 이후 장비 추가 시 캐시 스탈 없이 즉시 반영됨
            Equipments.insertAndGetId {
                it[Equipments.clinicId] = clinicId
                it[name] = "신규 장비"
                it[usageDurationMinutes] = 20
            }
            val result = repo.findByClinicId(clinicId)
            result shouldHaveSize 1
        }

        cache.close()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `findByClinicId - 다른 clinicId는 별도 캐시 키로 독립 관리`(testDB: TestDB) {
        val cache = createCache("test-equipments-02-${testDB.name}")
        val repo = EquipmentRepository(clinicEquipmentsCache = cache)

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

            Equipments.insertAndGetId {
                it[Equipments.clinicId] = clinicId1
                it[name] = "장비 A1"
                it[usageDurationMinutes] = 20
            }
            Equipments.insertAndGetId {
                it[Equipments.clinicId] = clinicId2
                it[name] = "장비 B1"
                it[usageDurationMinutes] = 30
            }
            Equipments.insertAndGetId {
                it[Equipments.clinicId] = clinicId2
                it[name] = "장비 B2"
                it[usageDurationMinutes] = 45
            }

            repo.findByClinicId(clinicId1) shouldHaveSize 1
            repo.findByClinicId(clinicId2) shouldHaveSize 2
        }

        cache.close()
    }
}
