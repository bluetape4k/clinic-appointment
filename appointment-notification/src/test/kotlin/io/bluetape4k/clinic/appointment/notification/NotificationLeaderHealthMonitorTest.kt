package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LeaderLease
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

internal class NotificationLeaderHealthMonitorTest {

    @Test
    fun `정상 leader lease는 low cardinality UP snapshot을 반환한다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val clock = MutableTestClock(now)
        val elector = mockk<LeaderElector>()
        every { elector.state(REMINDER_RECOVERY_LOCK_NAME) } returns leaderState(now.plusSeconds(300))
        val monitor = NotificationLeaderHealthMonitor(elector, clock = clock)

        monitor.recordAcquired()

        monitor.snapshot() shouldBeEqualTo NotificationLeaderHealthSnapshot(
            status = NotificationLeaderHealthStatus.UP,
            backendAvailable = true,
            leaderPresent = true,
            leaseAtRisk = false,
            lastAcquiredAt = now,
            lastAcquisitionFailureAt = null,
            recentAcquisitionFailures = 0,
            failureWindowSeconds = 300,
            leaseRiskWindowSeconds = 30,
            leaseUntil = now.plusSeconds(300),
        )
    }

    @Test
    fun `최근 획득 실패는 bounded window 동안 DEGRADED이고 시간이 지나면 자동 복구된다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val clock = MutableTestClock(now)
        val elector = mockk<LeaderElector>()
        every { elector.state(REMINDER_RECOVERY_LOCK_NAME) } returns leaderState(now.plusSeconds(3_600))
        val monitor = NotificationLeaderHealthMonitor(elector, clock = clock)

        monitor.recordAcquisitionFailure()
        monitor.snapshot().status shouldBeEqualTo NotificationLeaderHealthStatus.DEGRADED
        monitor.snapshot().recentAcquisitionFailures shouldBeEqualTo 1

        clock.advance(Duration.ofMinutes(6))

        monitor.snapshot().status shouldBeEqualTo NotificationLeaderHealthStatus.UP
        monitor.snapshot().recentAcquisitionFailures shouldBeEqualTo 0
    }

    @Test
    fun `lease 만료 임박은 backend가 살아 있어도 DEGRADED로 표시된다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val elector = mockk<LeaderElector>()
        every { elector.state(REMINDER_RECOVERY_LOCK_NAME) } returns leaderState(now.plusSeconds(10))
        val monitor = NotificationLeaderHealthMonitor(
            elector = elector,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        monitor.snapshot().let { snapshot ->
            snapshot.status shouldBeEqualTo NotificationLeaderHealthStatus.DEGRADED
            snapshot.leaderPresent shouldBeEqualTo true
            snapshot.leaseAtRisk shouldBeEqualTo true
        }
    }

    @Test
    fun `leader backend 예외는 DOWN이며 실행 경계를 대신 결정하지 않는다`() {
        val now = Instant.parse("2026-08-22T00:00:00Z")
        val elector = mockk<LeaderElector>()
        every { elector.state(REMINDER_RECOVERY_LOCK_NAME) } throws IllegalStateException("redis unavailable")
        val monitor = NotificationLeaderHealthMonitor(
            elector = elector,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val snapshot = monitor.snapshot()

        snapshot.status shouldBeEqualTo NotificationLeaderHealthStatus.DOWN
        snapshot.backendAvailable shouldBeEqualTo false
        snapshot.leaderPresent shouldBeEqualTo false
        snapshot.leaseAtRisk shouldBeEqualTo false
    }

    private fun leaderState(leaseUntil: Instant): LeaderState =
        LeaderState.occupied(
            lockName = REMINDER_RECOVERY_LOCK_NAME,
            leader = LeaderLease(
                auditLeaderId = "redacted",
                electedAt = leaseUntil.minusSeconds(60),
                leaseUntil = leaseUntil,
                nodeId = "redacted",
            ),
        )

    private class MutableTestClock(initial: Instant) : Clock() {
        private var current: Instant = initial

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
