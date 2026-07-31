package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.Date
import java.sql.SQLException
import java.sql.Time
import javax.sql.DataSource

/**
 * V15 legacy 예약 version이 clean 설치와 V14 업그레이드에서 같은 계약을 제공하는지 검증합니다.
 */
internal object LegacyAppointmentVersionMigrationTestSupport {

    fun verifyV15Migration(
        dataSource: DataSource,
        location: String,
    ) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .cleanDisabled(false)
            .load()
        flyway.clean()

        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("14")
            .load()
            .migrate()
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target("15")
            .load()
            .migrate()

        result.success shouldBeEqualTo true
        result.migrationsExecuted shouldBeEqualTo 1
        dataSource.connection.use(::verifyVersionContract)
    }

    private fun verifyVersionContract(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO scheduling_clinics " +
                    "(id, name, tenant_group_id) VALUES (91001, 'Version Clinic', 1)",
            )
            statement.executeUpdate(
                "INSERT INTO scheduling_doctors " +
                    "(id, clinic_id, name) VALUES (91002, 91001, 'Version Doctor')",
            )
            statement.executeUpdate(
                "INSERT INTO scheduling_treatment_types " +
                    "(id, clinic_id, name, default_duration_minutes) " +
                    "VALUES (91003, 91001, 'Version Treatment', 30)",
            )
        }
        connection.prepareStatement(
            """
            INSERT INTO scheduling_appointments (
                id, clinic_id, doctor_id, treatment_type_id, patient_name,
                appointment_date, start_time, end_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, 91004L)
            statement.setLong(2, 91001L)
            statement.setLong(3, 91002L)
            statement.setLong(4, 91003L)
            statement.setString(5, "Version Patient")
            statement.setDate(6, Date.valueOf("2026-08-01"))
            statement.setTime(7, Time.valueOf("09:00:00"))
            statement.setTime(8, Time.valueOf("09:30:00"))
            statement.executeUpdate()
        }
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT version FROM scheduling_appointments WHERE id = 91004",
            ).use { rows ->
                check(rows.next()) { "V15 version fixture row is missing" }
                rows.getLong(1) shouldBeEqualTo 0L
            }
        }

        val rejected = try {
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE scheduling_appointments SET version = -1 WHERE id = 91004",
                )
            }
            false
        } catch (_: SQLException) {
            true
        }
        check(rejected) { "V15 must reject negative legacy appointment versions" }
    }
}
