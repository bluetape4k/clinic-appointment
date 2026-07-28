package io.bluetape4k.clinic.appointment.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * scheduling-policy 기능 공개 순서와 비동기 작업의 자원 상한을 정의한다.
 *
 * 이 설정은 병원별 업무 정책의 내용이 아니라 SaaS 배포 단위의 안전 장치다. 모든 기능은
 * 기본적으로 꺼져 있으며, `shadow compile → effective read → admin write → preview worker
 * → scheduled activation` 순서가 뒤집히면 애플리케이션 시작을 거부한다. 따라서 후행
 * 기능이 선행 읽기·쓰기 계약보다 먼저 노출되는 부분 배포를 허용하지 않는다.
 *
 * worker 관련 값은 운영자가 줄일 수는 있지만 코드가 검증한 최대값보다 늘릴 수 없다.
 * 이 상한은 한 테넌트의 대량 미리보기나 activation burst가 데이터베이스 연결과 worker
 * thread를 독점하지 못하게 하는 제품 계약이다.
 *
 * @property shadowCompileEnabled 활성 정책을 예약 결정에 적용하지 않고 컴파일만 검증하는 단계다.
 * @property effectiveReadEnabled 권위 세대를 이중 확인한 effective snapshot 조회를 허용한다.
 * @property adminWriteEnabled 정책 draft·승인·활성화 관리 명령을 허용한다.
 * @property previewWorkerEnabled durable 영향도 미리보기 작업의 비동기 claim을 허용한다.
 * @property scheduledActivationEnabled 예약 시각이 지난 activation command의 worker claim을 허용한다.
 * @property previewPageSize impact repository가 한 트랜잭션에서 반환할 수 있는 최대 key 수다.
 * @property previewSyncRowLimit 동기 요청이 완료될 수 있는 최대 누적 scan 행 수다.
 * @property previewSyncDeadline 동기 preview가 비동기 작업으로 전환되기 전 단조 시간 예산이다.
 * @property previewJobDeadline 동기·비동기를 포함한 durable preview 전체 hard deadline이다.
 * @property previewHorizon 요청 시각부터 미래 예약·시술 의무를 영향도 scan에 포함하는 기간이다.
 * request body에서 받지 않아 한 caller가 임의로 무제한 범위를 선택할 수 없게 한다.
 * @property previewPollInterval 같은 tenant/scope/job의 비종결 polling을 다시 허용하는 최소 간격이다.
 * @property previewQueueCapacity 한 병원 scope가 보유할 수 있는 runnable preview 요청 수다.
 * @property previewTenantConcurrency 같은 테넌트에서 동시에 실행할 수 있는 preview 수다.
 * @property maxPreviewJobsPerTick 한 scheduled tick이 claim할 수 있는 preview job 상한이다.
 * @property maxActivationClaimsPerTick 한 scheduled tick이 claim할 수 있는 activation command 상한이다.
 * @property workerPollInterval startup catch-up 이후 durable due queue를 다시 확인하는 고정 지연이다.
 * @property workerLease claim 소유권을 보장하는 짧은 DB 시각 기준 lease 기간이다.
 * @property workerShutdownGrace 종료 시 진행 중 작업을 기다리는 최대 기간이다. 이후 행은 회수 가능하게 남긴다.
 * @property activationLatenessWarning 예정 시각 대비 운영 경고를 기록하는 지연 임계값이다.
 * @property activationMissedAfter prior active 정책을 보존하고 command를 `MISSED`로 종결하는 최대 지연이다.
 * @property activationMaxAttempts 일시 실패를 재시도할 수 있는 총 claim 횟수다.
 * @property activationInitialBackoff 첫 일시 실패 후 적용하는 지수 backoff 기준값이다.
 * @property activationMaxBackoff 지수 backoff가 커질 수 있는 최대값이다.
 * @property activationJitter 동일 시각 command가 동시에 재시도되는 것을 완화하는 결정적 jitter 비율이다.
 */
@ConfigurationProperties(prefix = "scheduling.policy")
data class SchedulingPolicyProperties(
    val shadowCompileEnabled: Boolean = false,
    val effectiveReadEnabled: Boolean = false,
    val adminWriteEnabled: Boolean = false,
    val previewWorkerEnabled: Boolean = false,
    val scheduledActivationEnabled: Boolean = false,
    val previewPageSize: Int = MAX_PREVIEW_PAGE_SIZE,
    val previewSyncRowLimit: Int = MAX_SYNC_ROW_LIMIT,
    val previewSyncDeadline: Duration = Duration.ofSeconds(2),
    val previewJobDeadline: Duration = Duration.ofMinutes(5),
    val previewHorizon: Duration = Duration.ofDays(30),
    val previewPollInterval: Duration = Duration.ofSeconds(1),
    val previewQueueCapacity: Int = MAX_PREVIEW_QUEUE_CAPACITY,
    val previewTenantConcurrency: Int = MAX_TENANT_CONCURRENCY,
    val maxPreviewJobsPerTick: Int = 10,
    val maxActivationClaimsPerTick: Int = 25,
    val workerPollInterval: Duration = Duration.ofSeconds(1),
    val workerLease: Duration = Duration.ofSeconds(30),
    val workerShutdownGrace: Duration = Duration.ofSeconds(10),
    val activationLatenessWarning: Duration = Duration.ofSeconds(60),
    val activationMissedAfter: Duration = Duration.ofMinutes(5),
    val activationMaxAttempts: Int = 5,
    val activationInitialBackoff: Duration = Duration.ofSeconds(5),
    val activationMaxBackoff: Duration = Duration.ofMinutes(2),
    val activationJitter: Double = 0.20,
) {
    init {
        require(!effectiveReadEnabled || shadowCompileEnabled) {
            "effectiveReadEnabled requires shadowCompileEnabled"
        }
        require(!adminWriteEnabled || effectiveReadEnabled) {
            "adminWriteEnabled requires effectiveReadEnabled"
        }
        require(!previewWorkerEnabled || adminWriteEnabled) {
            "previewWorkerEnabled requires adminWriteEnabled"
        }
        require(!scheduledActivationEnabled || previewWorkerEnabled) {
            "scheduledActivationEnabled requires previewWorkerEnabled"
        }
        require(previewPageSize in 1..MAX_PREVIEW_PAGE_SIZE) {
            "previewPageSize must be in 1..$MAX_PREVIEW_PAGE_SIZE"
        }
        require(previewSyncRowLimit in previewPageSize..MAX_SYNC_ROW_LIMIT) {
            "previewSyncRowLimit must be in previewPageSize..$MAX_SYNC_ROW_LIMIT"
        }
        requirePositiveBounded(previewSyncDeadline, MAX_SYNC_DEADLINE, "previewSyncDeadline")
        requirePositiveBounded(previewJobDeadline, MAX_PREVIEW_JOB_DEADLINE, "previewJobDeadline")
        require(previewJobDeadline > previewSyncDeadline) {
            "previewJobDeadline must be later than previewSyncDeadline"
        }
        requirePositiveBounded(previewHorizon, MAX_PREVIEW_HORIZON, "previewHorizon")
        requirePositiveBounded(previewPollInterval, MAX_PREVIEW_POLL_INTERVAL, "previewPollInterval")
        require(previewQueueCapacity in 1..MAX_PREVIEW_QUEUE_CAPACITY) {
            "previewQueueCapacity must be in 1..$MAX_PREVIEW_QUEUE_CAPACITY"
        }
        require(previewTenantConcurrency in 1..MAX_TENANT_CONCURRENCY) {
            "previewTenantConcurrency must be in 1..$MAX_TENANT_CONCURRENCY"
        }
        require(maxPreviewJobsPerTick in 1..MAX_PREVIEW_JOBS_PER_TICK) {
            "maxPreviewJobsPerTick must be in 1..$MAX_PREVIEW_JOBS_PER_TICK"
        }
        require(maxActivationClaimsPerTick in 1..MAX_ACTIVATION_CLAIMS_PER_TICK) {
            "maxActivationClaimsPerTick must be in 1..$MAX_ACTIVATION_CLAIMS_PER_TICK"
        }
        requirePositiveBounded(workerPollInterval, MAX_WORKER_POLL_INTERVAL, "workerPollInterval")
        requirePositiveBounded(workerLease, MAX_WORKER_LEASE, "workerLease")
        requirePositiveBounded(workerShutdownGrace, MAX_SHUTDOWN_GRACE, "workerShutdownGrace")
        requirePositiveBounded(
            activationLatenessWarning,
            activationMissedAfter,
            "activationLatenessWarning",
        )
        requirePositiveBounded(activationMissedAfter, MAX_ACTIVATION_MISSED_AFTER, "activationMissedAfter")
        require(activationMaxAttempts in 1..MAX_ACTIVATION_ATTEMPTS) {
            "activationMaxAttempts must be in 1..$MAX_ACTIVATION_ATTEMPTS"
        }
        requirePositiveBounded(activationInitialBackoff, activationMaxBackoff, "activationInitialBackoff")
        requirePositiveBounded(activationMaxBackoff, MAX_ACTIVATION_BACKOFF, "activationMaxBackoff")
        require(activationJitter in 0.0..MAX_ACTIVATION_JITTER) {
            "activationJitter must be in 0.0..$MAX_ACTIVATION_JITTER"
        }
    }

    private fun requirePositiveBounded(value: Duration, maximum: Duration, name: String) {
        require(!value.isZero && !value.isNegative && value <= maximum) {
            "$name must be positive and no greater than $maximum"
        }
    }

    private companion object {
        const val MAX_PREVIEW_PAGE_SIZE = 5_000
        const val MAX_SYNC_ROW_LIMIT = 10_000
        const val MAX_PREVIEW_QUEUE_CAPACITY = 100
        const val MAX_TENANT_CONCURRENCY = 2
        const val MAX_PREVIEW_JOBS_PER_TICK = 100
        const val MAX_ACTIVATION_CLAIMS_PER_TICK = 100
        const val MAX_ACTIVATION_ATTEMPTS = 10
        const val MAX_ACTIVATION_JITTER = 0.50
        val MAX_SYNC_DEADLINE: Duration = Duration.ofSeconds(2)
        val MAX_PREVIEW_JOB_DEADLINE: Duration = Duration.ofMinutes(30)
        val MAX_PREVIEW_HORIZON: Duration = Duration.ofDays(366)
        val MAX_PREVIEW_POLL_INTERVAL: Duration = Duration.ofMinutes(1)
        val MAX_WORKER_POLL_INTERVAL: Duration = Duration.ofMinutes(1)
        val MAX_WORKER_LEASE: Duration = Duration.ofMinutes(2)
        val MAX_SHUTDOWN_GRACE: Duration = Duration.ofSeconds(30)
        val MAX_ACTIVATION_MISSED_AFTER: Duration = Duration.ofMinutes(30)
        val MAX_ACTIVATION_BACKOFF: Duration = Duration.ofMinutes(10)
    }
}
