package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttemptOutcome
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import java.io.Serializable
import java.time.Duration

/**
 * notification outbox 초기 alert 기준을 코드로 고정합니다.
 *
 * alert label은 낮은 cardinality 값만 허용하고 raw tenant/clinic/member/appointment/outbox
 * 식별자는 포함하지 않는다.
 */
class NotificationOutboxAlertPolicy {

    fun evaluate(sample: NotificationOutboxAlertSample): List<NotificationOutboxAlert> =
        buildList {
            evaluateOldest(sample)?.let(::add)
            evaluateExhausted(sample)?.let(::add)
            evaluateProviderFailure(sample)?.let(::add)
            evaluateUnknown(sample)?.let(::add)
            evaluateLeaseRecovery(sample)?.let(::add)
            evaluatePending(sample)?.let(::add)
            if (sample.emergencyKeyRevoked) add(keyAlert(NotificationOutboxAlertSignal.EMERGENCY_KEY_REVOKED))
            if (sample.keyLookupFailures > 0) add(keyAlert(NotificationOutboxAlertSignal.KEY_LOOKUP_FAILURE))
        }

    /**
     * 설계에 고정된 안정 구간을 충족해 해제할 수 있는 alert signal을 반환합니다.
     *
     * key revoke와 lookup 장애는 새 key 배포·검증 절차가 필요하므로 자동 해제하지 않습니다.
     */
    fun clearedSignals(sample: NotificationOutboxAlertSample): Set<NotificationOutboxAlertSignal> =
        buildSet {
            if (sample.oldestActiveAge < Duration.ofMinutes(5) &&
                sample.oldestHealthyDuration >= Duration.ofMinutes(10)
            ) {
                add(NotificationOutboxAlertSignal.OLDEST_ACTIVE_AGE)
            }
            if (sample.exhaustedInFiveMinutes == 0 &&
                sample.exhaustedZeroDuration >= Duration.ofMinutes(15)
            ) {
                add(NotificationOutboxAlertSignal.EXHAUSTED)
            }
            if (sample.providerAttempts >= MIN_RATIO_ATTEMPTS &&
                sample.providerFailureRatio() < 0.05 &&
                sample.providerHealthyDuration >= Duration.ofMinutes(15)
            ) {
                add(NotificationOutboxAlertSignal.PROVIDER_FAILURE_RATIO)
            }
            if (sample.unknownInFiveMinutes == 0 && sample.unknownCauseAcknowledged) {
                add(NotificationOutboxAlertSignal.DELIVERY_RESULT_UNKNOWN)
            }
            if (sample.deliveryAttempts >= MIN_RATIO_ATTEMPTS &&
                sample.leaseRecoveryRatio() < 0.01 &&
                sample.leaseRecoveryHealthyDuration >= Duration.ofMinutes(15)
            ) {
                add(NotificationOutboxAlertSignal.LEASE_RECOVERY_RATIO)
            }
            if (sample.pendingDecreasingDuration >= Duration.ofMinutes(15)) {
                add(NotificationOutboxAlertSignal.PENDING_BACKLOG)
            }
        }

    private fun evaluateOldest(sample: NotificationOutboxAlertSample): NotificationOutboxAlert? =
        when {
            sample.oldestActiveAge > Duration.ofMinutes(30) &&
                sample.oldestBreachDuration >= Duration.ofMinutes(5) ->
                alert(sample, NotificationOutboxAlertSignal.OLDEST_ACTIVE_AGE, NotificationOutboxAlertSeverity.CRITICAL)

            sample.oldestActiveAge > Duration.ofMinutes(5) &&
                sample.oldestBreachDuration >= Duration.ofMinutes(10) ->
                alert(sample, NotificationOutboxAlertSignal.OLDEST_ACTIVE_AGE, NotificationOutboxAlertSeverity.WARNING)

            else -> null
        }

    private fun evaluateExhausted(sample: NotificationOutboxAlertSample): NotificationOutboxAlert? =
        when {
            sample.exhaustedInFiveMinutes >= 10 ->
                alert(sample, NotificationOutboxAlertSignal.EXHAUSTED, NotificationOutboxAlertSeverity.CRITICAL)

            sample.exhaustedInFiveMinutes >= 1 ->
                alert(sample, NotificationOutboxAlertSignal.EXHAUSTED, NotificationOutboxAlertSeverity.TICKET)

            else -> null
        }

    private fun evaluateProviderFailure(sample: NotificationOutboxAlertSample): NotificationOutboxAlert? {
        if (sample.providerAttempts < MIN_RATIO_ATTEMPTS ||
            sample.providerFailureBreachDuration < Duration.ofMinutes(5)
        ) {
            return null
        }
        val ratio = sample.providerFailureRatio()
        return when {
            ratio >= 0.50 ->
                alert(sample, NotificationOutboxAlertSignal.PROVIDER_FAILURE_RATIO, NotificationOutboxAlertSeverity.CRITICAL)

            ratio >= 0.20 ->
                alert(sample, NotificationOutboxAlertSignal.PROVIDER_FAILURE_RATIO, NotificationOutboxAlertSeverity.WARNING)

            else -> null
        }
    }

    private fun evaluateUnknown(sample: NotificationOutboxAlertSample): NotificationOutboxAlert? =
        when {
            sample.unknownInFiveMinutes >= 5 ->
                alert(sample, NotificationOutboxAlertSignal.DELIVERY_RESULT_UNKNOWN, NotificationOutboxAlertSeverity.CRITICAL)

            sample.unknownInFiveMinutes >= 1 ->
                alert(sample, NotificationOutboxAlertSignal.DELIVERY_RESULT_UNKNOWN, NotificationOutboxAlertSeverity.WARNING)

            else -> null
        }

    private fun evaluateLeaseRecovery(sample: NotificationOutboxAlertSample): NotificationOutboxAlert? {
        if (sample.deliveryAttempts < MIN_RATIO_ATTEMPTS ||
            sample.leaseRecoveryBreachDuration < Duration.ofMinutes(10)
        ) {
            return null
        }
        val ratio = sample.leaseRecoveryRatio()
        return if (ratio > 0.05) {
            alert(sample, NotificationOutboxAlertSignal.LEASE_RECOVERY_RATIO, NotificationOutboxAlertSeverity.WARNING)
        } else {
            null
        }
    }

    private fun evaluatePending(sample: NotificationOutboxAlertSample): NotificationOutboxAlert? =
        if (sample.pendingBacklog > 10_000 && sample.pendingIncreasingDuration >= Duration.ofMinutes(10)) {
            alert(sample, NotificationOutboxAlertSignal.PENDING_BACKLOG, NotificationOutboxAlertSeverity.WARNING)
        } else {
            null
        }

    private fun alert(
        sample: NotificationOutboxAlertSample,
        signal: NotificationOutboxAlertSignal,
        severity: NotificationOutboxAlertSeverity,
    ): NotificationOutboxAlert =
        NotificationOutboxAlert(
            signal = signal,
            severity = severity,
            labels = sample.lowCardinalityLabels(),
        )

    private fun keyAlert(signal: NotificationOutboxAlertSignal): NotificationOutboxAlert =
        NotificationOutboxAlert(
            signal = signal,
            severity = NotificationOutboxAlertSeverity.CRITICAL,
            owners = setOf(NotificationOnCallOwner.SECURITY, NotificationOnCallOwner.NOTIFICATION),
            enqueueReadinessStatus = 503,
        )

    companion object {
        private const val MIN_RATIO_ATTEMPTS = 100

        val ALLOWED_LABEL_KEYS: Set<String> = setOf("channel", "event_type", "outcome", "provider_category")
    }
}

/** alert 판정에 필요한 낮은 cardinality 집계와 안정 구간입니다. */
data class NotificationOutboxAlertSample(
    val oldestActiveAge: Duration = Duration.ZERO,
    val oldestBreachDuration: Duration = Duration.ZERO,
    val exhaustedInFiveMinutes: Int = 0,
    val providerAttempts: Int = 0,
    val providerFailures: Int = 0,
    val providerFailureBreachDuration: Duration = Duration.ZERO,
    val unknownInFiveMinutes: Int = 0,
    val leaseRecoveries: Int = 0,
    val deliveryAttempts: Int = 0,
    val leaseRecoveryBreachDuration: Duration = Duration.ZERO,
    val pendingBacklog: Long = 0,
    val pendingIncreasingDuration: Duration = Duration.ZERO,
    val oldestHealthyDuration: Duration = Duration.ZERO,
    val exhaustedZeroDuration: Duration = Duration.ZERO,
    val providerHealthyDuration: Duration = Duration.ZERO,
    val unknownCauseAcknowledged: Boolean = false,
    val leaseRecoveryHealthyDuration: Duration = Duration.ZERO,
    val pendingDecreasingDuration: Duration = Duration.ZERO,
    val emergencyKeyRevoked: Boolean = false,
    val keyLookupFailures: Int = 0,
    val channel: NotificationChannelType? = null,
    val eventType: NotificationEventType? = null,
    val outcome: NotificationDeliveryAttemptOutcome? = null,
    val providerCategory: NotificationProviderCategory? = null,
) : Serializable {
    init {
        require(!oldestActiveAge.isNegative) { "oldestActiveAge must be non-negative" }
        require(!oldestBreachDuration.isNegative) { "oldestBreachDuration must be non-negative" }
        require(exhaustedInFiveMinutes >= 0) { "exhaustedInFiveMinutes must be non-negative" }
        require(providerAttempts >= 0) { "providerAttempts must be non-negative" }
        require(providerFailures >= 0) { "providerFailures must be non-negative" }
        require(providerFailures <= providerAttempts) { "providerFailures must not exceed providerAttempts" }
        require(!providerFailureBreachDuration.isNegative) { "providerFailureBreachDuration must be non-negative" }
        require(unknownInFiveMinutes >= 0) { "unknownInFiveMinutes must be non-negative" }
        require(leaseRecoveries >= 0) { "leaseRecoveries must be non-negative" }
        require(deliveryAttempts >= 0) { "deliveryAttempts must be non-negative" }
        require(leaseRecoveries <= deliveryAttempts) { "leaseRecoveries must not exceed deliveryAttempts" }
        require(!leaseRecoveryBreachDuration.isNegative) { "leaseRecoveryBreachDuration must be non-negative" }
        require(pendingBacklog >= 0) { "pendingBacklog must be non-negative" }
        require(!pendingIncreasingDuration.isNegative) { "pendingIncreasingDuration must be non-negative" }
        require(!oldestHealthyDuration.isNegative) { "oldestHealthyDuration must be non-negative" }
        require(!exhaustedZeroDuration.isNegative) { "exhaustedZeroDuration must be non-negative" }
        require(!providerHealthyDuration.isNegative) { "providerHealthyDuration must be non-negative" }
        require(!leaseRecoveryHealthyDuration.isNegative) { "leaseRecoveryHealthyDuration must be non-negative" }
        require(!pendingDecreasingDuration.isNegative) { "pendingDecreasingDuration must be non-negative" }
        require(keyLookupFailures >= 0) { "keyLookupFailures must be non-negative" }
    }

    fun lowCardinalityLabels(): Map<String, String> =
        buildMap {
            channel?.let { put("channel", it.metricValue()) }
            eventType?.let { put("event_type", it.metricValue()) }
            outcome?.let { put("outcome", it.metricValue()) }
            providerCategory?.let { put("provider_category", it.metricValue()) }
        }

    fun providerFailureRatio(): Double =
        if (providerAttempts == 0) 0.0 else providerFailures.toDouble() / providerAttempts.toDouble()

    fun leaseRecoveryRatio(): Double =
        if (deliveryAttempts == 0) 0.0 else leaseRecoveries.toDouble() / deliveryAttempts.toDouble()

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** on-call 전달에 사용할 비식별 alert입니다. */
data class NotificationOutboxAlert(
    val signal: NotificationOutboxAlertSignal,
    val severity: NotificationOutboxAlertSeverity,
    val labels: Map<String, String> = emptyMap(),
    val owners: Set<NotificationOnCallOwner> = setOf(NotificationOnCallOwner.NOTIFICATION),
    val enqueueReadinessStatus: Int? = null,
) : Serializable {
    init {
        require(labels.keys.all { it in NotificationOutboxAlertPolicy.ALLOWED_LABEL_KEYS }) {
            "alert labels must be low-cardinality allowlist keys"
        }
        require(labels.all { (key, value) -> value in allowedLabelValues.getValue(key) }) {
            "alert label values must come from a closed low-cardinality set"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        private val allowedLabelValues: Map<String, Set<String>> = mapOf(
            "channel" to NotificationChannelType.entries.mapTo(mutableSetOf()) { it.metricValue() },
            "event_type" to NotificationEventType.entries.mapTo(mutableSetOf()) { it.metricValue() },
            "outcome" to NotificationDeliveryAttemptOutcome.entries.mapTo(mutableSetOf()) { it.metricValue() },
            "provider_category" to NotificationProviderCategory.entries.mapTo(mutableSetOf()) { it.metricValue() },
        )
    }
}

/** 운영자가 구분해야 하는 alert 신호입니다. */
enum class NotificationOutboxAlertSignal {
    OLDEST_ACTIVE_AGE,
    EXHAUSTED,
    PROVIDER_FAILURE_RATIO,
    DELIVERY_RESULT_UNKNOWN,
    LEASE_RECOVERY_RATIO,
    PENDING_BACKLOG,
    EMERGENCY_KEY_REVOKED,
    KEY_LOOKUP_FAILURE,
}

/** alert 전달 강도입니다. */
enum class NotificationOutboxAlertSeverity {
    TICKET,
    WARNING,
    CRITICAL,
}

/** 공동 대응이 필요한 on-call 소유 영역입니다. */
enum class NotificationOnCallOwner {
    NOTIFICATION,
    SECURITY,
}

/** provider 구현 이름과 분리해 공용 alert에 허용하는 닫힌 공급자 범주입니다. */
enum class NotificationProviderCategory {
    DUMMY,
    SMS_GATEWAY,
    EMAIL_GATEWAY,
    PUSH_GATEWAY,
}

private fun NotificationProviderCategory.metricValue(): String = name.lowercase()
