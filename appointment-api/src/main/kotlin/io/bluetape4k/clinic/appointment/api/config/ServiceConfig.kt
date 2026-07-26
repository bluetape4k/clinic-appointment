package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.service.DashboardStatsService
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.repository.AppointmentIdempotencyRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStatsRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentUnavailabilityRepository
import io.bluetape4k.clinic.appointment.repository.HolidayRepository
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.clinic.appointment.repository.RescheduleCandidateRepository
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.clinic.appointment.service.ClosureRescheduleService
import io.bluetape4k.clinic.appointment.service.AppointmentPlanQueryService
import io.bluetape4k.clinic.appointment.service.CatalogSyncApplicationService
import io.bluetape4k.clinic.appointment.service.EquipmentUnavailabilityService
import io.bluetape4k.clinic.appointment.service.SlotCalculationService
import io.bluetape4k.clinic.appointment.statemachine.AppointmentStateMachine
import io.bluetape4k.clinic.appointment.timezone.ClinicTimezoneService
import io.bluetape4k.logging.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlanFoundationProperties::class)
class ServiceConfig {

    companion object : KLogging()

    // --- Repository 빈 ---

    @Bean
    fun appointmentRepository(): AppointmentRepository = AppointmentRepository()

    @Bean
    fun appointmentIdempotencyRepository(): AppointmentIdempotencyRepository = AppointmentIdempotencyRepository()

    @Bean
    fun clinicRepository(): ClinicRepository = ClinicRepository()

    @Bean
    fun doctorRepository(): DoctorRepository = DoctorRepository()

    @Bean
    fun treatmentTypeRepository(): TreatmentTypeRepository = TreatmentTypeRepository()

    @Bean
    fun equipmentRepository(): EquipmentRepository = EquipmentRepository()

    @Bean
    fun holidayRepository(): HolidayRepository = HolidayRepository()

    @Bean
    fun rescheduleCandidateRepository(): RescheduleCandidateRepository = RescheduleCandidateRepository()

    @Bean
    fun appointmentStateHistoryRepository(): AppointmentStateHistoryRepository = AppointmentStateHistoryRepository()

    @Bean
    fun equipmentUnavailabilityRepository(): EquipmentUnavailabilityRepository = EquipmentUnavailabilityRepository()

    @Bean
    fun tenantGroupRepository(): TenantGroupRepository = TenantGroupRepository()

    @Bean
    fun productCatalogRepository(): ProductCatalogRepository = ProductCatalogRepository()

    @Bean
    fun appointmentPlanRepository(): AppointmentPlanRepository = AppointmentPlanRepository()

    @Bean
    fun tenantClinicAccessChecker(
        tenantGroupRepository: TenantGroupRepository,
        clinicRepository: ClinicRepository,
        doctorRepository: DoctorRepository,
        treatmentTypeRepository: TreatmentTypeRepository,
        equipmentRepository: EquipmentRepository,
    ): TenantClinicAccessChecker =
        TenantClinicAccessChecker(
            tenantGroupRepository,
            clinicRepository,
            doctorRepository,
            treatmentTypeRepository,
            equipmentRepository,
        )

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
        doctorRepository: DoctorRepository,
    ): ClosureRescheduleService = ClosureRescheduleService(
        slotCalculationService,
        appointmentRepository,
        rescheduleCandidateRepository,
        appointmentStateHistoryRepository,
        doctorRepository,
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

    @Bean
    fun catalogSyncApplicationService(
        productCatalogRepository: ProductCatalogRepository,
    ): CatalogSyncApplicationService = CatalogSyncApplicationService(productCatalogRepository)

    @Bean
    fun appointmentPlanQueryService(
        appointmentPlanRepository: AppointmentPlanRepository,
    ): AppointmentPlanQueryService = AppointmentPlanQueryService(appointmentPlanRepository)

    @Bean
    fun planFoundationPropertiesValidator(
        properties: PlanFoundationProperties,
        environment: Environment,
        outboxTransportCapability: ObjectProvider<OutboxTransportCapability>,
    ): PlanFoundationPropertiesValidator =
        PlanFoundationPropertiesValidator(properties, environment, outboxTransportCapability)

    // --- Stats 빈 ---

    @Bean
    fun appointmentStatsRepository(): AppointmentStatsRepository = AppointmentStatsRepository()

    @Bean
    fun dashboardStatsService(
        appointmentStatsRepository: AppointmentStatsRepository,
    ): DashboardStatsService = DashboardStatsService(appointmentStatsRepository)
}
