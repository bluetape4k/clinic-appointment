package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
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

    @BeforeEach
    fun setup() {
        cacheManager.getCache("clinic-doctors")?.clear()
        cacheManager.getCache("clinic-equipments")?.clear()
        cacheManager.getCache("clinic-treatment-types")?.clear()

        transaction {
            SchemaUtils.create(Clinics, Doctors, Equipments, TreatmentTypes)
            TreatmentTypes.deleteAll()
            Equipments.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()

            clinicId = Clinics.insertAndGetId {
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

        val result = transaction { doctorRepository.findByClinicId(id) }
        result.shouldNotBeEmpty()

        val cached = cacheManager.getCache("clinic-doctors")?.get(id.toString())?.get()
        cached.shouldNotBeNull()
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

        val result = transaction { equipmentRepository.findByClinicId(id) }
        result.shouldNotBeEmpty()

        val cached = cacheManager.getCache("clinic-equipments")?.get(id.toString())?.get()
        cached.shouldNotBeNull()
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

        val result = transaction { treatmentTypeRepository.findByClinicId(id) }
        result.shouldNotBeEmpty()

        val cached = cacheManager.getCache("clinic-treatment-types")?.get(id.toString())?.get()
        cached.shouldNotBeNull()
    }

    @Test
    fun `빈 결과는 캐시에 저장되지 않는다`() {
        val result = transaction { doctorRepository.findByClinicId(-999L) }
        result.shouldBeEmpty()

        val cached = cacheManager.getCache("clinic-doctors")?.get("-999")
        cached.shouldBeNull()
    }

    @Test
    fun `@Cacheable CGLIB 프록시 적용 확인 — DoctorRepository가 프록시로 감싸진다`() {
        val isCglibProxy = doctorRepository.javaClass.name.contains("CGLIB") ||
            doctorRepository.javaClass.name.contains("EnhancerBySpring") ||
            doctorRepository.javaClass != DoctorRepository::class.java
        isCglibProxy.shouldBeTrue()
    }
}
