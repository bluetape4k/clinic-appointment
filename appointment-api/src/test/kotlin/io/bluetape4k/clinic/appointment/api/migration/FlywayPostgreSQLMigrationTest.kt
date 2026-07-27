package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

/**
 * 공유 PostgreSQL 컨테이너에서 Flyway 마이그레이션을 검증한다.
 */
class FlywayPostgreSQLMigrationTest {

    @Test
    fun `V9 adds policy persistence and expands legacy outbox rows on PostgreSQL`() {
        val postgres = Containers.Postgres
        val driver = Class.forName("org.postgresql.Driver").getDeclaredConstructor().newInstance() as Driver
        val dataSource = SimpleDriverDataSource(
            driver,
            postgres.jdbcUrl,
            postgres.username ?: "test",
            postgres.password ?: "",
        )

        AppointmentPlanMigrationTestSupport.verifyV9Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
        )
    }
}
