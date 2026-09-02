package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.clinic.appointment.event.AppointmentEventLogs
import io.bluetape4k.clinic.appointment.notification.persistence.ClaimedNotification
import io.bluetape4k.clinic.appointment.notification.persistence.CompleteNotificationCommand
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationFairCursor
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxEvents
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.notification.persistence.RetryNotificationCommand
import io.bluetape4k.clinic.appointment.notification.persistence.WaitlistNotificationOutboxEvents
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
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
            )
            SchemaUtils.createMissingTablesAndColumns(FlywaySchemaHistory)
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
    fun `waitlist notification outbox table이 없으면 정확한 missing table 사유로 readiness DOWN이다`() {
        val database = connect("readiness_missing_waitlist_table")
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                FlywaySchemaHistory,
            )
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "21"
                it[success] = true
            }
        }

        val result = NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check()

        result.available shouldBeEqualTo false
        result.reason shouldBeEqualTo "missing tables: clinic_waitlist_notification_outbox"
        result.diagnostics.single().code shouldBeEqualTo "SCHEMA_TABLE_MISSING"
        result.diagnostics.single().target shouldBeEqualTo "clinic_waitlist_notification_outbox"
    }

    @Test
    fun `Flyway 기준 정보나 필수 claim index가 없으면 readiness DOWN이다`() {
        val missingFlyway = connect("readiness_missing_flyway")
        transaction(missingFlyway) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
            )
        }
        NotificationSchemaReadiness(
            database = missingFlyway,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check().available shouldBeEqualTo false

        val missingIndex = connect("readiness_missing_index")
        transaction(missingIndex) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
                FlywaySchemaHistory,
            )
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
    fun `waitlist idempotency index가 없으면 정확한 missing index 사유로 readiness DOWN이다`() {
        val database = connect("readiness_missing_waitlist_idempotency_index")
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
                FlywaySchemaHistory,
            )
            // H2는 Exposed의 uniqueIndex를 constraint로 생성하므로 migration과 같은 이름으로 정규화한다.
            exec("ALTER TABLE clinic_waitlist_notification_outbox DROP CONSTRAINT uk_waitlist_notification_outbox_idempotency")
            exec(
                "CREATE UNIQUE INDEX uk_waitlist_notification_outbox_idempotency ON clinic_waitlist_notification_outbox (tenant_group_id, clinic_id, idempotency_key)",
            )
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "21"
                it[success] = true
            }
            exec("DROP INDEX uk_waitlist_notification_outbox_idempotency")
        }

        val result = NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check()

        result.available shouldBeEqualTo false
        result.reason shouldBeEqualTo "missing indexes: uk_waitlist_notification_outbox_idempotency"
    }

    @Test
    fun `waitlist ready index가 없으면 정확한 missing index 사유로 readiness DOWN이다`() {
        val database = connect("readiness_missing_waitlist_ready_index")
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
                FlywaySchemaHistory,
            )
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "21"
                it[success] = true
            }
            exec("DROP INDEX idx_waitlist_notification_outbox_ready")
        }

        val result = NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check()

        result.available shouldBeEqualTo false
        result.reason shouldBeEqualTo "missing indexes: idx_waitlist_notification_outbox_ready"
    }

    @Test
    fun `waitlist lease index가 없으면 정확한 missing index 사유로 readiness DOWN이다`() {
        val database = connect("readiness_missing_waitlist_lease_index")
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
                FlywaySchemaHistory,
            )
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "21"
                it[success] = true
            }
            exec("DROP INDEX idx_waitlist_notification_outbox_lease")
        }

        val result = NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check()

        result.available shouldBeEqualTo false
        result.reason shouldBeEqualTo "missing indexes: idx_waitlist_notification_outbox_lease"
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
            SchemaUtils.createMissingTablesAndColumns(
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
                FlywaySchemaHistory,
            )
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
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
                FlywaySchemaHistory,
            )
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
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
                FlywaySchemaHistory,
            )
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
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
            )
            SchemaUtils.createMissingTablesAndColumns(FlywaySchemaHistory)
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
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentEventLogs,
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
                WaitlistNotificationOutboxEvents,
                FlywaySchemaHistory,
            )
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
            "jdbc:h2:mem:${name}_${System.nanoTime()}_${Base58.randomString(8)};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )

    private fun key(): NotificationCryptoProperties.KeyReference {
        val now = Instant.now()
        return NotificationCryptoProperties.KeyReference(
            keyId = "active-2026-07",
            secretReference = "vault:/clinic/notification/active",
            activatedAt = now.minus(Duration.ofDays(1)),
            expiresAt = now.plus(Duration.ofDays(1)),
        )
    }

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
