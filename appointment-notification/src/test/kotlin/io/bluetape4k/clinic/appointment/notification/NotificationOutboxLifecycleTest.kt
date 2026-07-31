package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.CompleteNotificationCommand
import io.bluetape4k.clinic.appointment.event.notification.NotificationCandidate
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationFairCursor
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationParameterType
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.event.notification.RetryNotificationCommand
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

internal class NotificationOutboxLifecycleTest {

    private val now = Instant.parse("2026-07-31T00:00:00Z")

    @Test
    fun `provider 성공은 fenced complete로 SENT 종결한다`() {
        runBlocking {
            val store = LifecycleFakeWorkStore()
            val worker = NotificationOutboxWorker(
                workStore = store,
                leaseOwner = "worker-a",
                deliveryAction = NotificationDeliveryAction { NotificationDeliveryResult.sent() },
            )

            val result = worker.process(claimed(attemptNumber = 1))

            result shouldBeEqualTo NotificationOutboxWorkerResult.COMPLETED
            store.completed.single().terminalStatus shouldBeEqualTo NotificationOutboxStatus.SENT
            store.retried.size shouldBeEqualTo 0
        }
    }

    @Test
    fun `retry 가능한 실패는 fenced retry로 RETRY_WAIT를 예약한다`() {
        runBlocking {
            val store = LifecycleFakeWorkStore()
            val worker = NotificationOutboxWorker(
                workStore = store,
                leaseOwner = "worker-a",
                deliveryAction = NotificationDeliveryAction {
                    NotificationDeliveryResult.retry(NotificationFailureCode.PROVIDER_UNAVAILABLE)
                },
            )

            val result = worker.process(claimed(attemptNumber = 2))

            result shouldBeEqualTo NotificationOutboxWorkerResult.RETRY_SCHEDULED
            val delay = store.retried.single().retryDelay
            (delay >= Duration.ofSeconds(120)) shouldBeEqualTo true
            (delay <= Duration.ofSeconds(144)) shouldBeEqualTo true
        }
    }

    @Test
    fun `DELIVERY_RESULT_UNKNOWN이 retry budget을 소진하면 EXHAUSTED로 종결한다`() {
        runBlocking {
            val store = LifecycleFakeWorkStore()
            val worker = NotificationOutboxWorker(
                workStore = store,
                leaseOwner = "worker-a",
                deliveryAction = NotificationDeliveryAction {
                    NotificationDeliveryResult.retry(NotificationFailureCode.DELIVERY_RESULT_UNKNOWN)
                },
            )

            val result = worker.process(claimed(attemptNumber = 6))

            result shouldBeEqualTo NotificationOutboxWorkerResult.EXHAUSTED
            store.completed.single().terminalStatus shouldBeEqualTo NotificationOutboxStatus.EXHAUSTED
            store.completed.single().failureCode shouldBeEqualTo NotificationFailureCode.DELIVERY_RESULT_UNKNOWN
        }
    }

    @Test
    fun `DB 시각 기준 최초 attempt 후 24시간이 지나면 EXHAUSTED로 종결한다`() {
        runBlocking {
            val store = LifecycleFakeWorkStore(databaseTime = now)
            val worker = NotificationOutboxWorker(
                workStore = store,
                leaseOwner = "worker-a",
                deliveryAction = NotificationDeliveryAction {
                    NotificationDeliveryResult.retry(NotificationFailureCode.PROVIDER_UNAVAILABLE)
                },
            )

            val result = worker.process(
                claimed(
                    attemptNumber = 2,
                    firstAttemptAt = now.minus(Duration.ofHours(24)).minusSeconds(1),
                ),
            )

            result shouldBeEqualTo NotificationOutboxWorkerResult.EXHAUSTED
            store.completed.single().terminalStatus shouldBeEqualTo NotificationOutboxStatus.EXHAUSTED
        }
    }

    @Test
    fun `provider I O 중 24시간 경계를 넘으면 최신 DB 시각으로 EXHAUSTED 처리한다`() {
        runBlocking {
            val store = LifecycleFakeWorkStore(databaseTime = now.plusSeconds(2))
            val worker = NotificationOutboxWorker(
                workStore = store,
                leaseOwner = "worker-a",
                deliveryAction = NotificationDeliveryAction {
                    NotificationDeliveryResult.retry(NotificationFailureCode.PROVIDER_UNAVAILABLE)
                },
            )

            val result = worker.process(
                claimed(
                    attemptNumber = 2,
                    firstAttemptAt = now.minus(Duration.ofHours(24)).plusSeconds(1),
                ),
            )

            result shouldBeEqualTo NotificationOutboxWorkerResult.EXHAUSTED
            store.retried.size shouldBeEqualTo 0
            store.completed.single().terminalStatus shouldBeEqualTo NotificationOutboxStatus.EXHAUSTED
        }
    }

    @Test
    fun `stale fence 결과는 새 상태를 덮지 않고 LEASE_LOST로 반환한다`() {
        runBlocking {
            val store = LifecycleFakeWorkStore(completeResult = false)
            val worker = NotificationOutboxWorker(
                workStore = store,
                leaseOwner = "worker-a",
                deliveryAction = NotificationDeliveryAction { NotificationDeliveryResult.sent() },
            )

            worker.process(claimed(attemptNumber = 1)) shouldBeEqualTo NotificationOutboxWorkerResult.LEASE_LOST
            store.retried.size shouldBeEqualTo 0
        }
    }

    @Test
    fun `cancellation은 일반 retry로 삼키지 않고 재던진다`() {
        runBlocking {
            val store = LifecycleFakeWorkStore()
            val worker = NotificationOutboxWorker(
                workStore = store,
                leaseOwner = "worker-a",
                deliveryAction = NotificationDeliveryAction { throw CancellationException("cancelled") },
            )

            assertThrows<CancellationException> {
                runBlocking { worker.process(claimed(attemptNumber = 1)) }
            }
            store.completed.size shouldBeEqualTo 0
            store.retried.size shouldBeEqualTo 0
        }
    }

    @Test
    fun `provider 성공 뒤 fenced update 중 cancellation도 일반 실패로 바꾸지 않는다`() {
        runBlocking {
            val store = LifecycleFakeWorkStore(cancelOnComplete = true)
            val worker = NotificationOutboxWorker(
                workStore = store,
                leaseOwner = "worker-a",
                deliveryAction = NotificationDeliveryAction { NotificationDeliveryResult.sent() },
            )

            assertThrows<CancellationException> {
                runBlocking { worker.process(claimed(attemptNumber = 1)) }
            }
            store.retried.size shouldBeEqualTo 0
        }
    }

    private class LifecycleFakeWorkStore(
        private val completeResult: Boolean = true,
        private val cancelOnComplete: Boolean = false,
        private val databaseTime: Instant = Instant.parse("2026-07-31T00:00:00Z"),
    ) : NotificationOutboxWorkStore {
        val completed = mutableListOf<CompleteNotificationCommand>()
        val retried = mutableListOf<RetryNotificationCommand>()

        override suspend fun findFairCandidates(limit: Int, cursor: NotificationFairCursor?): NotificationCandidatePage =
            NotificationCandidatePage(emptyList(), null)

        override suspend fun claim(id: Long, owner: String): ClaimedNotification? = null

        override suspend fun recoverExpired(limit: Int, owner: String): List<ClaimedNotification> = emptyList()

        override suspend fun complete(command: CompleteNotificationCommand): Boolean {
            if (cancelOnComplete) throw CancellationException("cancel during fenced completion")
            completed += command
            return completeResult
        }

        override suspend fun retry(command: RetryNotificationCommand): Boolean {
            retried += command
            return true
        }

        override suspend fun currentDatabaseTime(): Instant = databaseTime

        override suspend fun deleteTerminalBatch(
            status: NotificationOutboxStatus,
            retention: Duration,
            limit: Int,
        ): Int = 0
    }

    private fun claimed(
        attemptNumber: Int,
        firstAttemptAt: Instant = now.minus(Duration.ofMinutes(10)),
    ): ClaimedNotification =
        ClaimedNotification(
            id = 100L,
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(2L),
            appointmentId = AppointmentId(3L),
            memberId = MemberId("member-3"),
            idempotencyKey = NotificationIdempotencyKey("idem-3"),
            owner = "worker-a",
            token = "token-1",
            attemptNumber = attemptNumber,
            leaseUntil = now.plusSeconds(30),
            firstAttemptAt = firstAttemptAt,
            claimedAt = now,
            channel = NotificationChannelType.DUMMY,
            eventType = NotificationEventType.CONFIRMED,
            notificationSlot = NotificationSlot.CONFIRMED,
            providerKey = "dummy",
            templateKey = NotificationTemplateKey("appointment.confirmed"),
            templateVersion = NotificationTemplateVersion(1),
            parameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
            eventId = NotificationEventId("event-3"),
            parametersJson = "{}",
        )
}
