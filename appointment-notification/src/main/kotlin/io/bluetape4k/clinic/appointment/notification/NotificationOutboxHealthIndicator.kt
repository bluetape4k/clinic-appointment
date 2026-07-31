package io.bluetape4k.clinic.appointment.notification

import java.io.Serializable
import java.time.Duration

/**
 * notification outbox readiness/liveness를 개인정보 없는 code와 count로만 노출합니다.
 *
 * readiness는 enqueue/worker를 막아야 하는 schema, claim, key-ring 실패만 DOWN으로 본다.
 * provider/member circuit, backlog age, retention 실패는 liveness를 내리지 않고 degraded detail로
 * 운영자 조치 신호만 남긴다.
 */
class NotificationOutboxHealthIndicator(
    private val readinessSource: NotificationOutboxReadinessSource,
    private val livenessSource: NotificationOutboxLivenessSource,
) {
    fun readiness(): NotificationOutboxHealth {
        val snapshot = readinessSource.snapshot()
        val components = linkedMapOf(
            "schema" to snapshot.schema,
            "claim" to snapshot.claim,
            "keyRing" to snapshot.keyRing,
        )
        val failed = components.values.count { !it.available }
        val details = components.mapValues { (_, state) -> state.code } + ("failedComponents" to failed)
        return NotificationOutboxHealth(
            status = if (failed == 0) NotificationOutboxHealthStatus.UP else NotificationOutboxHealthStatus.DOWN,
            details = details,
        )
    }

    fun liveness(): NotificationOutboxHealth {
        val snapshot = livenessSource.snapshot()
        val degraded = snapshot.providerCircuitOpen > 0 ||
            snapshot.memberCircuitOpen > 0 ||
            snapshot.oldestActiveAge?.let { it > OLDEST_ACTIVE_AGE_WARNING } == true ||
            snapshot.retentionFailures > 0
        return NotificationOutboxHealth(
            status = NotificationOutboxHealthStatus.UP,
            details = mapOf(
                "degraded" to degraded,
                "providerCircuitOpen" to snapshot.providerCircuitOpen,
                "memberCircuitOpen" to snapshot.memberCircuitOpen,
                "oldestActiveAgeSeconds" to (snapshot.oldestActiveAge?.seconds ?: 0L),
                "retentionFailures" to snapshot.retentionFailures,
            ),
        )
    }

    private companion object {
        val OLDEST_ACTIVE_AGE_WARNING: Duration = Duration.ofMinutes(5)
    }
}

/** enqueue 가능 여부에 직접 영향을 주는 상태를 제공합니다. */
fun interface NotificationOutboxReadinessSource {
    fun snapshot(): NotificationOutboxReadinessSnapshot
}

/** process 생존 여부와 분리된 성능 저하 신호를 제공합니다. */
fun interface NotificationOutboxLivenessSource {
    fun snapshot(): NotificationOutboxLivenessSnapshot
}

/** schema, claim, key ring의 readiness 상태입니다. */
data class NotificationOutboxReadinessSnapshot(
    val schema: NotificationComponentState,
    val claim: NotificationComponentState,
    val keyRing: NotificationComponentState,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        fun up(): NotificationOutboxReadinessSnapshot =
            NotificationOutboxReadinessSnapshot(
                schema = NotificationComponentState.up(),
                claim = NotificationComponentState.up(),
                keyRing = NotificationComponentState.up(),
            )
    }
}

/** liveness를 내리지 않고 공개할 degraded 집계입니다. */
data class NotificationOutboxLivenessSnapshot(
    val providerCircuitOpen: Int = 0,
    val memberCircuitOpen: Int = 0,
    val oldestActiveAge: Duration? = null,
    val retentionFailures: Int = 0,
) : Serializable {
    init {
        require(providerCircuitOpen >= 0) { "providerCircuitOpen must be non-negative" }
        require(memberCircuitOpen >= 0) { "memberCircuitOpen must be non-negative" }
        require(oldestActiveAge == null || !oldestActiveAge.isNegative) {
            "oldestActiveAge must be non-negative"
        }
        require(retentionFailures >= 0) { "retentionFailures must be non-negative" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 안정적인 code 하나로 표현한 구성 요소 상태입니다. */
data class NotificationComponentState(
    val available: Boolean,
    val code: String,
) : Serializable {
    init {
        require(code.isNotBlank()) { "code must not be blank" }
        require(code.length <= 64) { "code must not exceed 64 characters" }
        require(code.all { it == '_' || it.isUpperCase() || it.isDigit() }) {
            "code must be a stable uppercase code"
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        fun up(): NotificationComponentState = NotificationComponentState(true, "UP")

        fun down(code: String): NotificationComponentState = NotificationComponentState(false, code)
    }
}

/** readiness 또는 liveness endpoint가 반환할 안전한 상태입니다. */
data class NotificationOutboxHealth(
    val status: NotificationOutboxHealthStatus,
    val details: Map<String, Any>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** health endpoint의 닫힌 상태입니다. */
enum class NotificationOutboxHealthStatus {
    UP,
    DOWN,
}
