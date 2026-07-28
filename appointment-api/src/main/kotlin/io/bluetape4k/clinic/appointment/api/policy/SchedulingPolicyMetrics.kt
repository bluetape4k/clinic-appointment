package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration

/** 예약 정책 활성화 worker의 닫힌 결과 분류다. */
enum class PolicyActivationMetricResult {
    /** activation과 outbox 기록이 원자적으로 완료됐다. */
    COMPLETED,
    /** 같은 durable command의 완료 결과를 재사용했다. */
    IDEMPOTENT_REPLAY,
    /** 일시 장애 후 owner-fenced retry가 예약됐다. */
    RETRY,
    /** 허용 지연 또는 시도 횟수를 넘어 prior active를 보존하고 누락됐다. */
    MISSED,
    /** claim 경쟁 또는 lease 상실로 현재 runnable이 쓰기 권한을 잃었다. */
    FENCED,
}

/** 영향도 preview의 닫힌 실행 결과 분류다. */
enum class PolicyPreviewMetricResult {
    /** 동기 예산 안에서 durable evidence까지 완료됐다. */
    COMPLETED_SYNC,
    /** durable worker가 재개한 작업을 마지막 page와 evidence까지 완료했다. */
    COMPLETED_ASYNC,
    /** bounded checkpoint 이후 비동기 worker로 넘겼다. */
    ACCEPTED_ASYNC,
    /** revision 또는 generation 변경으로 partial 결과를 폐기했다. */
    STALE,
    /** durable hard deadline을 넘어 증거 없이 실패했다. */
    DEADLINE,
    /** 명시적 취소를 page boundary에서 관측했다. */
    CANCELLED,
    /** 안정적 분류가 가능한 기타 실패다. */
    FAILED,
}

/** 정책 컴파일의 cold/warm/failure 분류다. */
enum class PolicyCompileMetricResult { COLD, WARM, FAILED }

/** 프로세스 로컬 정책 캐시의 제한된 결과 분류다. */
enum class PolicyCacheMetricResult { HIT, EVICTION, QUOTA, STALE_REJECTION }

/** 권위 generation read의 제한된 결과 분류다. */
enum class PolicyEffectiveReadMetricResult { SUCCESS, CONFLICT, UNAVAILABLE }

/** 정책 outbox backlog와 publish 결과 분류다. */
enum class PolicyOutboxMetricResult { PENDING, PUBLISHED, FAILED, OLDEST_PENDING_AGE }

/** aggregate-null 관측이 허용하는 닫힌 집계 종류다. */
enum class PolicyAggregateMetricKind { APPOINTMENT, PLANNED_TREATMENT }

/** dual-write 비교의 닫힌 결과 분류다. */
enum class PolicyDualWriteMetricResult { MATCHED, MISMATCHED }

/** 정책 관리 HTTP facade가 노출하는 닫힌 작업 분류다. */
enum class PolicyAdministrationMetricOperation {
    CREATE_DRAFT,
    VALIDATE,
    PREVIEW,
    APPROVE,
    SCHEDULE,
    ACTIVATE,
    RETIRE,
    REPLAY,
    PREVIEW_JOB,
    TENANT_EFFECTIVE,
    CLINIC_EFFECTIVE,
}

/** 정책 관리 요청의 application facade 처리 결과다. */
enum class PolicyAdministrationMetricResult { SUCCEEDED, REJECTED }

/**
 * scheduling-policy 관측값을 낮은 cardinality meter로 제한하는 단일 facade다.
 *
 * 공개 메서드는 임의 문자열 tag를 받지 않고 닫힌 enum과 정책 종류·범위만 받는다. 이 구조로
 * tenant/clinic ID, actor ID, 예약 ID, preview token, correlation ID, payload, 예외 메시지가
 * 시계열 label에 들어갈 수 없게 한다. meter backend 예외는 업무 결과·worker fencing을
 * 바꾸지 않도록 이 facade 안에서 격리하고 stack trace만 로컬 진단 로그에 남긴다. 호출자는
 * 상세 업무 원인을 구조화 로그나 추적 시스템에서 별도로 다뤄야 하며 meter tag를 확장해
 * 우회하면 안 된다.
 *
 * @property registry Spring Boot Actuator가 제공하는 프로세스 meter registry.
 */
class SchedulingPolicyMetrics(
    private val registry: MeterRegistry,
) {

    /** activation 완료·재시도·누락·fencing 횟수를 기록한다. */
    fun recordActivation(
        result: PolicyActivationMetricResult,
        kind: SchedulingPolicyKind,
        scope: PolicyScope,
    ) = recordSafely(ACTIVATION) {
        counter(ACTIVATION, result.metricValue(), kind, scope).increment()
    }

    /**
     * 예정 activation 경계 이후 실제 처리까지의 지연을 밀리초 분포로 기록한다.
     *
     * 음수 duration은 clock/source 계약 위반이므로 허용하지 않는다.
     */
    fun recordActivationLateness(
        lateness: Duration,
        kind: SchedulingPolicyKind,
        scope: PolicyScope,
    ) {
        require(!lateness.isNegative) { "lateness must be non-negative" }
        recordSafely(ACTIVATION_LATENESS) {
            DistributionSummary.builder(ACTIVATION_LATENESS)
                .baseUnit("milliseconds")
                .tags(
                    KIND_TAG, kind.metricValue(),
                    SCOPE_TAG, scope.metricValue(),
                )
                .register(registry)
                .record(lateness.toMillis().toDouble())
        }
    }

    /** preview 동기·비동기·stale·deadline·취소 결과를 기록한다. */
    fun recordPreview(
        result: PolicyPreviewMetricResult,
        kind: SchedulingPolicyKind,
        scope: PolicyScope,
    ) = recordSafely(PREVIEW) {
        counter(PREVIEW, result.metricValue(), kind, scope).increment()
    }

    /** 정책 컴파일 cold/warm/failure 결과를 기록한다. */
    fun recordCompile(
        result: PolicyCompileMetricResult,
        kind: SchedulingPolicyKind,
        scope: PolicyScope,
    ) = recordSafely(COMPILE) {
        counter(COMPILE, result.metricValue(), kind, scope).increment()
    }

    /** cache hit/eviction/quota/stale rejection을 기록한다. */
    fun recordCache(
        result: PolicyCacheMetricResult,
        kind: SchedulingPolicyKind,
        scope: PolicyScope,
    ) = recordSafely(CACHE) {
        counter(CACHE, result.metricValue(), kind, scope).increment()
    }

    /** 권위 generation 조회 결과를 scope type만으로 기록한다. */
    fun recordEffectiveRead(
        result: PolicyEffectiveReadMetricResult,
        scope: PolicyScope,
    ) = recordSafely(EFFECTIVE_READ) {
        Counter.builder(EFFECTIVE_READ)
            .tags(RESULT_TAG, result.metricValue(), SCOPE_TAG, scope.metricValue())
            .register(registry)
            .increment()
    }

    /** outbox pending/published/failed/oldest-age 관측을 기록한다. */
    fun recordOutbox(result: PolicyOutboxMetricResult) = recordSafely(OUTBOX) {
        Counter.builder(OUTBOX)
            .tag(RESULT_TAG, result.metricValue())
            .register(registry)
            .increment()
    }

    /** join 또는 projection에서 aggregate가 사라진 경우를 닫힌 종류로 기록한다. */
    fun recordAggregateNull(kind: PolicyAggregateMetricKind) = recordSafely(AGGREGATE_NULL) {
        Counter.builder(AGGREGATE_NULL)
            .tag(RESULT_TAG, kind.metricValue())
            .register(registry)
            .increment()
    }

    /** 이중 쓰기 결과의 parity 비교를 기록한다. */
    fun recordDualWriteParity(result: PolicyDualWriteMetricResult) = recordSafely(DUAL_WRITE_PARITY) {
        Counter.builder(DUAL_WRITE_PARITY)
            .tag(RESULT_TAG, result.metricValue())
            .register(registry)
            .increment()
    }

    /**
     * 정책 관리 요청 결과를 작업 종류와 scope 종류만으로 기록한다.
     *
     * HTTP path, tenant/clinic ID, actor, correlation ID, payload와 오류 상세는 tag로 받지
     * 않는다. 따라서 병원 수나 호출 수가 증가해도 label cardinality는 닫혀 있다.
     */
    fun recordAdministration(
        result: PolicyAdministrationMetricResult,
        operation: PolicyAdministrationMetricOperation,
        scope: PolicyScope,
    ) = recordSafely(ADMINISTRATION) {
        Counter.builder(ADMINISTRATION)
            .tags(
                RESULT_TAG, result.metricValue(),
                OPERATION_TAG, operation.metricValue(),
                SCOPE_TAG, scope.metricValue(),
            )
            .register(registry)
            .increment()
    }

    /**
     * meter backend 실패를 업무 제어 흐름에서 격리한다.
     *
     * [metricName]은 이 class의 고정 상수만 받으므로 tenant·actor·예약 식별자가 로그에
     * 들어가지 않는다. throwable은 registry 구현 오류의 class와 stack을 보존하지만
     * meter tag나 호출자의 업무 예외로 전파하지 않는다. JVM [Error]는 복구 대상이
     * 아니므로 포착하지 않는다.
     */
    private fun recordSafely(
        metricName: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (error: Exception) {
            log.warn(error) {
                "Scheduling policy metric recording failed: metric=$metricName"
            }
        }
    }

    private fun counter(
        name: String,
        result: String,
        kind: SchedulingPolicyKind,
        scope: PolicyScope,
    ): Counter =
        Counter.builder(name)
            .tags(
                RESULT_TAG, result,
                KIND_TAG, kind.metricValue(),
                SCOPE_TAG, scope.metricValue(),
            )
            .register(registry)

    private fun Enum<*>.metricValue(): String = name.lowercase()

    private companion object : KLogging() {
        const val ACTIVATION = "clinic.scheduling.policy.activation"
        const val ACTIVATION_LATENESS = "clinic.scheduling.policy.activation.lateness"
        const val PREVIEW = "clinic.scheduling.policy.preview"
        const val COMPILE = "clinic.scheduling.policy.compile"
        const val CACHE = "clinic.scheduling.policy.cache"
        const val EFFECTIVE_READ = "clinic.scheduling.policy.effective.read"
        const val OUTBOX = "clinic.scheduling.policy.outbox"
        const val AGGREGATE_NULL = "clinic.scheduling.policy.aggregate.null"
        const val DUAL_WRITE_PARITY = "clinic.scheduling.policy.dual.write.parity"
        const val ADMINISTRATION = "clinic.scheduling.policy.administration"
        const val RESULT_TAG = "result"
        const val KIND_TAG = "kind"
        const val OPERATION_TAG = "operation"
        const val SCOPE_TAG = "scope_type"
    }
}
