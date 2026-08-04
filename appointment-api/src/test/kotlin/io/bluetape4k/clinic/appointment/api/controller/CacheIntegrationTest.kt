package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import org.springframework.aop.support.AopUtils
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager

class CacheIntegrationTest @Autowired constructor(
    private val doctorRepository: DoctorRepository,
    private val equipmentRepository: EquipmentRepository,
    private val treatmentTypeRepository: TreatmentTypeRepository,
    private val cacheManager: CacheManager,
) : AbstractApiIntegrationTest() {

    companion object : KLogging()

    private var clinicId: Long = 0

    private fun scope(): TenantClinicScope =
        TenantClinicScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, clinicId)

    @BeforeEach
    fun setup() {
        cacheManager.getCache("clinic-doctors")?.clear()
        cacheManager.getCache("clinic-equipments")?.clear()
        cacheManager.getCache("clinic-treatment-types")?.clear()

        transaction {
            SchemaUtils.create(TenantGroups, Clinics, Doctors, Equipments, TreatmentTypes)
            TreatmentTypes.deleteAll()
            Equipments.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            TenantGroups.insertAndGetId {
                it[id] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                it[tenantCode] = TenantGroups.DEFAULT_TENANT_CODE
                it[displayName] = TenantGroups.DEFAULT_TENANT_NAME
                it[active] = true
            }

            clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                it[name] = "Test Clinic"
                it[slotDurationMinutes] = 30
                it[timezone] = "Asia/Seoul"
                it[locale] = "ko-KR"
                it[maxConcurrentPatients] = 3
                it[openOnHolidays] = false
            }.value
        }
    }

    @AfterEach
    fun teardown() {
        cacheManager.getCache("clinic-doctors")?.clear()
        cacheManager.getCache("clinic-equipments")?.clear()
        cacheManager.getCache("clinic-treatment-types")?.clear()
    }

    @Test
    fun `DoctorRepository @Cacheable — 동일 clinicId 두 번째 호출 시 캐시에서 반환`() {
        val id = clinicId  // Exposed DSL 내 컬럼 이름 충돌 방지
        transaction {
            Doctors.insertAndGetId {
                it[Doctors.clinicId] = id
                it[name] = "Dr. Test"
                it[specialty] = "General"
                it[providerType] = "DOCTOR"
                it[maxConcurrentPatients] = 1
            }
        }

        // 1회 호출 → DB 조회 후 캐시 적재
        val first = transaction { doctorRepository.findByScope(scope()) }
        first.shouldNotBeEmpty()

        // 캐시에 저장됐는지 확인
        val cached = cacheManager.getCache("clinic-doctors")?.get(scope().cacheKey())?.get()
        cached.shouldNotBeNull()

        // 2회 호출 → 캐시에서 반환 (결과 동일)
        val second = transaction { doctorRepository.findByScope(scope()) }
        second.shouldNotBeEmpty()
        second shouldBeEqualTo first
    }

    @Test
    fun `EquipmentRepository @Cacheable — 동일 clinicId 두 번째 호출 시 캐시에서 반환`() {
        val id = clinicId
        transaction {
            Equipments.insertAndGetId {
                it[Equipments.clinicId] = id
                it[name] = "MRI Machine"
                it[usageDurationMinutes] = 30
                it[quantity] = 1
            }
        }

        val first = transaction { equipmentRepository.findByScope(scope()) }
        first.shouldNotBeEmpty()

        val cached = cacheManager.getCache("clinic-equipments")?.get(scope().cacheKey())?.get()
        cached.shouldNotBeNull()

        val second = transaction { equipmentRepository.findByScope(scope()) }
        second.shouldNotBeEmpty()
        second shouldBeEqualTo first
    }

    @Test
    fun `TreatmentTypeRepository @Cacheable — 동일 clinicId 두 번째 호출 시 캐시에서 반환`() {
        val id = clinicId
        transaction {
            TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = id
                it[name] = "General Consultation"
                it[defaultDurationMinutes] = 30
            }
        }

        val first = transaction { treatmentTypeRepository.findByScope(scope()) }
        first.shouldNotBeEmpty()

        val cached = cacheManager.getCache("clinic-treatment-types")?.get(scope().cacheKey())?.get()
        cached.shouldNotBeNull()

        val second = transaction { treatmentTypeRepository.findByScope(scope()) }
        second.shouldNotBeEmpty()
        second shouldBeEqualTo first
    }

    @Test
    fun `빈 결과는 캐시에 저장되지 않는다`() {
        val result = transaction {
            doctorRepository.findByScope(TenantClinicScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, 999_999L))
        }
        result.shouldBeEmpty()

        val cached = cacheManager.getCache("clinic-doctors")?.get("${TenantGroups.DEFAULT_TENANT_GROUP_ID}:999999")
        cached.shouldBeNull()
    }

    @Test
    fun `@Cacheable CGLIB 프록시 적용 확인 — DoctorRepository가 프록시로 감싸진다`() {
        AopUtils.isCglibProxy(doctorRepository).shouldBeTrue()
    }
}
