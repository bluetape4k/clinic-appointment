package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderLease
import kotlinx.coroutines.CancellationException
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** reminder leader의 운영 상태를 Actuator에 전달하는 읽기 전용 port입니다. */
fun interface NotificationLeaderHealthSource {
    fun snapshot(): NotificationLeaderHealthSnapshot
}

/** reminder leader health의 닫힌 상태입니다. */
enum class NotificationLeaderHealthStatus {
    UP,
    DEGRADED,
    DOWN,
}

/** 식별자 없이 reminder leader의 lease와 최근 획득 결과를 요약합니다. */
data class NotificationLeaderHealthSnapshot(
    val status: NotificationLeaderHealthStatus,
    val backendAvailable: Boolean,
    val leaderPresent: Boolean,
    val leaseAtRisk: Boolean,
    val lastAcquiredAt: Instant? = null,
    val lastAcquisitionFailureAt: Instant? = null,
    val recentAcquisitionFailures: Int = 0,
    val failureWindowSeconds: Long,
    val leaseRiskWindowSeconds: Long,
    val leaseUntil: Instant? = null,
) : Serializable {
    init {
        require(recentAcquisitionFailures >= 0) {
            "recentAcquisitionFailures must be non-negative"
        }
        require(failureWindowSeconds > 0) { "failureWindowSeconds must be positive" }
        require(leaseRiskWindowSeconds > 0) { "leaseRiskWindowSeconds must be positive" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * reminder lock의 획득 결과와 Redis lease 상태를 개인정보 없는 상태 요약으로 합칩니다.
 *
 * 이 monitor는 scheduler 실행을 허용하거나 차단하지 않습니다. runner가 실제로 관측한
 * 획득 성공·backend 실패만 기록하고, health 조회 시 현재 group lease를 읽습니다.
 */
class NotificationLeaderHealthMonitor(
    private val elector: LeaderGroupElector,
    private val lockName: String = REMINDER_RECOVERY_LOCK_NAME,
    private val clock: Clock = Clock.systemUTC(),
    private val failureWindow: Duration = Duration.ofMinutes(5),
    private val leaseRiskWindow: Duration = Duration.ofSeconds(30),
) : NotificationLeaderHealthSource {
    init {
        require(lockName.isNotBlank()) { "lockName must not be blank" }
        require(failureWindow.isPositive) { "failureWindow must be positive" }
        require(leaseRiskWindow.isPositive) { "leaseRiskWindow must be positive" }
    }

    private val recorded = AtomicReference(RecordedState())

    /** 현재 process가 leader action을 획득한 시각을 기록합니다. */
    fun recordAcquired() {
        val now = clock.instant()
        recorded.updateAndGet { it.copy(lastAcquiredAt = now, failures = emptyList()) }
    }

    /** leader backend가 action을 시작하기 전에 실패한 시각을 bounded window에 기록합니다. */
    fun recordAcquisitionFailure() {
        val now = clock.instant()
        recorded.updateAndGet { current ->
            val failures = current.prune(now).failures
                .plus(now)
                .takeLast(MAX_FAILURE_EVENTS)
            current.copy(
                lastAcquisitionFailureAt = now,
                failures = failures,
            )
        }
    }

    override fun snapshot(): NotificationLeaderHealthSnapshot {
        val now = clock.instant()
        val current = recorded.updateAndGet { it.prune(now) }
        val leaderState = try {
            elector.state(lockName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return current.toSnapshot(
                status = NotificationLeaderHealthStatus.DOWN,
                backendAvailable = false,
                leaderPresent = false,
                leaseAtRisk = false,
                leaseUntil = null,
            )
        }

        val leaseUntil = leaderState.leaders.fold<LeaderLease, Instant?>(null) { earliest, lease ->
            lease.leaseUntil?.let { candidate ->
                earliest?.let {
                    if (candidate.isBefore(it)) candidate else it
                } ?: candidate
            } ?: earliest
        }
        val leaderPresent = leaderState.leaders.isNotEmpty()
        val leaseAtRisk = leaseUntil?.let {
            !it.isAfter(now.plus(leaseRiskWindow))
        } == true
        val status = when {
            current.failures.isNotEmpty() || !leaderPresent || leaseAtRisk ->
                NotificationLeaderHealthStatus.DEGRADED
            else -> NotificationLeaderHealthStatus.UP
        }
        return current.toSnapshot(
            status = status,
            backendAvailable = true,
            leaderPresent = leaderPresent,
            leaseAtRisk = leaseAtRisk,
            leaseUntil = leaseUntil,
        )
    }

    private fun RecordedState.toSnapshot(
        status: NotificationLeaderHealthStatus,
        backendAvailable: Boolean,
        leaderPresent: Boolean,
        leaseAtRisk: Boolean,
        leaseUntil: Instant?,
    ): NotificationLeaderHealthSnapshot =
        NotificationLeaderHealthSnapshot(
            status = status,
            backendAvailable = backendAvailable,
            leaderPresent = leaderPresent,
            leaseAtRisk = leaseAtRisk,
            lastAcquiredAt = lastAcquiredAt,
            lastAcquisitionFailureAt = lastAcquisitionFailureAt,
            recentAcquisitionFailures = failures.size,
            failureWindowSeconds = failureWindow.seconds.coerceAtLeast(1L),
            leaseRiskWindowSeconds = leaseRiskWindow.seconds.coerceAtLeast(1L),
            leaseUntil = leaseUntil,
        )

    private fun RecordedState.prune(now: Instant): RecordedState {
        val cutoff = now.minus(failureWindow)
        return copy(failures = failures.filter { it.isAfter(cutoff) })
    }

    private data class RecordedState(
        val lastAcquiredAt: Instant? = null,
        val lastAcquisitionFailureAt: Instant? = null,
        val failures: List<Instant> = emptyList(),
    )

    private companion object {
        const val MAX_FAILURE_EVENTS = 128
    }
}
