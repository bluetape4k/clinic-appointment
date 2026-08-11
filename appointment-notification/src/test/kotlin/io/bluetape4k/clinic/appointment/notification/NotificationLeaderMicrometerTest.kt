package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.micrometer.InstrumentedLeaderGroupElector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

internal class NotificationLeaderMicrometerTest {

    @Test
    fun `decorator는 acquired duration active를 기록하고 기본 lock tag를 redacted 한다`() {
        val registry = SimpleMeterRegistry()
        val elector = InstrumentedLeaderGroupElector(
            delegate = fakeElector(acquired = true),
            registry = registry,
            lockName = REMINDER_RECOVERY_LOCK_NAME,
        )

        elector.runIfLeader(REMINDER_RECOVERY_LOCK_NAME) { "ok" } shouldBeEqualTo "ok"

        registry.counter("shedlock.leader.acquired", "lock.name", "redacted-lock").count() shouldBeEqualTo 1.0
        registry.find("shedlock.leader.not_acquired").tag("lock.name", "redacted-lock").counter().shouldBeNull()
        registry.find("shedlock.leader.duration").tag("lock.name", "redacted-lock").timer().shouldNotBeNull()
        registry.find("shedlock.leader.active").tag("lock.name", "redacted-lock").gauge()!!.value() shouldBeEqualTo 0.0
        registry.find("shedlock.leader.acquired").tag("lock.name", REMINDER_RECOVERY_LOCK_NAME).counter().shouldBeNull()
    }

    @Test
    fun `leader 미획득은 not acquired metric만 기록하고 action을 실행하지 않는다`() {
        val registry = SimpleMeterRegistry()
        val elector = InstrumentedLeaderGroupElector(
            delegate = fakeElector(acquired = false),
            registry = registry,
            lockName = REMINDER_RECOVERY_LOCK_NAME,
        )

        elector.runIfLeader(REMINDER_RECOVERY_LOCK_NAME) { error("action must not run") }.shouldBeNull()

        registry.counter("shedlock.leader.not_acquired", "lock.name", "redacted-lock").count() shouldBeEqualTo 1.0
        registry.find("shedlock.leader.active").tag("lock.name", "redacted-lock").gauge().shouldBeNull()
    }

    @Test
    fun `action 실패 후 active gauge가 0으로 정리된다`() {
        val registry = SimpleMeterRegistry()
        val elector = InstrumentedLeaderGroupElector(
            delegate = fakeElector(acquired = true),
            registry = registry,
            lockName = REMINDER_RECOVERY_LOCK_NAME,
        )

        assertFailsWith<IllegalStateException> {
            elector.runIfLeader(REMINDER_RECOVERY_LOCK_NAME) {
                throw IllegalStateException("scan failed")
            }
        }

        registry.find("shedlock.leader.active").tag("lock.name", "redacted-lock").gauge()!!.value() shouldBeEqualTo 0.0
    }

    private fun fakeElector(acquired: Boolean): LeaderGroupElector {
        val delegate = mockk<LeaderGroupElector>(relaxed = true)
        every {
            delegate.runIfLeader(any<String>(), any<() -> Any?>())
        } answers {
            if (acquired) secondArg<() -> Any?>().invoke() else null
        }
        return delegate
    }
}
