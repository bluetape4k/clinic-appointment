package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.cache.LettuceCaches
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
import io.lettuce.core.RedisClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class ServiceConfig {

    @Bean
    fun appointmentRepository(): AppointmentRepository = AppointmentRepository()

    @Bean
    fun clinicRepository(): ClinicRepository = ClinicRepository()

    @Bean
    fun doctorRepository(redisClient: RedisClient): DoctorRepository =
        DoctorRepository(
            clinicDoctorsCache = LettuceCaches.nearCache(redisClient) {
                cacheName = "clinic-doctors"
                maxLocalSize = 500
                frontExpireAfterWrite = Duration.ofMinutes(10)
                redisTtl = Duration.ofHours(1)
            }
        )

    @Bean
    fun treatmentTypeRepository(redisClient: RedisClient): TreatmentTypeRepository =
        TreatmentTypeRepository(
            clinicTreatmentTypesCache = LettuceCaches.nearCache(redisClient) {
                cacheName = "clinic-treatment-types"
                maxLocalSize = 500
                frontExpireAfterWrite = Duration.ofMinutes(10)
                redisTtl = Duration.ofHours(1)
            }
        )

    @Bean
    fun holidayRepository(): HolidayRepository = HolidayRepository()

    @Bean
    fun rescheduleCandidateRepository(): RescheduleCandidateRepository = RescheduleCandidateRepository()

    @Bean
    fun appointmentStateHistoryRepository(): AppointmentStateHistoryRepository = AppointmentStateHistoryRepository()

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
    fun equipmentRepository(redisClient: RedisClient): EquipmentRepository =
        EquipmentRepository(
            clinicEquipmentsCache = LettuceCaches.nearCache(redisClient) {
                cacheName = "clinic-equipments"
                maxLocalSize = 500
                frontExpireAfterWrite = Duration.ofMinutes(10)
                redisTtl = Duration.ofHours(1)
            }
        )

    @Bean
    fun equipmentUnavailabilityRepository(): EquipmentUnavailabilityRepository = EquipmentUnavailabilityRepository()

    @Bean
    fun equipmentUnavailabilityService(
        equipmentUnavailabilityRepository: EquipmentUnavailabilityRepository,
        appointmentRepository: AppointmentRepository,
    ): EquipmentUnavailabilityService = EquipmentUnavailabilityService(
        repo = equipmentUnavailabilityRepository,
        appointmentRepository = appointmentRepository,
    )
}
