package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.AppointmentEventLogs
import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.CompleteNotificationCommand
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.NotificationFairCursor
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.RetryNotificationCommand
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.junit.jupiter.api.Test

internal class NotificationSchemaReadinessTest {

    @Test
    fun `old schema 또는 active key 부재는 readiness DOWN으로 worker 시작을 막는다`() {
        val database = connect("readiness_down")
        transaction(database) {
            SchemaUtils.create(TenantGroups, Clinics, AppointmentEventLogs, NotificationOutboxEvents, NotificationDeliveryAttempts)
            SchemaUtils.create(FlywaySchemaHistory)
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "13"
                it[success] = true
            }
        }

        val readiness = NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = null),
        )

        readiness.check().available shouldBeEqualTo false
    }

    @Test
    fun `Flyway 기준 정보나 필수 claim index가 없으면 readiness DOWN이다`() {
        val missingFlyway = connect("readiness_missing_flyway")
        transaction(missingFlyway) {
            SchemaUtils.create(TenantGroups, Clinics, AppointmentEventLogs, NotificationOutboxEvents, NotificationDeliveryAttempts)
        }
        NotificationSchemaReadiness(
            database = missingFlyway,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check().available shouldBeEqualTo false

        val missingIndex = connect("readiness_missing_index")
        transaction(missingIndex) {
            SchemaUtils.create(TenantGroups, Clinics, AppointmentEventLogs, NotificationOutboxEvents, NotificationDeliveryAttempts, FlywaySchemaHistory)
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "21"
                it[success] = true
            }
            exec("DROP INDEX idx_notification_outbox_lease_recovery")
        }
        NotificationSchemaReadiness(
            database = missingIndex,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check().available shouldBeEqualTo false
    }

    @Test
    fun `V21 event-log tenant column이 없으면 readiness DOWN이다`() {
        val database = connect("readiness_missing_event_tenant_column")
        transaction(database) {
            exec(
                """
                CREATE TABLE scheduling_appointment_event_logs (
                    id BIGINT PRIMARY KEY,
                    event_type VARCHAR(50) NOT NULL,
                    entity_type VARCHAR(100) NOT NULL,
                    entity_id BIGINT NOT NULL,
                    clinic_id BIGINT NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """.trimIndent(),
            )
            SchemaUtils.create(NotificationOutboxEvents, NotificationDeliveryAttempts, FlywaySchemaHistory)
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "21"
                it[success] = true
            }
        }

        NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check().available shouldBeEqualTo false
    }

    @Test
    fun `tenant backfill orphan가 남아 있으면 readiness DOWN이다`() {
        val database = connect("readiness_orphan_event_tenant")
        transaction(database) {
            SchemaUtils.create(TenantGroups, Clinics, AppointmentEventLogs, NotificationOutboxEvents, NotificationDeliveryAttempts, FlywaySchemaHistory)
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "21"
                it[success] = true
            }
            AppointmentEventLogs.insert {
                it[eventType] = "APPOINTMENT_CREATED"
                it[entityType] = "Appointment"
                it[entityId] = 991L
                it[tenantGroupId] = null
                it[clinicId] = 992L
                it[payloadJson] = "{}"
            }
        }

        NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check().available shouldBeEqualTo false
    }

    @Test
    fun `event-log tenant와 clinic owner가 다르면 readiness DOWN이다`() {
        val database = connect("readiness_tenant_clinic_mismatch")
        transaction(database) {
            SchemaUtils.create(TenantGroups, Clinics, AppointmentEventLogs, NotificationOutboxEvents, NotificationDeliveryAttempts, FlywaySchemaHistory)
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
            }
            TenantGroups.insert {
                it[id] = EntityID(2L, TenantGroups)
                it[tenantCode] = "tenant-two"
                it[displayName] = "Tenant Two"
            }
            val clinicId = Clinics.insertAndGetId {
                it[Clinics.tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Tenant One Clinic"
            }.value
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "21"
                it[success] = true
            }
            AppointmentEventLogs.insert {
                it[eventType] = "APPOINTMENT_CREATED"
                it[entityType] = "Appointment"
                it[entityId] = 993L
                it[tenantGroupId] = 2L
                it[AppointmentEventLogs.clinicId] = clinicId
                it[payloadJson] = "{}"
            }
        }

        NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check().available shouldBeEqualTo false
    }

    @Test
    fun `outbox attempt table index와 active key가 준비되면 readiness UP이다`() {
        val database = connect("readiness_up")
        transaction(database) {
            SchemaUtils.create(TenantGroups, Clinics, AppointmentEventLogs, NotificationOutboxEvents, NotificationDeliveryAttempts)
            SchemaUtils.create(FlywaySchemaHistory)
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "21"
                it[success] = true
            }
        }

        val readiness = NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        )

        readiness.check().available shouldBeEqualTo true
    }

    @Test
    fun `terminal retention index가 없으면 readiness DOWN이고 retention을 실행하지 않는다`() {
        val database = connect("readiness_missing_retention_index")
        transaction(database) {
            SchemaUtils.create(TenantGroups, Clinics, AppointmentEventLogs, NotificationOutboxEvents, NotificationDeliveryAttempts, FlywaySchemaHistory)
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "21"
                it[success] = true
            }
            exec("DROP INDEX idx_notification_outbox_terminal_retention")
        }
        val readiness = NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        )
        val store = ReadinessRetentionWorkStore()

        readiness.check().available shouldBeEqualTo false
        runBlocking {
            NotificationRetentionRunner(
                workStore = store,
                readiness = readiness,
            ).runOnce().deletedByStatus shouldBeEqualTo emptyMap()
        }
        store.deleteCalls shouldBeEqualTo 0
    }

    private fun connect(name: String): Database =
        Database.connect(
            "jdbc:h2:mem:${name}_${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )

    private fun key(): NotificationCryptoProperties.KeyReference =
        NotificationCryptoProperties.KeyReference(
            keyId = "active-2026-07",
            secretReference = "vault:/clinic/notification/active",
            activatedAt = Instant.parse("2026-07-01T00:00:00Z"),
            expiresAt = Instant.parse("2026-08-31T00:00:00Z"),
        )

    private class ReadinessRetentionWorkStore : NotificationOutboxWorkStore {
        var deleteCalls: Int = 0

        override suspend fun findFairCandidates(
            limit: Int,
            cursor: NotificationFairCursor?,
        ): NotificationCandidatePage = NotificationCandidatePage(emptyList(), null)

        override suspend fun claim(id: Long, owner: String): ClaimedNotification? = null

        override suspend fun recoverExpired(limit: Int, owner: String): List<ClaimedNotification> = emptyList()

        override suspend fun complete(command: CompleteNotificationCommand): Boolean = true

        override suspend fun retry(command: RetryNotificationCommand): Boolean = true

        override suspend fun currentDatabaseTime(): Instant = Instant.EPOCH

        override suspend fun deleteTerminalBatch(
            status: NotificationOutboxStatus,
            retention: Duration,
            limit: Int,
        ): Int {
            deleteCalls++
            return 0
        }
    }
}
