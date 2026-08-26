package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppointmentOutboxStoreTest {
    private val now = Instant.parse("2026-08-05T08:30:00Z")

    @BeforeAll
    fun connectPostgreSQL() {
        previousDefaultDatabase = TransactionManager.defaultDatabase
        TransactionManager.defaultDatabase = POSTGRESQL_DATABASE
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                ProductCatalogProjections,
                AppointmentPlans,
                SchedulingOutboxEvents,
            )
        }
    }

    @AfterAll
    fun restoreH2DefaultDatabase() {
        TransactionManager.defaultDatabase = previousDefaultDatabase
    }

    @BeforeEach
    fun setup() {
        TransactionManager.defaultDatabase = POSTGRESQL_DATABASE
        transaction {
            SchedulingOutboxEvents.deleteAll()
            AppointmentPlans.deleteAll()
            ProductCatalogProjections.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
                it[active] = true
            }
            Clinics.insert {
                it[id] = EntityID(31L, Clinics)
                it[tenantGroupId] = 1L
                it[name] = "Clinic One"
            }
        }
    }

    private companion object {
        private var previousDefaultDatabase: Database? = null
        private val POSTGRESQL = PostgreSQLServer.Launcher.postgres
        private val POSTGRESQL_DATABASE = Database.connect(
            POSTGRESQL.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = POSTGRESQL.username ?: PostgreSQLServer.USERNAME,
            password = POSTGRESQL.password ?: PostgreSQLServer.PASSWORD,
        )
    }

    @Test
    fun `claim is lease fenced and published transition is owner specific`() {
        insertOutbox()
        val store = JdbcAppointmentOutboxStore()

        val first = store.claim("relay-a", 32, Duration.ofSeconds(30)).single()
        store.claim("relay-b", 32, Duration.ofSeconds(30)) shouldBeEqualTo emptyList()
        store.markPublished(first).shouldBeTrue()
        store.markPublished(first).shouldBeFalse()

        transaction {
            SchedulingOutboxEvents.selectAll().single()[SchedulingOutboxEvents.status] shouldBeEqualTo SchedulingOutboxStatus.PUBLISHED
            SchedulingOutboxEvents.selectAll().single()[SchedulingOutboxEvents.leaseOwner].shouldBeNull()
        }
    }

    @Test
    fun `expired lease is reclaimable by another relay`() {
        insertOutbox(
            outboxEventId = "event-store-expired-lease",
            leaseOwner = "relay-stale",
            leaseToken = "stale-token",
            leaseUntil = now.minusSeconds(1),
        )
        val store = JdbcAppointmentOutboxStore()

        val reclaimed = store.claim("relay-recovery", 1, Duration.ofSeconds(30)).single()

        reclaimed.owner shouldBeEqualTo "relay-recovery"
        (reclaimed.token != "stale-token").shouldBeTrue()
    }

    @Test
    fun `retry increments attempt and persists stable failure code`() {
        insertOutbox()
        val store = JdbcAppointmentOutboxStore(maxAttempts = 2)
        val claim = store.claim("relay-a", 32, Duration.ofSeconds(30)).single()

        store.markRetry(claim, Duration.ofSeconds(5), AppointmentOutboxRelay.FAILURE_BROKER_UNAVAILABLE).shouldBeTrue()
        val retry = store.claim("relay-b", 32, Duration.ofSeconds(30))
        retry shouldBeEqualTo emptyList()

        transaction {
            val row = SchedulingOutboxEvents.selectAll().single()
            row[SchedulingOutboxEvents.status] shouldBeEqualTo SchedulingOutboxStatus.PENDING
            row[SchedulingOutboxEvents.attemptCount] shouldBeEqualTo 1
            row[SchedulingOutboxEvents.lastFailureCode] shouldBeEqualTo AppointmentOutboxRelay.FAILURE_BROKER_UNAVAILABLE
        }
    }

    @Test
    fun `same aggregate publishes only its oldest pending row`() {
        insertOutbox(outboxEventId = "event-store-oldest", appointmentAggregateId = 924, outboxOccurredAt = now)
        insertOutbox(outboxEventId = "event-store-newer", appointmentAggregateId = 924, outboxOccurredAt = now)
        val store = JdbcAppointmentOutboxStore()

        val oldest = store.claim("relay-a", 32, Duration.ofSeconds(30)).single()
        oldest.eventId shouldBeEqualTo AppointmentEventId("event-store-oldest")
        store.claim("relay-a", 32, Duration.ofSeconds(30)) shouldBeEqualTo emptyList()

        store.markPublished(oldest).shouldBeTrue()
        val newer = store.claim("relay-a", 32, Duration.ofSeconds(30)).single()
        newer.eventId shouldBeEqualTo AppointmentEventId("event-store-newer")
    }

    @Test
    fun `concurrent relays claim each eligible row at most once`() {
        repeat(4) { index ->
            insertOutbox(
                outboxEventId = "event-store-concurrent-$index",
                appointmentAggregateId = 924L + index,
            )
        }
        val store = JdbcAppointmentOutboxStore(maxClinicBatch = 4)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (0 until 2).map { relayIndex ->
                executor.submit<List<AppointmentOutboxClaim>> {
                    store.claim("relay-concurrent-$relayIndex", 2, Duration.ofSeconds(30))
                }
            }
            val claims = futures.flatMap { it.get(10, TimeUnit.SECONDS) }
            val ids = claims.map(AppointmentOutboxClaim::id)

            ids.toSet().size shouldBeEqualTo ids.size
            ids.size shouldBeEqualTo 4
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `clinic quota bounds a single claim batch`() {
        repeat(3) { index ->
            insertOutbox(
                outboxEventId = "event-store-clinic-quota-$index",
                appointmentAggregateId = 1_000L + index,
            )
        }
        val store = JdbcAppointmentOutboxStore(maxClinicBatch = 1)

        store.claim("relay-a", 32, Duration.ofSeconds(30)).size shouldBeEqualTo 1
    }

    @Test
    fun `invalid predecessor is terminalized before a newer aggregate row can claim`() {
        insertOutbox(
            outboxEventId = "event-store-invalid-predecessor",
            appointmentAggregateId = 924,
            eventTopic = null,
            outboxOccurredAt = now.minusSeconds(1),
        )
        insertOutbox(
            outboxEventId = "event-store-valid-successor",
            appointmentAggregateId = 924,
            outboxOccurredAt = now,
        )
        val store = JdbcAppointmentOutboxStore()

        store.claim("relay-a", 32, Duration.ofSeconds(32)) shouldBeEqualTo emptyList()
        transaction {
            SchedulingOutboxEvents.selectAll()
                .sortedBy { it[SchedulingOutboxEvents.eventId] }
                .first()[SchedulingOutboxEvents.status] shouldBeEqualTo SchedulingOutboxStatus.FAILED
        }

        val successor = store.claim("relay-a", 32, Duration.ofSeconds(32)).single()
        successor.eventId shouldBeEqualTo AppointmentEventId("event-store-valid-successor")
    }

    @Test
    fun `disallowed topic is terminally rejected before claim`() {
        insertOutbox(eventTopic = AppointmentTopic("clinic.appointment.unapproved"))
        val store = JdbcAppointmentOutboxStore()

        store.claim("relay-a", 32, Duration.ofSeconds(30)) shouldBeEqualTo emptyList()
        transaction {
            val row = SchedulingOutboxEvents.selectAll().single()
            row[SchedulingOutboxEvents.status] shouldBeEqualTo SchedulingOutboxStatus.FAILED
            row[SchedulingOutboxEvents.lastFailureCode] shouldBeEqualTo AppointmentOutboxRelay.FAILURE_DISALLOWED_TOPIC
        }
    }

    @Test
    fun `attempt exhaustion is terminally recorded`() {
        insertOutbox(attempts = 1)
        val store = JdbcAppointmentOutboxStore(maxAttempts = 1)

        store.claim("relay-a", 32, Duration.ofSeconds(30)) shouldBeEqualTo emptyList()
        transaction {
            val row = SchedulingOutboxEvents.selectAll().single()
            row[SchedulingOutboxEvents.status] shouldBeEqualTo SchedulingOutboxStatus.FAILED
            row[SchedulingOutboxEvents.lastFailureCode] shouldBeEqualTo AppointmentOutboxRelay.FAILURE_ATTEMPT_EXHAUSTED
        }
    }

    private fun insertOutbox(
        outboxEventId: String = "event-store-1",
        appointmentAggregateId: Long = 924,
        outboxOccurredAt: Instant = now,
        eventTopic: AppointmentTopic? = AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC),
        attempts: Int = 0,
        leaseOwner: String? = null,
        leaseToken: String? = null,
        leaseUntil: Instant? = null,
    ) {
        val envelope = AppointmentEventEnvelope(
            eventId = AppointmentEventId(outboxEventId),
            eventType = AppointmentEventType.CREATED,
            schemaVersion = 1,
            occurredAt = outboxOccurredAt,
            tenantGroupId = 1,
            clinicId = 31,
            aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
            aggregateId = AppointmentAggregateId(appointmentAggregateId),
            correlationId = AppointmentCommandContext.root("store-1").correlationId,
            causationId = AppointmentCommandContext.root("store-1").causationId,
            payload = AppointmentCreatedPayload(AppointmentAggregateId(appointmentAggregateId), 1, AppointmentState.CONFIRMED),
        )
        val key = AppointmentPartitionKeyFactory.create(1, 31, appointmentAggregateId)
        val json = AppointmentEventEnvelopeCodec().encode(envelope)
        transaction {
            SchedulingOutboxEvents.insert {
                it[eventId] = envelope.eventId.value
                it[causationEventId] = envelope.causationId.value
                it[correlationId] = envelope.correlationId.value
                it[eventType] = envelope.eventType.wireName
                it[tenantGroupId] = 1L
                it[clinicId] = 31L
                it[planId] = null
                it[aggregateType] = envelope.aggregateType
                it[SchedulingOutboxEvents.aggregateId] = appointmentAggregateId.toString()
                it[occurredAt] = outboxOccurredAt
                it[SchedulingOutboxEvents.topic] = eventTopic?.value
                it[partitionKey] = key.value
                it[schemaVersion] = 1
                it[payloadJson] = json
                it[status] = SchedulingOutboxStatus.PENDING
                it[SchedulingOutboxEvents.attemptCount] = attempts
                it[nextAttemptAt] = outboxOccurredAt
                it[SchedulingOutboxEvents.leaseOwner] = leaseOwner
                it[SchedulingOutboxEvents.leaseToken] = leaseToken
                it[SchedulingOutboxEvents.leaseUntil] = leaseUntil
            }
        }
    }
}
