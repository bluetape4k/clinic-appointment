package io.bluetape4k.clinic.appointment.notification.persistence

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.notification.JdbcNotificationOutboxObservationStore
import io.bluetape4k.clinic.appointment.notification.JdbcNotificationOutboxWorkStore
import io.bluetape4k.codec.Base58
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database

class NotificationPersistenceCapabilityContractTest {

    @Test
    fun `JDBC wrapper public constructor는 concrete repository를 노출하지 않는다`() {
        JdbcNotificationOutboxWorkStore::class.constructors
            .flatMap { it.parameters }
            .none { it.type.classifier == JdbcNotificationOutboxRepository::class }
            .shouldBeTrue()
        JdbcNotificationOutboxObservationStore::class.constructors
            .flatMap { it.parameters }
            .none { it.type.classifier == JdbcNotificationOutboxRepository::class }
            .shouldBeTrue()
    }

    @Test
    fun `repository는 work와 observation persistence capability를 구현한다`() {
        NotificationOutboxWorkPersistence::class.java
            .isAssignableFrom(JdbcNotificationOutboxRepository::class.java)
            .shouldBeTrue()
        NotificationOutboxObservationPersistence::class.java
            .isAssignableFrom(JdbcNotificationOutboxRepository::class.java)
            .shouldBeTrue()
    }

    @Test
    fun `waitlist adapter public constructor는 concrete repository를 노출하지 않는다`() {
        WaitlistNotificationOutboxAdapter::class.constructors
            .flatMap { it.parameters }
            .none { it.type.classifier == WaitlistNotificationOutboxRepository::class }
            .shouldBeTrue()
    }

    @Test
    fun `consumer fixture는 concrete notification repository를 import하지 않는다`() {
        val fixtureRoot = listOf(
            Path.of("src/consumerFixture/notification"),
            Path.of("../src/consumerFixture/notification"),
        ).firstOrNull(Files::exists) ?: error("Notification consumer fixture source root not found")
        val sourceContainsConcreteRepository = Files.walk(fixtureRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .anyMatch { Files.readString(it).contains("JdbcNotificationOutboxRepository") }
        }

        sourceContainsConcreteRepository.shouldBeFalse()
    }

    @Test
    fun `wrapper는 constructor capability를 실제 delegate로 사용한다`() = runBlocking {
        val database = Database.connect(
            "jdbc:h2:mem:notification_capability_${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        val workPersistence = mockk<NotificationOutboxWorkPersistence>()
        every {
            workPersistence.findReadyClinicKeys(
                cursor = null,
                limit = 1,
                eligibleScopes = null,
            )
        } returns emptyList()

        JdbcNotificationOutboxWorkStore(database, workPersistence)
            .findFairCandidates(limit = 1, cursor = null)
            .candidates
            .shouldBeEqualTo(emptyList())
        verify(exactly = 1) {
            workPersistence.findReadyClinicKeys(null, 1, null)
        }

        val observationPersistence = mockk<NotificationOutboxObservationPersistence>()
        every { observationPersistence.observeReady(10_001) } returns NotificationOutboxObservation(
            readyCount = 0,
            oldestReadyAt = null,
            observedAt = Instant.parse("2026-08-26T00:00:00Z"),
            capped = false,
        )
        JdbcNotificationOutboxObservationStore(database, observationPersistence)
            .loadBoundedSnapshot()
            .pendingReady
            .shouldBeEqualTo(0L)
        verify(exactly = 1) { observationPersistence.observeReady(10_001) }
    }
}
