package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** V16 reminder recovery checkpoint가 clean 설치와 V15 업그레이드에서 같은 계약을 제공하는지 검증합니다. */
internal object ReminderRecoveryCheckpointMigrationTestSupport {

    fun verifyV16Migration(dataSource: DataSource, location: String) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
        flyway.clean()

        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("15")
            .load()
            .migrate()
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("16")
            .load()
            .migrate()

        result.success shouldBeEqualTo true
        result.migrationsExecuted shouldBeEqualTo 1
        dataSource.connection.use(::verifyCheckpointContract)
    }

    private fun verifyCheckpointContract(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO clinic_notification_reminder_checkpoint " +
                    "(scope, run_id, last_appointment_id, active) VALUES " +
                    "('appointment-reminders', 'run-1', 42, TRUE)",
            )
        }
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT run_id, last_appointment_id, active " +
                    "FROM clinic_notification_reminder_checkpoint WHERE scope = 'appointment-reminders'",
            ).use { rows ->
                check(rows.next()) { "V16 reminder recovery checkpoint fixture is missing" }
                rows.getString(1) shouldBeEqualTo "run-1"
                rows.getLong(2) shouldBeEqualTo 42L
                rows.getBoolean(3) shouldBeEqualTo true
            }
        }
        val rejected = try {
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE clinic_notification_reminder_checkpoint " +
                        "SET last_appointment_id = -1 WHERE scope = 'appointment-reminders'",
                )
            }
            false
        } catch (_: SQLException) {
            true
        }
        check(rejected) { "V16 must reject a negative reminder recovery cursor" }
    }
}
