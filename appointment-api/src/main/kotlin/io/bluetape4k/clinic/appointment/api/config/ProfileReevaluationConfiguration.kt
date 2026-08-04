package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.api.profile.ExposedProfileReevaluationAdminStore
import io.bluetape4k.clinic.appointment.api.profile.ExposedProfileReevaluationWorkStore
import io.bluetape4k.clinic.appointment.api.profile.ProfileAssessmentClient
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationAdminService
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationAdminStore
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationAppointmentProcessor
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDispatcher
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationEndpoint
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationHealthIndicator
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationMetrics
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationMetricsEventObserver
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationOperationalMonitor
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationRedrivePolicy
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationRetryPolicy
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationRuntimeGate
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationWorkStore
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationWorker
import io.bluetape4k.clinic.appointment.api.profile.RestClientProfileAssessmentClient
import io.bluetape4k.clinic.appointment.event.profile.ProfileReevaluationEventObserver
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.lang.management.ManagementFactory
import javax.sql.DataSource

/**
 * 프로필 재평가의 fail-closed 설정, 운영 조회, 선택적 worker graph를 조립합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileReevaluationProperties::class)
@EnableScheduling
class ProfileReevaluationConfiguration {
    /**
     * 재평가 worker와 운영 endpoint가 Spring DataSource와 같은 pool을 사용하게 합니다.
     */
    @Bean
    @ConditionalOnMissingBean(Database::class)
    fun profileReevaluationDatabase(dataSource: DataSource): Database =
        ExposedDatabaseFactory.connect(dataSource)

    @Bean
    @ConditionalOnBean(name = ["profileReevaluationDatabase"])
    internal fun profileReevaluationDatabaseLifecycle(database: Database): ExposedDatabaseLifecycle =
        ExposedDatabaseLifecycle(database)

    @Bean
    @ConditionalOnMissingBean
    fun profileReevaluationRepository(
        properties: ProfileReevaluationProperties,
        appointmentRepository: AppointmentRepository,
    ): ProfileReevaluationRepository =
        ProfileReevaluationRepository(
            leaseDuration = properties.leaseDuration,
            retryDelay = properties.retryInitialBackoff,
            maxAttempts = properties.retryMaxAttempts,
            hasHeldAppointments = appointmentRepository::hasHeldProfileReevaluationAppointments,
        )

    @Bean
    fun profileReevaluationRuntimeGate(
        properties: ProfileReevaluationProperties,
    ): ProfileReevaluationRuntimeGate =
        ProfileReevaluationRuntimeGate { properties.runtimeAccess() }

    @Bean
    fun profileReevaluationOperationalMonitor(): ProfileReevaluationOperationalMonitor =
        ProfileReevaluationOperationalMonitor()

    @Bean
    fun profileReevaluationMetrics(
        registry: MeterRegistry,
        monitor: ProfileReevaluationOperationalMonitor,
    ): ProfileReevaluationMetrics =
        ProfileReevaluationMetrics(registry, monitor)

    @Bean
    fun profileReevaluationEventObserver(
        metrics: ProfileReevaluationMetrics,
    ): ProfileReevaluationEventObserver =
        ProfileReevaluationMetricsEventObserver(metrics)

    @Bean
    fun profileReevaluationWorkStore(
        database: Database,
        repository: ProfileReevaluationRepository,
        appointmentRepository: AppointmentRepository,
        metrics: ProfileReevaluationMetrics,
    ): ProfileReevaluationWorkStore =
        ExposedProfileReevaluationWorkStore(database, repository, appointmentRepository, metrics)

    @Bean
    fun profileReevaluationAdminStore(
        database: Database,
        repository: ProfileReevaluationRepository,
        runtimeGate: ProfileReevaluationRuntimeGate,
        monitor: ProfileReevaluationOperationalMonitor,
    ): ProfileReevaluationAdminStore =
        ExposedProfileReevaluationAdminStore(database, repository, runtimeGate, monitor)

    @Bean
    fun profileReevaluationAdminService(
        store: ProfileReevaluationAdminStore,
        properties: ProfileReevaluationProperties,
    ): ProfileReevaluationAdminService =
        ProfileReevaluationAdminService(store, properties.autoRedriveCooldown)

    @Bean
    fun profileReevaluationEndpoint(
        service: ProfileReevaluationAdminService,
    ): ProfileReevaluationEndpoint = ProfileReevaluationEndpoint(service)

    @Bean
    fun profileReevaluationHealthIndicator(
        service: ProfileReevaluationAdminService,
    ): ProfileReevaluationHealthIndicator =
        ProfileReevaluationHealthIndicator(service)

    @Bean
    @ConditionalOnMissingBean(ProfileAssessmentClient::class)
    @ConditionalOnProperty(
        prefix = "appointment.profile-reevaluation",
        name = ["enabled"],
        havingValue = "true",
    )
    fun profileAssessmentClient(
        properties: ProfileReevaluationProperties,
        registry: MeterRegistry,
        metrics: ProfileReevaluationMetrics,
    ): ProfileAssessmentClient {
        val assessment = properties.assessment
        return RestClientProfileAssessmentClient(
            baseUrl = assessment.requireUsableEndpoint(),
            allowedHosts = assessment.allowedHosts,
            connectTimeout = assessment.connectTimeout,
            readTimeout = assessment.readTimeout,
            maxResponseBytes = assessment.maxResponseBytes,
            maxConcurrency = assessment.maxConcurrency,
            meterRegistry = registry,
            reevaluationMetrics = metrics,
        )
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "appointment.profile-reevaluation",
        name = ["enabled"],
        havingValue = "true",
    )
    fun profileReevaluationWorker(
        store: ProfileReevaluationWorkStore,
        assessmentClient: ProfileAssessmentClient,
        processor: ProfileReevaluationAppointmentProcessor,
        runtimeGate: ProfileReevaluationRuntimeGate,
        properties: ProfileReevaluationProperties,
    ): ProfileReevaluationWorker =
        ProfileReevaluationWorker(
            store = store,
            assessmentClient = assessmentClient,
            appointmentProcessor = processor,
            runtimeGate = runtimeGate,
            retryPolicy = ProfileReevaluationRetryPolicy(
                maxAttempts = properties.retryMaxAttempts,
                maxElapsedTime = properties.retryMaxElapsedTime,
                initialBackoff = properties.retryInitialBackoff,
                maxBackoff = properties.retryMaxBackoff,
                jitterRatio = properties.retryJitter,
            ),
            maxAppointmentsPerTick = properties.maxAppointmentsPerTick,
            pageSize = properties.pageSize,
            leaseRenewInterval = properties.leaseRenewInterval,
        )

    @Bean
    @ConditionalOnBean(ProfileReevaluationWorkStore::class, ProfileReevaluationWorker::class)
    fun profileReevaluationDispatcher(
        store: ProfileReevaluationWorkStore,
        worker: ProfileReevaluationWorker,
        runtimeGate: ProfileReevaluationRuntimeGate,
        metrics: ProfileReevaluationMetrics,
        properties: ProfileReevaluationProperties,
    ): ProfileReevaluationDispatcher =
        ProfileReevaluationDispatcher(
            store = store,
            worker = worker,
            leaseOwner = "profile-reevaluation:${ManagementFactory.getRuntimeMXBean().name}",
            globalConcurrency = properties.globalConcurrency,
            perClinicConcurrency = properties.perClinicConcurrency,
            runtimeGate = runtimeGate,
            redrivePolicy = ProfileReevaluationRedrivePolicy(
                maxRedrives = properties.autoRedriveMax,
                cooldown = properties.autoRedriveCooldown,
            ),
            autoRedriveLimit = maxOf(1, properties.autoRedriveMax),
            metrics = metrics,
        )

    @Bean
    @ConditionalOnBean(ProfileReevaluationDispatcher::class)
    fun profileReevaluationSchedulingRunner(
        dispatcher: ProfileReevaluationDispatcher,
    ): ProfileReevaluationSchedulingRunner =
        ProfileReevaluationSchedulingRunner(dispatcher)
}

/**
 * 애플리케이션 준비 직후와 설정된 고정 간격마다 재평가 작업을 제한된 한 tick만큼 처리합니다.
 */
class ProfileReevaluationSchedulingRunner(
    private val dispatcher: ProfileReevaluationDispatcher,
) {
    @Scheduled(
        fixedDelayString = "\${appointment.profile-reevaluation.poll-interval:PT1S}",
        initialDelayString = "PT0S",
    )
    suspend fun poll() {
        dispatcher.dispatchOnce()
    }
}
