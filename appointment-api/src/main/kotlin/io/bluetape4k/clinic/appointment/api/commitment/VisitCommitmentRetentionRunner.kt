package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.io.Serializable
import java.time.Clock
import java.time.Duration

/**
 * 활성 tenant·clinic을 짧게 조회한 뒤 scope별 retention transaction을 순차 실행합니다.
 *
 * 한 scope 실패가 다른 병원의 보존 정책을 막지 않으며, 결과 로그와 metric에는 tenant
 * code·`clinic-{id}`·건수만 남깁니다. 삭제된 record ID나 event·patient·product 식별자는
 * 노출하지 않습니다. 여러 인스턴스가 동시에 실행해도 실제 payload 만료와 audit은
 * [VisitCommitmentRetentionService]의 조건부 update가 한 번만 성공하게 합니다.
 *
 * @property scopePageSize 활성 clinic을 메모리에 한꺼번에 적재하지 않도록 keyset 방식으로
 * 조회할 한 page의 최대 scope 수입니다.
 */
class VisitCommitmentRetentionRunner(
    private val database: Database,
    private val retentionService: VisitCommitmentRetentionService,
    private val metrics: AppointmentCommitmentMetrics,
    private val clock: Clock = Clock.systemUTC(),
    private val scopePageSize: Int = 500,
) {
    init {
        require(scopePageSize in 1..5_000) { "scopePageSize must be between 1 and 5000" }
    }

    /**
     * 현재 활성 SaaS scope를 한 번 순회하고 성공·실패 scope 수와 변경 건수를 반환합니다.
     */
    fun runOnce(): VisitCommitmentRetentionRunSummary {
        var totalScopes = 0
        var successfulScopes = 0
        var failedScopes = 0
        var affectedRecords = 0
        var afterClinicId = 0L
        while (true) {
            val scopes = loadActiveScopes(afterClinicId)
            if (scopes.isEmpty()) break
            scopes.forEach { scope ->
                totalScopes += 1
                val startedAt = clock.instant()
                try {
                    val result = retentionService.cleanupTenant(scope.tenantGroupId, scope.clinicId)
                    successfulScopes += 1
                    affectedRecords += result.affectedRecordCount
                    recordMetric(
                        scope = scope,
                        result = CommitmentRetentionRunResult.SUCCESS,
                        latency = Duration.between(startedAt, clock.instant()).coerceAtLeast(Duration.ZERO),
                    )
                } catch (failure: Exception) {
                    failedScopes += 1
                    recordMetric(
                        scope = scope,
                        result = CommitmentRetentionRunResult.FAILED,
                        latency = Duration.between(startedAt, clock.instant()).coerceAtLeast(Duration.ZERO),
                    )
                    log.error(failure) {
                        "commitment retention failed: tenant=${scope.tenantCode}, clinic=${scope.clinicId}"
                    }
                }
            }
            afterClinicId = scopes.last().clinicId
        }
        return VisitCommitmentRetentionRunSummary(
            totalScopes = totalScopes,
            successfulScopes = successfulScopes,
            failedScopes = failedScopes,
            affectedRecords = affectedRecords,
        ).also { summary ->
            log.info {
                "commitment retention completed: scopes=${summary.totalScopes}, " +
                    "success=${summary.successfulScopes}, failed=${summary.failedScopes}, " +
                    "affected=${summary.affectedRecords}"
            }
        }
    }

    /**
     * scope discovery 자체가 실패하면 tenant·clinic tag를 추정하지 않고 고정 메시지로 기록한다.
     *
     * 이 단계에서는 안전한 scope를 아직 알 수 없어 per-scope 실패 metric을 만들 수 없다.
     * 예외는 scheduler error handler와 외부 job 실패 상태가 감지하도록 다시 전달한다.
     */
    private fun loadActiveScopes(afterClinicId: Long): List<RetentionScope> =
        try {
            activeScopes(afterClinicId)
        } catch (failure: Exception) {
            log.error(failure) {
                "commitment retention scope discovery failed"
            }
            throw failure
        }

    private fun activeScopes(afterClinicId: Long): List<RetentionScope> =
        transaction(database) {
            Clinics
                .innerJoin(TenantGroups)
                .select(Clinics.tenantGroupId, Clinics.id, TenantGroups.tenantCode)
                .where {
                    (TenantGroups.active eq true) and
                        (Clinics.id greater afterClinicId)
                }
                .orderBy(Clinics.id, SortOrder.ASC)
                .limit(scopePageSize)
                .map { row ->
                    RetentionScope(
                        tenantGroupId = row[Clinics.tenantGroupId].value,
                        clinicId = row[Clinics.id].value,
                        tenantCode = row[TenantGroups.tenantCode],
                    )
                }
        }

    private fun recordMetric(
        scope: RetentionScope,
        result: CommitmentRetentionRunResult,
        latency: Duration,
    ) {
        try {
            metrics.recordRetentionRun(
                tenant = scope.tenantCode,
                clinic = "clinic-${scope.clinicId}",
                result = result,
                latency = latency,
            )
        } catch (failure: Exception) {
            log.error(failure) {
                "commitment retention metric failed: tenant=${scope.tenantCode}, clinic=${scope.clinicId}"
            }
        }
    }

    private data class RetentionScope(
        val tenantGroupId: Long,
        val clinicId: Long,
        val tenantCode: String,
    )

    private companion object : KLogging()
}

/**
 * 한 retention 순회의 저카디널리티 운영 결과입니다.
 *
 * @property totalScopes 조회된 활성 tenant·clinic 수입니다.
 * @property successfulScopes 오류 없이 cleanup을 마친 scope 수입니다.
 * @property failedScopes 오류를 격리하고 다음 scope로 진행한 수입니다.
 * @property affectedRecords 삭제되거나 암호화 payload가 만료된 record 총수입니다.
 */
data class VisitCommitmentRetentionRunSummary(
    val totalScopes: Int,
    val successfulScopes: Int,
    val failedScopes: Int,
    val affectedRecords: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * retention 서비스와 명시적 운영 runner를 일반 bean으로 등록합니다.
 *
 * scheduler는 별도 설정이므로 외부 CronJob이나 관리 도구가 [VisitCommitmentRetentionRunner]
 * 를 직접 호출하는 배포도 같은 bounded 업무 경계를 재사용할 수 있습니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(Database::class)
class VisitCommitmentRetentionConfiguration {
    /** Spring이 제공한 database로 tenant-scoped retention 서비스를 조립합니다. */
    @Bean
    fun visitCommitmentRetentionService(database: Database): VisitCommitmentRetentionService =
        VisitCommitmentRetentionService(database)

    /** 수동 실행과 scheduler가 공유할 bounded retention runner를 조립합니다. */
    @Bean
    fun visitCommitmentRetentionRunner(
        database: Database,
        retentionService: VisitCommitmentRetentionService,
        metrics: AppointmentCommitmentMetrics,
    ): VisitCommitmentRetentionRunner =
        VisitCommitmentRetentionRunner(database, retentionService, metrics)
}

/**
 * 단일-process scheduler를 명시적으로 선택한 배포에서만 retention을 주기 실행합니다.
 *
 * 다중 replica 배포는 이 flag를 한 owner에서만 켜거나 외부 CronJob을 사용해야 합니다.
 * 데이터 변경 자체는 조건부 update로 안전하지만 중복 스캔 비용을 피하려면 scheduling
 * ownership을 하나로 제한합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "appointment.commitment",
    name = ["retention-enabled"],
    havingValue = "true",
)
class VisitCommitmentRetentionSchedulingConfiguration(
    private val runner: VisitCommitmentRetentionRunner,
) {
    /** 설정한 fixed delay마다 한 번의 bounded retention 순회를 실행합니다. */
    @Scheduled(fixedDelayString = "\${appointment.commitment.retention-interval:PT1H}")
    fun runRetention() {
        runner.runOnce()
    }
}

private val VisitCommitmentRetentionResult.affectedRecordCount: Int
    get() =
        deletedIdempotencyIds.size +
            deletedInboxIds.size +
            deletedOutboxIds.size +
            expiredQuarantinePayloadIds.size
