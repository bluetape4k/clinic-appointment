package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * 활성 dialect의 전체 tenant schema를 재생성하므로 API 통합 테스트와 같은 DB lock을 사용한다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class MultitenancyMigrationTest {

    @Test
    fun `V3-V6 applies cleanly and enforces tenant ownership`() {
        val target = MigrationTarget.fromActiveProfiles()
        migrate(target)

        DriverManager.getConnection(target.url, target.user, target.password).use { connection ->
            connection.count(
                "SELECT COUNT(*) FROM scheduling_tenant_groups WHERE tenant_code = 'tenant-default' AND active = TRUE"
            ) shouldBeEqualTo 1L

            connection.expectSqlFailure {
                executeUpdate(
                    """
                    INSERT INTO scheduling_clinics (name, slot_duration_minutes)
                    VALUES ('Missing Tenant Clinic', 30)
                    """.trimIndent()
                )
            }

            connection.expectSqlFailure {
                executeUpdate(
                    """
                    INSERT INTO scheduling_clinics (tenant_group_id, name, slot_duration_minutes)
                    VALUES (999, 'Bad Tenant Clinic', 30)
                    """.trimIndent()
                )
            }

            connection.executeUpdate(
                """
                INSERT INTO scheduling_tenant_groups (id, tenant_code, display_name, active)
                VALUES (2, 'tenant-b', 'Tenant B', TRUE)
                """.trimIndent()
            )

            connection.executeUpdate(
                """
                INSERT INTO scheduling_holidays (tenant_group_id, holiday_date, name, recurring)
                VALUES (1, '2026-01-01', 'Default New Year', FALSE)
                """.trimIndent()
            )
            connection.executeUpdate(
                """
                INSERT INTO scheduling_holidays (tenant_group_id, holiday_date, name, recurring)
                VALUES (2, '2026-01-01', 'Tenant B New Year', FALSE)
                """.trimIndent()
            )

            connection.expectSqlFailure {
                executeUpdate(
                    """
                    INSERT INTO scheduling_holidays (tenant_group_id, holiday_date, name, recurring)
                    VALUES (1, '2026-01-01', 'Duplicate Default New Year', FALSE)
                    """.trimIndent()
                )
            }
        }
    }

    private fun migrate(target: MigrationTarget) {
        val flyway = Flyway.configure()
            .dataSource(target.url, target.user, target.password)
            .locations("classpath:db/migration/${target.vendor}")
            .cleanDisabled(false)
            .load()

        flyway.clean()
        flyway.migrate()
    }

    private fun Connection.count(sql: String): Long =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }

    private fun Connection.executeUpdate(sql: String): Int =
        createStatement().use { statement -> statement.executeUpdate(sql) }

    private fun Connection.expectSqlFailure(block: Connection.() -> Unit) {
        runCatching { block() }
            .exceptionOrNull()
            .let { it is SQLException }
            .shouldBeTrue()
    }

    private data class MigrationTarget(
        val vendor: String,
        val url: String,
        val user: String,
        val password: String,
    ) {
        companion object {
            fun fromActiveProfiles(): MigrationTarget {
                val activeProfiles = System.getProperty("spring.profiles.active", "test")
                return when {
                    "test-postgresql" in activeProfiles -> {
                        val postgres = Containers.Postgres
                        MigrationTarget(
                            vendor = "postgresql",
                            url = postgres.jdbcUrl,
                            user = postgres.username ?: "test",
                            password = postgres.password ?: "",
                        )
                    }

                    "test-mysql" in activeProfiles -> {
                        val mysql = Containers.MySql8
                        MigrationTarget(
                            vendor = "mysql",
                            url = mysql.jdbcUrl,
                            user = mysql.username ?: "test",
                            password = mysql.password ?: "",
                        )
                    }

                    else -> MigrationTarget(
                        vendor = "h2",
                        url = "jdbc:h2:mem:multitenancy-migration;DB_CLOSE_DELAY=-1",
                        user = "sa",
                        password = "",
                    )
                }
            }
        }
    }
}
