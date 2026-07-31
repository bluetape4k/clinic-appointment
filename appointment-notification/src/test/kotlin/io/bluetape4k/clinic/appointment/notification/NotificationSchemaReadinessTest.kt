package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.notification.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEvents
import java.time.Instant
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

internal class NotificationSchemaReadinessTest {

    @Test
    fun `old schema 또는 active key 부재는 readiness DOWN으로 worker 시작을 막는다`() {
        val database = connect("readiness_down")
        transaction(database) {
            SchemaUtils.create(NotificationOutboxEvents, NotificationDeliveryAttempts)
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
            SchemaUtils.create(NotificationOutboxEvents, NotificationDeliveryAttempts)
        }
        NotificationSchemaReadiness(
            database = missingFlyway,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        ).check().available shouldBeEqualTo false

        val missingIndex = connect("readiness_missing_index")
        transaction(missingIndex) {
            SchemaUtils.create(NotificationOutboxEvents, NotificationDeliveryAttempts, FlywaySchemaHistory)
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "14"
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
    fun `outbox attempt table index와 active key가 준비되면 readiness UP이다`() {
        val database = connect("readiness_up")
        transaction(database) {
            SchemaUtils.create(NotificationOutboxEvents, NotificationDeliveryAttempts)
            SchemaUtils.create(FlywaySchemaHistory)
            FlywaySchemaHistory.insert {
                it[installedRank] = 1
                it[version] = "14"
                it[success] = true
            }
        }

        val readiness = NotificationSchemaReadiness(
            database = database,
            cryptoProperties = NotificationCryptoProperties(active = key()),
        )

        readiness.check().available shouldBeEqualTo true
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
}
