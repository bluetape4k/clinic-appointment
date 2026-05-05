package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.cache.LettuceCaches
import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentUnavailabilityRepository
import io.bluetape4k.clinic.appointment.repository.HolidayRepository
import io.bluetape4k.clinic.appointment.repository.RescheduleCandidateRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.clinic.appointment.service.ClosureRescheduleService
import io.bluetape4k.clinic.appointment.service.EquipmentUnavailabilityService
import io.bluetape4k.clinic.appointment.service.SlotCalculationService
import io.bluetape4k.clinic.appointment.statemachine.AppointmentStateMachine
import io.bluetape4k.clinic.appointment.timezone.ClinicTimezoneService
import io.bluetape4k.logging.KLogging
import io.lettuce.core.RedisClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class ServiceConfig {

    companion object : KLogging() {
        private const val MASTER_CACHE_LOCAL_SIZE = 500
        private val MASTER_CACHE_LOCAL_TTL: Duration = Duration.ofMinutes(10)
        private val MASTER_CACHE_REDIS_TTL: Duration = Duration.ofHours(1)
    }

    // --- 마스터 데이터 NearCache 빈 (destroyMethod 지정으로 리소스 해제 보장) ---

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

    // --- Repository 빈 ---

    @Bean
    fun appointmentRepository(): AppointmentRepository = AppointmentRepository()

    @Bean
    fun clinicRepository(): ClinicRepository = ClinicRepository()

    @Bean
    fun doctorRepository(
        clinicDoctorsCache: NearCacheOperations<List<DoctorRecord>>,
    ): DoctorRepository = DoctorRepository(clinicDoctorsCache)

    @Bean
    fun treatmentTypeRepository(
        clinicTreatmentTypesCache: NearCacheOperations<List<TreatmentTypeRecord>>,
    ): TreatmentTypeRepository = TreatmentTypeRepository(clinicTreatmentTypesCache)

    @Bean
    fun equipmentRepository(
        clinicEquipmentsCache: NearCacheOperations<List<EquipmentRecord>>,
    ): EquipmentRepository = EquipmentRepository(clinicEquipmentsCache)

    @Bean
    fun holidayRepository(): HolidayRepository = HolidayRepository()

    @Bean
    fun rescheduleCandidateRepository(): RescheduleCandidateRepository = RescheduleCandidateRepository()

    @Bean
    fun appointmentStateHistoryRepository(): AppointmentStateHistoryRepository = AppointmentStateHistoryRepository()

    @Bean
    fun equipmentUnavailabilityRepository(): EquipmentUnavailabilityRepository = EquipmentUnavailabilityRepository()

    // --- Service 빈 ---

    @Bean
    fun slotCalculationService(
        clinicRepository: ClinicRepository,
        doctorRepository: DoctorRepository,
        treatmentTypeRepository: TreatmentTypeRepository,
        appointmentRepository: AppointmentRepository,
        holidayRepository: HolidayRepository,
    ): SlotCalculationService = SlotCalculationService(
        clinicRepository,
        doctorRepository,
        treatmentTypeRepository,
        appointmentRepository,
        holidayRepository,
    )

    @Bean
    fun closureRescheduleService(
        slotCalculationService: SlotCalculationService,
        appointmentRepository: AppointmentRepository,
        rescheduleCandidateRepository: RescheduleCandidateRepository,
        appointmentStateHistoryRepository: AppointmentStateHistoryRepository,
    ): ClosureRescheduleService = ClosureRescheduleService(
        slotCalculationService,
        appointmentRepository,
        rescheduleCandidateRepository,
        appointmentStateHistoryRepository,
    )

    @Bean
    fun appointmentStateMachine(): AppointmentStateMachine = AppointmentStateMachine()

    @Bean
    fun clinicTimezoneService(clinicRepository: ClinicRepository): ClinicTimezoneService =
        ClinicTimezoneService(clinicRepository)

    @Bean
    fun equipmentUnavailabilityService(
        equipmentUnavailabilityRepository: EquipmentUnavailabilityRepository,
        appointmentRepository: AppointmentRepository,
    ): EquipmentUnavailabilityService = EquipmentUnavailabilityService(
        repo = equipmentUnavailabilityRepository,
        appointmentRepository = appointmentRepository,
    )
}
