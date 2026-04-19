package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.logging.KLogging
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

/**
 * Flyway 마이그레이션이 빈 DB에서 정상적으로 실행되는지 검증하는 테스트.
 *
 * H2 인메모리 DB에 대해 모든 마이그레이션 스크립트를 적용하고,
 * 테이블이 올바르게 생성되는지 확인합니다.
 */
class FlywayMigrationTest {

    companion object : KLogging()

    @Test
    fun `Flyway migration executes successfully on empty H2 database`() {
        val driver = Class.forName("org.h2.Driver").getDeclaredConstructor().newInstance() as Driver
        val dataSource = SimpleDriverDataSource(
            driver,
            "jdbc:h2:mem:flyway_test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        )

        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/h2")
            .load()

        val result = flyway.migrate()

        assertThat(result.success).isTrue()
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(2)

        dataSource.connection.use { conn ->
            val tables = mutableListOf<String>()
            val rs = conn.metaData.getTables(null, null, "SCHEDULING_%", arrayOf("TABLE"))
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"))
            }
            assertThat(tables).containsAll(
                listOf(
                    "SCHEDULING_CLINICS",
                    "SCHEDULING_DOCTORS",
                    "SCHEDULING_APPOINTMENTS",
                    "SCHEDULING_EQUIPMENTS",
                    "SCHEDULING_EQUIPMENT_UNAVAILABILITIES",
                )
            )
        }
    }
}
