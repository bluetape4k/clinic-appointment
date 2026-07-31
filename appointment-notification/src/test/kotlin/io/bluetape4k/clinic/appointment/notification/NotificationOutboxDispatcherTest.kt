package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.NotificationCandidate
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationClinicKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFairCursor
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationParameterType
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

internal class NotificationOutboxDispatcherTest {

    @Test
    fun `큰 clinic backlog가 있어도 작은 clinic 후보가 첫 두 page 안에 claim된다`() {
        runBlocking {
            val store = FairFakeWorkStore(
                candidates = buildList {
                    repeat(10_000) { add(candidate(id = it + 1L, clinicId = 1L)) }
                    repeat(10) { add(candidate(id = 20_000L + it, clinicId = 2L)) }
                },
            )
            val claimedClinicIds = mutableListOf<Long>()
            val dispatcher = NotificationOutboxDispatcher(
                store = store,
                worker = NotificationOutboxJobWorker {
                    claimedClinicIds += it.clinicId.value
                    NotificationOutboxWorkerResult.COMPLETED
                },
                leaseOwner = "dispatcher-test",
                globalConcurrency = 2,
                perClinicConcurrency = 1,
            )

            dispatcher.dispatchOnce()
            dispatcher.dispatchOnce()

            claimedClinicIds.contains(2L) shouldBeEqualTo true
        }
    }

    @Test
    fun `dispatcher는 전역과 clinic별 동시 처리 상한을 넘지 않는다`() {
        runBlocking {
            val store = FairFakeWorkStore(
                candidates = (1L..12L).map { candidate(id = it, clinicId = ((it - 1L) % 4L) + 1L) },
            )
            val globalActive = AtomicInteger()
            val globalMax = AtomicInteger()
            val clinicActive = ConcurrentHashMap<Long, AtomicInteger>()
            val clinicMax = ConcurrentHashMap<Long, AtomicInteger>()
            val dispatcher = NotificationOutboxDispatcher(
                store = store,
                worker = NotificationOutboxJobWorker { claimed ->
                    val global = globalActive.incrementAndGet()
                    globalMax.accumulateAndGet(global, ::maxOf)
                    val active = clinicActive.computeIfAbsent(claimed.clinicId.value) { AtomicInteger() }
                    val max = clinicMax.computeIfAbsent(claimed.clinicId.value) { AtomicInteger() }
                    max.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    delay(10)
                    active.decrementAndGet()
                    globalActive.decrementAndGet()
                    NotificationOutboxWorkerResult.COMPLETED
                },
                leaseOwner = "dispatcher-test",
                globalConcurrency = 4,
                perClinicConcurrency = 1,
            )

            dispatcher.dispatchOnce()

            globalMax.get() shouldBeEqualTo 4
            clinicMax.values.map { it.get() }.max() shouldBeEqualTo 1
        }
    }

    @Test
    fun `dispatcher는 만료 lease 복구를 bounded drive하고 신규 후보 용량을 남긴다`() {
        runBlocking {
            val recovered = listOf(
                claimed(id = 900L, clinicId = 9L),
                claimed(id = 901L, clinicId = 9L),
                claimed(id = 902L, clinicId = 9L),
            )
            val store = FairFakeWorkStore(
                candidates = listOf(candidate(id = 1L, clinicId = 1L), candidate(id = 2L, clinicId = 2L)),
                expired = recovered,
            )
            val processedIds = ConcurrentHashMap.newKeySet<Long>()
            val dispatcher = NotificationOutboxDispatcher(
                store = store,
                worker = NotificationOutboxJobWorker {
                    processedIds += it.id
                    NotificationOutboxWorkerResult.COMPLETED
                },
                leaseOwner = "dispatcher-test",
                globalConcurrency = 3,
                perClinicConcurrency = 1,
            )

            dispatcher.dispatchOnce()

            store.operations shouldBeEqualTo listOf("recover:2", "find:1")
            store.claimedIds shouldBeEqualTo listOf(1L)
            processedIds.toSet() shouldBeEqualTo setOf(900L, 901L, 1L)
        }
    }

    @Test
    fun `globalConcurrency 1에서는 만료 lease와 신규 후보를 tick마다 교대한다`() {
        runBlocking {
            val store = FairFakeWorkStore(
                candidates = listOf(candidate(id = 1L, clinicId = 1L)),
                expired = listOf(claimed(id = 900L, clinicId = 9L)),
            )
            val processedIds = mutableListOf<Long>()
            val dispatcher = NotificationOutboxDispatcher(
                store = store,
                worker = NotificationOutboxJobWorker {
                    processedIds += it.id
                    NotificationOutboxWorkerResult.COMPLETED
                },
                leaseOwner = "dispatcher-test",
                globalConcurrency = 1,
                perClinicConcurrency = 1,
            )

            dispatcher.dispatchOnce()
            dispatcher.dispatchOnce()

            store.operations shouldBeEqualTo listOf("recover:1", "find:1")
            store.claimedIds shouldBeEqualTo listOf(1L)
            processedIds shouldBeEqualTo listOf(900L, 1L)
        }
    }

    private inner class FairFakeWorkStore(
        candidates: List<NotificationCandidate>,
        expired: List<ClaimedNotification> = emptyList(),
    ) : NotificationOutboxWorkStore {
        private val byId = candidates.associateBy(NotificationCandidate::id)
        private val remaining = candidates.toMutableList()
        private val expired = expired.toMutableList()
        val operations = mutableListOf<String>()
        val claimedIds = mutableListOf<Long>()

        override suspend fun findFairCandidates(
            limit: Int,
            cursor: NotificationFairCursor?,
        ): NotificationCandidatePage {
            operations += "find:$limit"
            val grouped = remaining.groupBy { NotificationClinicKey(it.tenantGroupId, it.clinicId) }
            val keys = grouped.keys.sortedWith(compareBy({ it.tenantGroupId.value }, { it.clinicId.value }))
            val orderedKeys = cursor?.let { fairCursor ->
                keys.filter { it.tenantGroupId.value > fairCursor.tenantGroupId.value || it.clinicId.value > fairCursor.clinicId.value } +
                    keys.filter { it.tenantGroupId.value <= fairCursor.tenantGroupId.value && it.clinicId.value <= fairCursor.clinicId.value }
            } ?: keys
            val selected = orderedKeys.mapNotNull { grouped[it]?.firstOrNull() }.take(limit)
            remaining.removeAll(selected.toSet())
            return NotificationCandidatePage(
                candidates = selected,
                nextCursor = selected.lastOrNull()?.let { NotificationFairCursor(it.tenantGroupId, it.clinicId) },
            )
        }

        override suspend fun claim(id: Long, owner: String): ClaimedNotification? {
            claimedIds += id
            return byId[id]?.let { claimed(it.id, it.clinicId.value) }
        }

        override suspend fun recoverExpired(limit: Int, owner: String): List<ClaimedNotification> {
            operations += "recover:$limit"
            val selected = expired.take(limit)
            expired.removeAll(selected.toSet())
            return selected
        }

        override suspend fun complete(command: io.bluetape4k.clinic.appointment.event.notification.CompleteNotificationCommand): Boolean = true

        override suspend fun retry(command: io.bluetape4k.clinic.appointment.event.notification.RetryNotificationCommand): Boolean = true

        override suspend fun currentDatabaseTime(): java.time.Instant = java.time.Instant.parse("2026-07-31T00:00:00Z")

        override suspend fun deleteTerminalBatch(
            status: io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxStatus,
            retention: Duration,
            limit: Int,
        ): Int = 0
    }

    private fun candidate(id: Long, clinicId: Long): NotificationCandidate =
        NotificationCandidate(
            id = id,
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(clinicId),
            availableAt = Instant.parse("2026-07-31T00:00:00Z"),
        )

    private fun claimed(id: Long, clinicId: Long): ClaimedNotification =
        ClaimedNotification(
            id = id,
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(clinicId),
            appointmentId = AppointmentId(id),
            memberId = MemberId("member-$id"),
            idempotencyKey = NotificationIdempotencyKey("idem-$id"),
            owner = "dispatcher-test",
            token = "token-$id",
            attemptNumber = 1,
            leaseUntil = Instant.parse("2026-07-31T00:01:00Z"),
            firstAttemptAt = Instant.parse("2026-07-31T00:00:00Z"),
            claimedAt = Instant.parse("2026-07-31T00:00:00Z"),
            channel = NotificationChannelType.DUMMY,
            eventType = NotificationEventType.CONFIRMED,
            notificationSlot = NotificationSlot.CONFIRMED,
            providerKey = "dummy",
            templateKey = NotificationTemplateKey("appointment.confirmed"),
            templateVersion = NotificationTemplateVersion(1),
            parameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
            eventId = NotificationEventId("event-$id"),
            parametersJson = "{}",
        )
}
