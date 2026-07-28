package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.policy.PolicyActivationPublisher
import io.bluetape4k.clinic.appointment.api.policy.EffectiveSchedulingPolicyService
import io.bluetape4k.clinic.appointment.api.policy.ExposedEffectivePolicyStore
import io.bluetape4k.clinic.appointment.api.policy.ExposedSchedulingPolicyPreviewStore
import io.bluetape4k.clinic.appointment.api.policy.ExposedSchedulingPolicyWorkerStore
import io.bluetape4k.clinic.appointment.api.policy.PersistedPolicyPreviewEvidenceVerifier
import io.bluetape4k.clinic.appointment.api.policy.PolicyPreviewEvidenceVerifier
import io.bluetape4k.clinic.appointment.api.policy.PolicyTenantBoundaryVerifier
import io.bluetape4k.clinic.appointment.api.policy.ScheduledPolicyActivationExecutionOutcome
import io.bluetape4k.clinic.appointment.api.policy.ScheduledPolicyActivationExecutor
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyCommandService
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyMetrics
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyPreviewService
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyWorker
import io.bluetape4k.clinic.appointment.api.service.DashboardStatsService
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.tenant.TenantContext
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.event.policy.SchedulingPolicyEventRepository
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
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyImpactRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.clinic.appointment.service.ClosureRescheduleService
import io.bluetape4k.clinic.appointment.service.AppointmentPlanQueryService
import io.bluetape4k.clinic.appointment.service.CatalogSyncApplicationService
import io.bluetape4k.clinic.appointment.service.EquipmentUnavailabilityService
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCache
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCacheLimits
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyPayloadCodec
import io.bluetape4k.clinic.appointment.service.SlotCalculationService
import io.bluetape4k.clinic.appointment.statemachine.AppointmentStateMachine
import io.bluetape4k.clinic.appointment.timezone.ClinicTimezoneService
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.micrometer.core.instrument.MeterRegistry
import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.Environment
import java.util.Base64
import java.time.Instant

/**
 * 예약 API의 repository와 application service를 명시적으로 조립하는 Spring 설정이다.
 *
 * 예제 애플리케이션에서 각 업무 서비스가 사용하는 저장소, transaction 경계, 캐시 소유권을
 * 한곳에서 확인할 수 있도록 bean 생성 과정을 숨기지 않는다. 유효 정책 캐시는 프로세스
 * 로컬 성능 최적화일 뿐 권위 저장소가 아니며, [EffectiveSchedulingPolicyService]는 매
 * 조회마다 데이터베이스 세대를 먼저 확인하도록 [ExposedEffectivePolicyStore]와 함께
 * 조립한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlanFoundationProperties::class, SchedulingPolicyProperties::class)
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
    fun schedulingPolicyRepository(): SchedulingPolicyRepository = SchedulingPolicyRepository()

    /** 미래 예약·미배정 시술 의무를 payload 없이 bounded keyset으로 읽는 저장소를 생성한다. */
    @Bean
    fun schedulingPolicyImpactRepository(): SchedulingPolicyImpactRepository =
        SchedulingPolicyImpactRepository()

    /**
     * 안전 상한 기본값을 적용한 프로세스 로컬 유효 정책 캐시를 생성한다.
     *
     * 이 캐시는 성능 최적화일 뿐 권위 저장소가 아니다. 서비스는 이 빈을 조회하기 전에 항상
     * 데이터베이스의 세대 벡터를 확인하므로, 무효화 이벤트가 유실되거나 이전 세대 항목이
     * 메모리에 남아도 오래된 정책으로 예약 결정을 승인할 수 없다.
     */
    @Bean
    fun effectivePolicyCache(): EffectivePolicyCache =
        EffectivePolicyCache(EffectivePolicyCacheLimits())

    /**
     * Exposed 트랜잭션 경계와 엄격한 폐쇄형 정책 payload 디코더를 구성한다.
     *
     * 저장소 메서드는 호출자 트랜잭션을 전제로 하며 [ExposedEffectivePolicyStore]가 각
     * 권위 조회와 원자적 세대 재검사의 트랜잭션을 소유한다.
     */
    @Bean
    fun effectivePolicyStore(
        schedulingPolicyRepository: SchedulingPolicyRepository,
    ): ExposedEffectivePolicyStore =
        ExposedEffectivePolicyStore(
            repository = schedulingPolicyRepository,
            payloadCodec = SchedulingPolicyPayloadCodec(),
        )

    /**
     * 권위 세대 이중 조회, 정책 컴파일, 불변 스냅샷 재사용 흐름을 구성한다.
     *
     * 기본 구성은 세대 충돌을 최대 세 번 재시도하고, 영속화 커밋 이후에만 프로세스 로컬
     * 캐시를 채운다. 데이터베이스 조회가 실패하면 캐시로 우회하지 않는다.
     */
    @Bean
    fun effectiveSchedulingPolicyService(
        effectivePolicyStore: ExposedEffectivePolicyStore,
        effectivePolicyCache: EffectivePolicyCache,
    ): EffectiveSchedulingPolicyService =
        EffectiveSchedulingPolicyService(effectivePolicyStore, effectivePolicyCache)

    @Bean
    fun schedulingPolicyEventRepository(): SchedulingPolicyEventRepository = SchedulingPolicyEventRepository()

    /**
     * 운영자가 전용 Base64 비밀값을 제공한 경우에만 keyed-idempotency 저장소를 생성한다.
     *
     * 비밀값은 JWT 서명 키에서 파생하지 않으며 소스 코드 기본값도 두지 않는다. 저장소는
     * 디코딩된 바이트를 복사하고 외부에 노출하지 않는다. 정책 명령을 활성화한 환경은
     * `scheduling.policy.idempotency-hash-secret`에 디코딩 후 최소 16바이트가 되는 값을
     * 제공해야 한다.
     */
    @Bean
    @ConditionalOnProperty("scheduling.policy.idempotency-hash-secret")
    fun schedulingPolicyJobRepository(environment: Environment): SchedulingPolicyJobRepository {
        val encoded = environment.getRequiredProperty("scheduling.policy.idempotency-hash-secret")
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (ex: IllegalArgumentException) {
            throw IllegalStateException(
                "scheduling.policy.idempotency-hash-secret must be valid Base64",
                ex,
            )
        }
        return SchedulingPolicyJobRepository(decoded)
    }

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

    /**
     * 명시적인 비밀값 구성이 갖춰진 경우에만 예약 정책 명령 서비스를 구성한다.
     *
     * 영속 영향도 미리보기 구현을 연결하기 전에는 누락된 [PolicyPreviewEvidenceVerifier]를
     * 닫힌 실패 검증기로 대체한다. 초안 생성·수정·승인은 계속 사용할 수 있지만, 예약 또는
     * 활성화 명령은 완전한 미리보기 증거 없이는 진행할 수 없다.
     */
    @Bean
    @ConditionalOnProperty("scheduling.policy.idempotency-hash-secret")
    fun schedulingPolicyCommandService(
        schedulingPolicyRepository: SchedulingPolicyRepository,
        schedulingPolicyJobRepository: SchedulingPolicyJobRepository,
        schedulingPolicyEventRepository: SchedulingPolicyEventRepository,
        tenantClinicAccessChecker: TenantClinicAccessChecker,
        previewVerifierProvider: ObjectProvider<PolicyPreviewEvidenceVerifier>,
    ): SchedulingPolicyCommandService {
        val previewVerifier = previewVerifierProvider.getIfAvailable {
            PolicyPreviewEvidenceVerifier { _, _, _ -> false }
        }
        val publisher = PolicyActivationPublisher(schedulingPolicyEventRepository::insertPolicyActivated)
        val tenantBoundaryVerifier = PolicyTenantBoundaryVerifier { scope, actor ->
            val tenant = TenantContext.current() ?: return@PolicyTenantBoundaryVerifier false
            if (tenant.id != scope.tenantGroupId || tenant.tenantCode !in actor.allowedTenantCodes) {
                return@PolicyTenantBoundaryVerifier false
            }
            if (scope.scope == io.bluetape4k.clinic.appointment.model.policy.PolicyScope.CLINIC_OVERRIDE) {
                try {
                    tenantClinicAccessChecker.verifyClinic(
                        tenant.tenantCode,
                        requireNotNull(scope.clinicId),
                    ).id == tenant.id
                } catch (_: NoSuchElementException) {
                    false
                }
            } else {
                true
            }
        }
        return SchedulingPolicyCommandService(
            schedulingPolicyRepository,
            schedulingPolicyJobRepository,
            tenantBoundaryVerifier,
            previewVerifier,
            publisher,
        )
    }

    /**
     * 정책 worker와 관리 API가 공유하는 low-cardinality metric facade를 생성한다.
     *
     * facade의 공개 입력은 닫힌 enum, policy kind, scope type뿐이므로 tenant/clinic/actor/token
     * 값이 meter tag로 유입되지 않는다.
     */
    @Bean
    fun schedulingPolicyMetrics(meterRegistry: MeterRegistry): SchedulingPolicyMetrics =
        SchedulingPolicyMetrics(meterRegistry)

    /** preview primitive마다 짧은 transaction을 소유하는 Exposed adapter를 생성한다. */
    @Bean
    @ConditionalOnProperty("scheduling.policy.idempotency-hash-secret")
    fun schedulingPolicyPreviewStore(
        jobRepository: SchedulingPolicyJobRepository,
        impactRepository: SchedulingPolicyImpactRepository,
        policyRepository: SchedulingPolicyRepository,
    ): ExposedSchedulingPolicyPreviewStore =
        ExposedSchedulingPolicyPreviewStore(jobRepository, impactRepository, policyRepository)

    /** 동기 fast path와 one-page async path를 같은 durable evidence 계약으로 조립한다. */
    @Bean
    @ConditionalOnProperty("scheduling.policy.idempotency-hash-secret")
    fun schedulingPolicyPreviewService(
        store: ExposedSchedulingPolicyPreviewStore,
        properties: SchedulingPolicyProperties,
    ): SchedulingPolicyPreviewService =
        SchedulingPolicyPreviewService(store, properties)

    /**
     * activation token을 completed preview row와 exact-match하는 로컬 검증기를 생성한다.
     *
     * command transaction 중 원격 호출을 수행하지 않으며 stale/partial/cancelled row는 조회
     * 단계에서부터 증거가 될 수 없다.
     */
    @Bean
    @ConditionalOnProperty("scheduling.policy.idempotency-hash-secret")
    fun policyPreviewEvidenceVerifier(
        jobRepository: SchedulingPolicyJobRepository,
        policyRepository: SchedulingPolicyRepository,
    ): PolicyPreviewEvidenceVerifier =
        PersistedPolicyPreviewEvidenceVerifier(jobRepository, policyRepository)

    /** DB-time due selection과 owner-fenced retry/missed primitive를 worker에 제공한다. */
    @Bean
    @ConditionalOnProperty("scheduling.policy.idempotency-hash-secret")
    fun schedulingPolicyWorkerStore(
        jobRepository: SchedulingPolicyJobRepository,
        policyRepository: SchedulingPolicyRepository,
    ): ExposedSchedulingPolicyWorkerStore =
        ExposedSchedulingPolicyWorkerStore(jobRepository, policyRepository)

    /**
     * 이미 claim된 command를 durable scope와 preview snapshot으로 실행하는 adapter다.
     *
     * Gateway request [TenantContext]를 만들지 않고 command service의 SYSTEM 전용 경계를
     * 사용한다.
     */
    @Bean
    @ConditionalOnProperty("scheduling.policy.idempotency-hash-secret")
    fun scheduledPolicyActivationExecutor(
        commandService: SchedulingPolicyCommandService,
    ): ScheduledPolicyActivationExecutor =
        ScheduledPolicyActivationExecutor { commandId, owner, actor, databaseNow ->
            val result = commandService.executeClaimedScheduled(commandId, owner, actor, databaseNow)
            ScheduledPolicyActivationExecutionOutcome(result.idempotentReplay)
        }

    /**
     * application 내부 scheduled worker identity와 bounded worker 본체를 생성한다.
     *
     * 이 identity는 HTTP request/JWT에서 오지 않으며 human 권한을 갖지 않는다. tenant와
     * clinic 범위는 실행 시 durable command row에서만 복원된다.
     */
    @Bean
    @ConditionalOnProperty("scheduling.policy.idempotency-hash-secret")
    fun schedulingPolicyWorker(
        store: ExposedSchedulingPolicyWorkerStore,
        activationExecutor: ScheduledPolicyActivationExecutor,
        previewService: SchedulingPolicyPreviewService,
        properties: SchedulingPolicyProperties,
        metrics: SchedulingPolicyMetrics,
    ): SchedulingPolicyWorker =
        SchedulingPolicyWorker(
            store = store,
            activationExecutor = activationExecutor,
            previewProcessor = previewService,
            properties = properties,
            metrics = metrics,
            systemActor = ActorContext(
                actorId = "scheduling-policy-worker",
                actorType = ActorType.SYSTEM,
                roles = setOf(ActorRole.SYSTEM),
                scopes = setOf("policy:scheduled-activation"),
                allowedTenantCodes = emptySet(),
                allowedClinicIds = emptySet(),
                patientSubjectId = null,
                assurance = AuthenticationAssurance.SERVICE,
                issuer = "clinic-appointment",
                tokenId = "internal-scheduling-policy-worker",
                authenticatedAt = Instant.now(),
                correlationId = "scheduled-policy-worker",
            ),
        )

    @Bean
    fun planFoundationPropertiesValidator(
        properties: PlanFoundationProperties,
        environment: Environment,
        outboxTransportCapability: ObjectProvider<OutboxTransportCapability>,
    ): PlanFoundationPropertiesValidator =
        PlanFoundationPropertiesValidator(properties, environment, outboxTransportCapability)

    @Bean
    fun planFoundationFeatureControlResolver(
        properties: PlanFoundationProperties,
    ): PlanFoundationFeatureControlResolver = PlanFoundationFeatureControlResolver(properties)

    // --- Stats 빈 ---

    @Bean
    fun appointmentStatsRepository(): AppointmentStatsRepository = AppointmentStatsRepository()

    @Bean
    fun dashboardStatsService(
        appointmentStatsRepository: AppointmentStatsRepository,
    ): DashboardStatsService = DashboardStatsService(appointmentStatsRepository)
}
