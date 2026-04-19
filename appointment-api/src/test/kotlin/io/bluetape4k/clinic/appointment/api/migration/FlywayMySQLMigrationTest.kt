package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.logging.KLogging
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Driver

/**
 * MySQL 8 Testcontainer에서 Flyway 마이그레이션이 정상 실행되는지 검증.
 */
@Testcontainers
class FlywayMySQLMigrationTest {

    companion object : KLogging() {
        @Container
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.0")
    }

    @Test
    fun `Flyway migration executes successfully on MySQL 8`() {
        val driver = Class.forName(mysql.driverClassName).getDeclaredConstructor().newInstance() as Driver
        val dataSource = SimpleDriverDataSource(driver, mysql.jdbcUrl, mysql.username, mysql.password)

        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/mysql")
            .load()

        val result = flyway.migrate()

        assertThat(result.success).isTrue()
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(2)

        dataSource.connection.use { conn ->
            val tables = mutableListOf<String>()
            val rs = conn.metaData.getTables(null, null, "scheduling_%", arrayOf("TABLE"))
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
