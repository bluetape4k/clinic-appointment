package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.logging.KLogging
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Driver

/**
 * PostgreSQL Testcontainer에서 Flyway 마이그레이션이 정상 실행되는지 검증.
 */
@Testcontainers
class FlywayPostgreSQLMigrationTest {

    companion object : KLogging() {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Test
    fun `Flyway migration executes successfully on PostgreSQL`() {
        val driver = Class.forName(postgres.driverClassName).getDeclaredConstructor().newInstance() as Driver
        val dataSource = SimpleDriverDataSource(driver, postgres.jdbcUrl, postgres.username, postgres.password)

        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .load()

        val result = flyway.migrate()

        assertThat(result.success).isTrue()
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(2)

        dataSource.connection.use { conn ->
            val tables = mutableListOf<String>()
            val rs = conn.metaData.getTables(null, "public", "scheduling_%", arrayOf("TABLE"))
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"))
            }
            assertThat(tables).containsAll(
                listOf(
                    "scheduling_clinics",
                    "scheduling_doctors",
                    "scheduling_appointments",
                    "scheduling_equipments",
                    "scheduling_equipment_unavailabilities",
                )
            )
        }
    }
}
