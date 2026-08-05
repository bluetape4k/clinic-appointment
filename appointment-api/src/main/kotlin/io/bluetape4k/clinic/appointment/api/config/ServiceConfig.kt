package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentMetrics
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentProposalService
import io.bluetape4k.clinic.appointment.api.notification.AppointmentMemberDirectory
import io.bluetape4k.clinic.appointment.api.notification.AppointmentMemberResolver
import io.bluetape4k.clinic.appointment.api.notification.AppointmentNotificationWriter
import io.bluetape4k.clinic.appointment.api.notification.DefaultAppointmentNotificationWriter
import io.bluetape4k.clinic.appointment.api.notification.DefaultAppointmentMemberResolver
import io.bluetape4k.clinic.appointment.api.notification.FailClosedAppointmentMemberDirectory
import io.bluetape4k.clinic.appointment.api.notification.JdbcAppointmentReminderRecoveryStore
import io.bluetape4k.clinic.appointment.api.notification.NotificationMemberIdProperties
import io.bluetape4k.clinic.appointment.api.notification.UnavailableAppointmentNotificationWriter
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
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyAdministrationService
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyCommandService
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyMetrics
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyPreviewService
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyWorker
import io.bluetape4k.clinic.appointment.api.policy.TenantEffectiveSchedulingPolicyService
import io.bluetape4k.clinic.appointment.api.service.DashboardStatsService
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentPlanningResolver
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentPolicySnapshotResolver
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentConsentEvidenceVerifier
import io.bluetape4k.clinic.appointment.api.service.DefaultAppointmentCommitmentApplicationService
import io.bluetape4k.clinic.appointment.api.service.EffectiveAppointmentCommitmentPolicySnapshotResolver
import io.bluetape4k.clinic.appointment.api.service.FailClosedAppointmentCommitmentConsentEvidenceVerifier
import io.bluetape4k.clinic.appointment.api.service.FailClosedAppointmentCommitmentPlanningResolver
import io.bluetape4k.clinic.appointment.api.service.FailClosedPatientSubjectFingerprintResolver
import io.bluetape4k.clinic.appointment.api.service.HmacAppointmentCommitmentIdempotencyKeyHasher
import io.bluetape4k.clinic.appointment.api.service.PatientSubjectFingerprintResolver
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiService
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApplicationPort
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityMetrics
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityProperties
import io.bluetape4k.clinic.appointment.api.reliability.DefaultBookingReliabilityApplicationAdapter
import io.bluetape4k.clinic.appointment.api.reliability.DefaultBookingReliabilityApiService
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityReevaluationWorker
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityRetryPolicy
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityHealthIndicator
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityHealthSource
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityOperationalSnapshot
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityOperationalState
import io.bluetape4k.clinic.appointment.api.reliability.DefaultBookingReliabilitySchemaReadiness
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilitySchemaReadiness
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilitySchemaProbe
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistDeliveryProperties
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.tenant.TenantContext
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.event.policy.SchedulingPolicyEventRepository
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxHasher
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxRepository
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingContext
import io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxWriter
import io.bluetape4k.clinic.appointment.notification.NotificationProperties
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentIdempotencyRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStatsRepository
import io.bluetape4k.clinic.appointment.repository.BookingReliabilityRepository
import io.bluetape4k.clinic.appointment.repository.BookingReliabilityReevaluationJobRepository
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
import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.bluetape4k.clinic.appointment.service.AppointmentRescheduleNotificationWriter
import io.bluetape4k.clinic.appointment.service.AppointmentPlanQueryService
import io.bluetape4k.clinic.appointment.service.PackageExecutionLimits
import io.bluetape4k.clinic.appointment.service.CatalogSyncApplicationService
import io.bluetape4k.clinic.appointment.service.EquipmentUnavailabilityService
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCache
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCacheLimits
import io.bluetape4k.clinic.appointment.service.SchedulingPolicyPayloadCodec
import io.bluetape4k.clinic.appointment.service.SlotCalculationService
import io.bluetape4k.clinic.appointment.statemachine.AppointmentStateMachine
import io.bluetape4k.clinic.appointment.timezone.ClinicTimezoneService
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.micrometer.core.instrument.MeterRegistry
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.Environment
import javax.sql.DataSource
import java.util.Base64
import java.time.Instant
import java.time.Clock
import java.time.Duration

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
@EnableConfigurationProperties(
    PlanFoundationProperties::class,
    SchedulingPolicyProperties::class,
    AppointmentCommitmentProperties::class,
    ProfileReevaluationProperties::class,
    NotificationMemberIdProperties::class,
    NotificationProperties::class,
    BookingReliabilityProperties::class,
    WaitlistDeliveryProperties::class,
)
class ServiceConfig {

    companion object : KLogging() {
        private const val REQUIRED_MIGRATION_VERSION = 17
        private val REQUIRED_RELIABILITY_TABLES = setOf(
            "booking_reliability_events",
            "booking_reliability_decisions",
            "booking_reliability_overrides",
            "booking_reliability_reevaluation_jobs",
        )
        private val REQUIRED_RELIABILITY_INDEXES = setOf(
            "ux_booking_reliability_event_identity",
            "ux_booking_reliability_decision_digest",
            "ux_booking_reliability_override_idempotency",
            "ux_booking_reliability_reevaluation_idempotency",
        )
    }

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
    fun notificationOutboxCodec(): NotificationOutboxCodec = NotificationOutboxCodec()

    @Bean
    fun notificationOutboxRepository(
        notificationOutboxCodec: NotificationOutboxCodec,
    ): NotificationOutboxRepository =
        NotificationOutboxRepository(
            codec = notificationOutboxCodec,
            leaseDuration = Duration.ofMinutes(5),
        )

    /**
     * 외부 secret-backed key ring이 준비된 환경에서만 실제 enqueue writer를 구성한다.
     *
     * key가 없으면 예약 command 시점에 닫힌 실패로 거절하며, 임시 키나 무서명
     * idempotency 값으로 우회하지 않는다.
     */
    @Bean
    fun appointmentNotificationWriter(
        notificationOutboxRepository: NotificationOutboxRepository,
        notificationOutboxHasherProvider: ObjectProvider<NotificationOutboxHasher>,
        clinicRepository: ClinicRepository,
    ): AppointmentNotificationWriter {
        val hasher = notificationOutboxHasherProvider.getIfAvailable()
            ?: return UnavailableAppointmentNotificationWriter
        return DefaultAppointmentNotificationWriter(
            repository = notificationOutboxRepository,
            hasher = hasher,
            clinicRepository = clinicRepository,
            clock = Clock.systemUTC(),
            sameDayReminderLeadTime = Duration.ofHours(2),
        )
    }

    @Bean
    @ConditionalOnBean(NotificationOutboxHasher::class)
    @ConditionalOnMissingBean(JdbcAppointmentReminderRecoveryStore::class)
    fun appointmentReminderRecoveryStore(
        database: Database,
        notificationOutboxRepository: NotificationOutboxRepository,
        notificationOutboxHasher: NotificationOutboxHasher,
        notificationProperties: NotificationProperties,
    ): JdbcAppointmentReminderRecoveryStore {
        val reminder = notificationProperties.reminder
        return JdbcAppointmentReminderRecoveryStore(
            database = database,
            repository = notificationOutboxRepository,
            hasher = notificationOutboxHasher,
            sameDayReminderLeadTime = Duration.ofHours(reminder.sameDayHoursBefore.toLong()),
            dayBeforeEnabled = reminder.enabled && reminder.dayBefore,
            sameDayEnabled = reminder.enabled && reminder.sameDay,
        )
    }

    /**
     * 회원 서비스 adapter가 연결되지 않은 환경에서는 신규 예약을 닫힌 실패로 막는다.
     */
    @Bean
    @ConditionalOnMissingBean(AppointmentMemberDirectory::class)
    internal fun appointmentMemberDirectory(): AppointmentMemberDirectory =
        FailClosedAppointmentMemberDirectory

    /**
     * legacy와 v2 예약 진입점이 공유하는 회원 식별 경계를 구성한다.
     */
    @Bean
    @ConditionalOnMissingBean(AppointmentMemberResolver::class)
    internal fun appointmentMemberResolver(
        appointmentMemberDirectory: AppointmentMemberDirectory,
        notificationMemberIdProperties: NotificationMemberIdProperties,
    ): AppointmentMemberResolver =
        DefaultAppointmentMemberResolver(
            directory = appointmentMemberDirectory,
            properties = notificationMemberIdProperties,
            clock = Clock.systemUTC(),
        )

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

    /**
     * clinic sentinel 없이 tenant baseline effective policy를 컴파일하는 관리 조회 서비스를 만든다.
     *
     * clinic-resolved cache에는 양수 clinic ID가 identity 일부로 들어가므로 tenant 조회가
     * 그것을 재사용하지 않는다. 이 서비스는 tenant scope head와 tenant definition만
     * double-read하고 별도 namespace의 deterministic hash를 반환한다.
     */
    @Bean
    fun tenantEffectiveSchedulingPolicyService(
        schedulingPolicyRepository: SchedulingPolicyRepository,
    ): TenantEffectiveSchedulingPolicyService =
        TenantEffectiveSchedulingPolicyService(schedulingPolicyRepository)

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
        appointmentNotificationWriter: AppointmentNotificationWriter,
        appointmentOutboxWriter: AppointmentOutboxWriter,
        clinicRepository: ClinicRepository,
    ): ClosureRescheduleService = ClosureRescheduleService(
        slotCalculationService,
        appointmentRepository,
        rescheduleCandidateRepository,
        appointmentStateHistoryRepository,
        doctorRepository,
        object : AppointmentRescheduleNotificationWriter {
            override fun rescheduled(
                tenantGroupId: Long,
                original: AppointmentRecord,
                replacement: AppointmentRecord,
                version: Long,
            ) {
                appointmentNotificationWriter.rescheduled(
                    tenantGroupId = tenantGroupId,
                    original = original,
                    replacement = replacement,
                    version = version,
                )
            }

            override fun rescheduled(
                tenantGroupId: Long,
                original: AppointmentRecord,
                replacement: AppointmentRecord,
                version: Long,
                commandContext: AppointmentCommandContext,
            ) {
                appointmentNotificationWriter.rescheduled(
                    tenantGroupId = tenantGroupId,
                    original = original,
                    replacement = replacement,
                    version = version,
                )
                appointmentOutboxWriter.rescheduled(
                    scope = TenantClinicScope(tenantGroupId, original.clinicId),
                    original = original,
                    replacement = replacement,
                    context = AppointmentMessagingContext.from(commandContext),
                )
            }
        },
        clinicRepository,
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

    /**
     * commitment proposal·allocation·격리·운영 예외의 저카디널리티 metric facade를 생성한다.
     */
    @Bean
    fun appointmentCommitmentMetrics(meterRegistry: MeterRegistry): AppointmentCommitmentMetrics =
        AppointmentCommitmentMetrics(meterRegistry)

    /**
     * v2 application transaction이 Spring 관리 [DataSource]와 같은 pool을 사용하도록
     * 명시적인 Exposed database handle을 생성한다.
     *
     * controller/OpenAPI slice가 [AppointmentCommitmentApplicationService]를 mock으로
     * 대체한 경우에는 불필요한 database 조립을 생략한다. 등록·전역 기본 database 복원과
     * context 종료 시 manager 해제는 [ExposedDatabaseFactory]와
     * [ExposedDatabaseLifecycle]이 담당한다.
     */
    @Bean(name = ["appointmentCommitmentDatabase"])
    @ConditionalOnProperty(
        prefix = "appointment.commitment",
        name = ["api-enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(
        value = [
            Database::class,
            AppointmentCommitmentApplicationService::class,
        ],
    )
    internal fun appointmentCommitmentDatabase(dataSource: DataSource): Database =
        ExposedDatabaseFactory.connect(dataSource)

    @Bean(name = ["appointmentCommitmentDatabaseLifecycle"])
    @ConditionalOnBean(name = ["appointmentCommitmentDatabase"])
    internal fun appointmentCommitmentDatabaseLifecycle(database: Database): ExposedDatabaseLifecycle =
        ExposedDatabaseLifecycle(database)

    /**
     * effective-policy service가 만든 snapshot과 command FK용 row ID를 결합한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "appointment.commitment",
        name = ["api-enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(AppointmentCommitmentApplicationService::class)
    internal fun appointmentCommitmentPolicySnapshotResolver(
        database: Database,
        effectiveSchedulingPolicyService: EffectiveSchedulingPolicyService,
        schedulingPolicyRepository: SchedulingPolicyRepository,
    ): AppointmentCommitmentPolicySnapshotResolver =
        EffectiveAppointmentCommitmentPolicySnapshotResolver(
            database = database,
            effectiveSchedulingPolicyService = effectiveSchedulingPolicyService,
            schedulingPolicyRepository = schedulingPolicyRepository,
        )

    /**
     * customer identity와 실제 resource inventory adapter가 없는 기본 배포를 fail-closed로 둔다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "appointment.commitment",
        name = ["api-enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(
        value = [
            AppointmentCommitmentPlanningResolver::class,
            AppointmentCommitmentApplicationService::class,
        ],
    )
    internal fun appointmentCommitmentPlanningResolver(): AppointmentCommitmentPlanningResolver =
        FailClosedAppointmentCommitmentPlanningResolver()

    /**
     * 외부 동의 projection adapter가 아직 연결되지 않은 환경에서 commitment 동의 결정을 닫힌 실패로 둔다.
     *
     * 예약 API는 request body의 opaque evidence ID를 권위로 취급하지 않는다. 병원별 동의
     * 서비스 adapter가 이 bean을 대체하고 tenant·clinic·환자·proposal·정책·약관 metadata를
     * 반환하기 전에는 신규 예약, 직접 확정, 변경 수락이 모두 `CONSENT_REQUIRED`로 차단된다.
     */
    @Bean
    @ConditionalOnMissingBean(AppointmentCommitmentConsentEvidenceVerifier::class)
    internal fun appointmentCommitmentConsentEvidenceVerifier(): AppointmentCommitmentConsentEvidenceVerifier =
        FailClosedAppointmentCommitmentConsentEvidenceVerifier

    /**
     * v2 HTTP controller가 사용하는 실제 application boundary를 command/query 서비스에 연결한다.
     *
     * controller 노출은 `appointment.commitment.api-enabled`가 계속 제어하지만 bean 자체는 항상
     * 생성해 startup 시 production wiring 누락을 먼저 드러낸다.
     * 기본 patient fingerprint resolver는 fail-closed이며, 구매 Plan ingress와 같은
     * HMAC key·algorithm·domain separation을 구현한 bean을 운영 배포에서 제공해야 한다.
     * raw `Idempotency-Key`는 JWT·정책 command와 분리된
     * `appointment.commitment.idempotency-hash-secret`으로 HMAC-SHA-256 처리한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "appointment.commitment",
        name = ["api-enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(AppointmentCommitmentApplicationService::class)
    internal fun appointmentCommitmentApplicationService(
        database: Database,
        properties: AppointmentCommitmentProperties,
        metrics: AppointmentCommitmentMetrics,
        policySnapshotResolver: AppointmentCommitmentPolicySnapshotResolver,
        planningResolver: AppointmentCommitmentPlanningResolver,
        appointmentMemberResolver: AppointmentMemberResolver,
        consentEvidenceVerifier: AppointmentCommitmentConsentEvidenceVerifier,
        patientSubjectFingerprintResolver: PatientSubjectFingerprintResolver,
        tenantGroupRepository: TenantGroupRepository,
        appointmentPlanRepository: AppointmentPlanRepository,
        appointmentRepository: AppointmentRepository,
        appointmentNotificationWriter: AppointmentNotificationWriter,
        bookingReliabilityApplicationPort: ObjectProvider<BookingReliabilityApplicationPort>,
        bookingReliabilityProperties: BookingReliabilityProperties,
        bookingReliabilitySchemaReadiness: ObjectProvider<DefaultBookingReliabilitySchemaReadiness>,
        environment: Environment,
    ): AppointmentCommitmentApplicationService =
        DefaultAppointmentCommitmentApplicationService(
            database = database,
            properties = properties,
            accessResolver =
                io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentAccessResolver(
                    database = database,
                    patientSubjectFingerprintResolver = patientSubjectFingerprintResolver,
                    tenantGroupRepository = tenantGroupRepository,
                    appointmentPlanRepository = appointmentPlanRepository,
                    appointmentRepository = appointmentRepository,
                ),
            commandService =
                io.bluetape4k.clinic.appointment.api.commitment.AppointmentCommitmentCommandService(
                    database = database,
                    maxTransactionAttempts = properties.retry.maxAttempts,
                    initialRetryDelayMillis = properties.retry.initialBackoff.toMillis(),
                    notificationWriter = appointmentNotificationWriter,
                    bookingEligibilityGate =
                        io.bluetape4k.clinic.appointment.api.commitment.BookingEligibilityGate(
                            port = bookingReliabilityApplicationPort.getIfAvailable(),
                            properties = bookingReliabilityProperties,
                            schemaReadiness = bookingReliabilitySchemaReadiness.getIfAvailable(),
                        ),
                ),
            policySnapshotResolver = policySnapshotResolver,
            planningResolver = planningResolver,
            appointmentMemberResolver = appointmentMemberResolver,
            consentEvidenceVerifier = consentEvidenceVerifier,
            metrics = metrics,
            proposalService =
                AppointmentProposalService(
                    limits =
                        PackageExecutionLimits(
                            maximumRepeatCount = properties.ceiling.repeatCount,
                            maximumTreatmentCount = properties.ceiling.plannedTreatments,
                            maximumEdgeCount = properties.ceiling.relationshipEdges,
                            maximumCandidateSlotCount = properties.ceiling.candidateSlots,
                            maximumResourcesPerSlot = properties.ceiling.resourcesPerSlot,
                            maximumCandidateResourceCount = properties.ceiling.candidateResourceEntries,
                            maximumProposalCount = properties.ceiling.returnedProposals,
                        ),
                ),
            idempotencyKeyHasher =
                HmacAppointmentCommitmentIdempotencyKeyHasher(
                    decodeAppointmentCommitmentIdempotencySecret(environment),
                ),
        )

    /**
     * 구매 Plan ingress와 동일한 patient fingerprint adapter가 없으면 patient 접근을 거부한다.
     *
     * 운영 배포는 구매 서비스의 보호된 환자 참조와 정확히 비교할 수 있는
     * [PatientSubjectFingerprintResolver] bean을 반드시 제공해야 한다.
     */
    @Bean
    @ConditionalOnMissingBean(PatientSubjectFingerprintResolver::class)
    internal fun patientSubjectFingerprintResolver(): PatientSubjectFingerprintResolver =
        FailClosedPatientSubjectFingerprintResolver()

    /**
     * 예약 command 전용 Base64 HMAC 비밀값을 검증하고 방어적으로 반환한다.
     *
     * secret이 없거나 Base64가 아니거나 256 bit보다 짧으면 v2 API startup을 실패시켜
     * raw idempotency key가 비키드 digest로 저장되는 구성을 허용하지 않는다.
     */
    private fun decodeAppointmentCommitmentIdempotencySecret(environment: Environment): ByteArray {
        val propertyName = "appointment.commitment.idempotency-hash-secret"
        val encoded = environment.getRequiredProperty(propertyName)
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("$propertyName must be valid Base64", failure)
        }
        check(decoded.size >= 32) { "$propertyName must decode to at least 32 bytes" }
        return decoded
    }

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

    /**
     * 정책 관리 HTTP 계약을 기존 명령·preview·effective 서비스에 연결한다.
     *
     * raw idempotency key를 digest로 영속화할 전용 secret이 설정된 경우에만 생성한다.
     * 기능 flag가 꺼진 상태에서는 facade가 policy path를 404로 닫아 두므로 배포자가
     * `shadow compile → effective read → admin write` 순서를 지키며 노출할 수 있다.
     */
    @Bean
    @ConditionalOnProperty("scheduling.policy.idempotency-hash-secret")
    fun schedulingPolicyAdministrationService(
        commandService: SchedulingPolicyCommandService,
        previewService: SchedulingPolicyPreviewService,
        previewStore: ExposedSchedulingPolicyPreviewStore,
        previewVerifier: PolicyPreviewEvidenceVerifier,
        policyRepository: SchedulingPolicyRepository,
        jobRepository: SchedulingPolicyJobRepository,
        tenantEffectiveService: TenantEffectiveSchedulingPolicyService,
        clinicEffectiveService: EffectiveSchedulingPolicyService,
        metrics: SchedulingPolicyMetrics,
        properties: SchedulingPolicyProperties,
        profileReevaluationProperties: ProfileReevaluationProperties,
    ): SchedulingPolicyAdministrationService =
        SchedulingPolicyAdministrationService(
            commandService = commandService,
            previewService = previewService,
            previewStore = previewStore,
            previewVerifier = previewVerifier,
            policyRepository = policyRepository,
            jobRepository = jobRepository,
            tenantEffectiveService = tenantEffectiveService,
            clinicEffectiveService = clinicEffectiveService,
            metrics = metrics,
            properties = properties,
            profileReevaluationProperties = profileReevaluationProperties,
        )

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
    fun bookingReliabilityRepository(): BookingReliabilityRepository = BookingReliabilityRepository()

    @Bean
    fun bookingReliabilityReevaluationJobRepository(): BookingReliabilityReevaluationJobRepository =
        BookingReliabilityReevaluationJobRepository()

    @Bean
    fun bookingReliabilityMetrics(meterRegistry: MeterRegistry): BookingReliabilityMetrics =
        BookingReliabilityMetrics(meterRegistry)

    @Bean
    fun bookingReliabilityOperationalState(): BookingReliabilityOperationalState =
        BookingReliabilityOperationalState()

    /** V17 테이블·인덱스와 Flyway 최신 version을 확인하는 fail-closed readiness probe입니다. */
    @Bean
    @ConditionalOnBean(DataSource::class)
    fun bookingReliabilitySchemaReadiness(dataSource: DataSource): DefaultBookingReliabilitySchemaReadiness =
        DefaultBookingReliabilitySchemaReadiness(
            BookingReliabilitySchemaProbe {
                dataSource.connection.use { connection ->
                    runCatching {
                        val metadata = connection.metaData
                        val tables = buildSet {
                            metadata.getTables(null, null, "%", arrayOf("TABLE")).use { rows ->
                                while (rows.next()) rows.getString("TABLE_NAME")?.lowercase()?.let(::add)
                            }
                        }
                        val indexes = buildSet {
                            REQUIRED_RELIABILITY_TABLES.forEach { table ->
                                metadata.getIndexInfo(null, null, table, false, false).use { rows ->
                                    while (rows.next()) rows.getString("INDEX_NAME")?.lowercase()?.let(::add)
                                }
                            }
                        }
                        val migrationVersion = connection.prepareStatement(
                            "select version from flyway_schema_history order by installed_rank desc",
                        ).use { statement ->
                            statement.executeQuery().use { rows ->
                                if (rows.next()) rows.getString(1)?.substringBefore('.')?.toIntOrNull() else null
                            }
                        }
                        BookingReliabilitySchemaReadiness(
                            migrationVersion = migrationVersion,
                            requiredTablesPresent = REQUIRED_RELIABILITY_TABLES.all(tables::contains),
                            requiredIndexesPresent = REQUIRED_RELIABILITY_INDEXES.all(indexes::contains),
                            migrationCurrent = migrationVersion?.let { it >= REQUIRED_MIGRATION_VERSION } == true,
                        )
                    }.getOrElse {
                        BookingReliabilitySchemaReadiness(null, false, false, false)
                    }
                }
            },
        )

    /** 식별자 없이 reliability schema/worker backlog 상태만 actuator health에 노출합니다. */
    @Bean
    @ConditionalOnBean(DefaultBookingReliabilitySchemaReadiness::class)
    @ConditionalOnMissingBean(BookingReliabilityHealthIndicator::class)
    fun bookingReliabilityHealthIndicator(
        readiness: DefaultBookingReliabilitySchemaReadiness,
        properties: BookingReliabilityProperties,
        bookingReliabilityRepository: BookingReliabilityRepository,
        operationalState: BookingReliabilityOperationalState,
    ): BookingReliabilityHealthIndicator =
        BookingReliabilityHealthIndicator(
            source = BookingReliabilityHealthSource {
                val schema = readiness.current()
                val operations = if (schema.ready) {
                    runCatching { transaction { bookingReliabilityRepository.summarizeOperations() } }.getOrNull()
                } else null
                BookingReliabilityOperationalSnapshot(
                    schemaReady = schema.ready,
                    pendingJobs = operations?.pendingJobs ?: 0L,
                    oldestBacklogAge = operations?.oldestBacklogAge ?: Duration.ZERO,
                    unavailableDecisions = operations?.unavailableDecisions ?: 0L,
                    deadLetterJobs = operations?.deadLetterJobs ?: 0L,
                    leaseLostJobs = operationalState.leaseLostJobs(),
                    mode = properties.mode,
                )
            },
        )

    /** effective policy와 core evaluator/persistence를 실제 reliability port로 연결합니다. */
    @Bean
    @ConditionalOnMissingBean(BookingReliabilityApplicationPort::class)
    fun bookingReliabilityApplicationPort(
        effectiveSchedulingPolicyService: EffectiveSchedulingPolicyService,
        bookingReliabilityRepository: BookingReliabilityRepository,
        bookingReliabilityProperties: BookingReliabilityProperties,
        bookingReliabilityMetrics: BookingReliabilityMetrics,
    ): BookingReliabilityApplicationPort =
        DefaultBookingReliabilityApplicationAdapter(
            effectivePolicyService = effectiveSchedulingPolicyService,
            repository = bookingReliabilityRepository,
            properties = bookingReliabilityProperties,
            metrics = bookingReliabilityMetrics,
            clock = Clock.systemUTC(),
        )

    @Bean
    @ConditionalOnProperty(
        prefix = "booking.reliability",
        name = ["worker-enabled"],
        havingValue = "true",
    )
    fun bookingReliabilityReevaluationWorker(
        bookingReliabilityReevaluationJobRepository: BookingReliabilityReevaluationJobRepository,
        bookingReliabilityApplicationPort: BookingReliabilityApplicationPort,
        bookingReliabilityProperties: BookingReliabilityProperties,
        bookingReliabilityMetrics: BookingReliabilityMetrics,
        bookingReliabilityOperationalState: BookingReliabilityOperationalState,
        bookingReliabilitySchemaReadiness: ObjectProvider<DefaultBookingReliabilitySchemaReadiness>,
    ): BookingReliabilityReevaluationWorker =
        BookingReliabilityReevaluationWorker(
            jobRepository = bookingReliabilityReevaluationJobRepository,
            applicationPort = bookingReliabilityApplicationPort,
            properties = bookingReliabilityProperties,
            metrics = bookingReliabilityMetrics,
            operationalState = bookingReliabilityOperationalState,
            retryPolicy = BookingReliabilityRetryPolicy(),
            schemaReadiness = bookingReliabilitySchemaReadiness.getIfAvailable(),
        )

    /** core evaluator/persistence port가 제공된 환경에서 reliability HTTP facade를 노출합니다. */
    @Bean
    @ConditionalOnBean(BookingReliabilityApplicationPort::class)
    fun bookingReliabilityApiService(
        port: BookingReliabilityApplicationPort,
        properties: BookingReliabilityProperties,
    ): BookingReliabilityApiService =
        DefaultBookingReliabilityApiService(port, properties, Clock.systemUTC())

    @Bean
    fun dashboardStatsService(
        appointmentStatsRepository: AppointmentStatsRepository,
    ): DashboardStatsService = DashboardStatsService(appointmentStatsRepository)
}
