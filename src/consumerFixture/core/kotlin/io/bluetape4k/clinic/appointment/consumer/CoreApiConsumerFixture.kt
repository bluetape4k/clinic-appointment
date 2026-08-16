package io.bluetape4k.clinic.appointment.consumer

import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.HolidayRepository
import io.bluetape4k.clinic.appointment.repository.PatientAccountRepository
import io.bluetape4k.clinic.appointment.repository.PatientLoginIdentityRepository
import io.bluetape4k.clinic.appointment.repository.RescheduleCandidateRepository
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import kotlin.reflect.KClass

/**
 * `appointment-core`의 공개 JDBC repository가 소비자 `apiElements`에서 해석되는지 고정합니다.
 *
 * 각 anchor는 대응 production source의 실제 선언을 가리킵니다.
 */
private val repositoryTypes: List<KClass<out LongJdbcRepository<*>>> = listOf(
    // appointment-core/.../repository/AppointmentRepository.kt
    AppointmentRepository::class,
    // appointment-core/.../repository/ClinicRepository.kt
    ClinicRepository::class,
    // appointment-core/.../repository/DoctorRepository.kt
    DoctorRepository::class,
    // appointment-core/.../repository/EquipmentRepository.kt
    EquipmentRepository::class,
    // appointment-core/.../repository/HolidayRepository.kt
    HolidayRepository::class,
    // appointment-core/.../repository/PatientAccountRepository.kt
    PatientAccountRepository::class,
    // appointment-core/.../repository/PatientLoginIdentityRepository.kt
    PatientLoginIdentityRepository::class,
    // appointment-core/.../repository/RescheduleCandidateRepository.kt
    RescheduleCandidateRepository::class,
    // appointment-core/.../repository/TenantGroupRepository.kt
    TenantGroupRepository::class,
    // appointment-core/.../repository/TreatmentTypeRepository.kt
    TreatmentTypeRepository::class,
)

@Suppress("unused")
fun verifyCoreApiConsumerSurface(): List<KClass<out LongJdbcRepository<*>>> = repositoryTypes
