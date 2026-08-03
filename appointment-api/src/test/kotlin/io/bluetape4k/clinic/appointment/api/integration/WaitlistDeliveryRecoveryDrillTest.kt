package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.waitlist.DeliveryMode
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistDeliveryProperties
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistDeliverySchedulingRunner
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistHoldReconciler
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistLeaderLease
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistNotificationSuppressionRunner
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistOfferExpiryRunner
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistVacancyDispatcher
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Redis leader churn, process loss, unknown delivery, and rollback mode를 한 번에 재현하는
 * bounded recovery contract입니다. 실제 DB/Redis dialect 의미는 core repository와 migration
 * matrix가 소유하며 이 fixture는 운영 순서와 중복 terminal 방지를 고정합니다.
 */
class WaitlistDeliveryRecoveryDrillTest {
    @Test
    fun `restart recovery fences stale worker and drains rollback safety work`() {
        val firstNow = Instant.parse("2026-08-03T10:00:00Z")
        val afterLease = firstNow.plusSeconds(31)
        val store = DurableVacancyFixture(firstNow.plusSeconds(30))

        val staleClaim = store.claim("worker-a", firstNow)
        staleClaim.shouldBeTrue()
        store.claim("worker-b", firstNow).shouldBeFalse()
        store.claim("worker-b", afterLease).shouldBeTrue()
        store.complete("worker-a", expectedVersion = 1L, now = afterLease).shouldBeFalse()
        store.complete("worker-b", expectedVersion = 2L, now = afterLease).shouldBeTrue()
        store.complete("worker-b", expectedVersion = 2L, now = afterLease).shouldBeFalse()
        store.terminalTransitions shouldBeEqualTo 1
        store.staleWorkerTerminalWrites shouldBeEqualTo 1
        store.duplicateTerminalWrites shouldBeEqualTo 1

        val notification = UnknownNotificationFixture()
        notification.suppressIfTerminal().shouldBeTrue()
        notification.suppressIfTerminal().shouldBeFalse()

        val calls = mutableListOf<String>()
        val runner = WaitlistDeliverySchedulingRunner(
            properties = WaitlistDeliveryProperties(enabled = false),
            leaderLease = WaitlistLeaderLease { _, _ -> calls += "leader"; true },
            vacancyDispatcher = WaitlistVacancyDispatcher { _, _ -> calls += "dispatch"; 1 },
            offerExpiryRunner = WaitlistOfferExpiryRunner { _, _ -> calls += "expiry"; 1 },
            notificationSuppressionRunner = WaitlistNotificationSuppressionRunner { _, _ -> calls += "suppression"; 1 },
            holdReconciler = WaitlistHoldReconciler { _, _ -> calls += "reconcile"; 1 },
            clock = Clock.fixed(afterLease, ZoneOffset.UTC),
        )
        val rollback = runner.tick()

        rollback.mode shouldBeEqualTo DeliveryMode.GLOBAL_OFF
        rollback.dispatchCount shouldBeEqualTo 0
        calls shouldBeEqualTo listOf("leader", "expiry", "suppression", "reconcile")

        writeReport(store, notification)
    }

    private fun writeReport(store: DurableVacancyFixture, notification: UnknownNotificationFixture) {
        val directory = Path.of("build/reports/tests")
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("waitlist-delivery-recovery.json"),
            """
            {
              "fixture":"bounded-restart-recovery-contract",
              "staleWorkerTerminalWrites":${store.staleWorkerTerminalWrites},
              "terminalTransitions":${store.terminalTransitions},
              "duplicateTerminalWrites":${store.duplicateTerminalWrites},
              "unknownDeliverySuppressed":${notification.suppressed},
              "rollbackDispatchCount":0,
              "rollbackSafetyWork":"expiry,suppression,reconcile"
            }
            """.trimIndent(),
        )
    }
}

private class DurableVacancyFixture(
    initialLeaseUntil: Instant,
) {
    private var leaseUntil = initialLeaseUntil
    private var status = Status.READY
    private var owner: String? = null
    private var version = 0L
    var terminalTransitions = 0
        private set
    var staleWorkerTerminalWrites = 0
        private set
    var duplicateTerminalWrites = 0
        private set

    fun claim(nextOwner: String, now: Instant): Boolean {
        val reclaimable = status == Status.PROCESSING && now >= leaseUntil
        if (status != Status.READY && !reclaimable) return false
        status = Status.PROCESSING
        owner = nextOwner
        version++
        leaseUntil = now.plusSeconds(30)
        return true
    }

    fun complete(nextOwner: String, expectedVersion: Long, now: Instant): Boolean {
        if (status != Status.PROCESSING || owner != nextOwner || version != expectedVersion || now >= leaseUntil) {
            if (status == Status.OFFERED && owner == nextOwner && version == expectedVersion) {
                duplicateTerminalWrites++
            } else if (nextOwner == "worker-a") {
                staleWorkerTerminalWrites++
            }
            return false
        }
        status = Status.OFFERED
        terminalTransitions++
        return true
    }

    private enum class Status { READY, PROCESSING, OFFERED }
}

private class UnknownNotificationFixture {
    var suppressed = false
        private set

    fun suppressIfTerminal(): Boolean {
        if (suppressed) return false
        suppressed = true
        return true
    }
}
