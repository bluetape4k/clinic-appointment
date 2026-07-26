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
    fun `V8 adds the plan foundation without changing a legacy appointment on PostgreSQL`() {
        val postgres = Containers.Postgres
        val driver = Class.forName("org.postgresql.Driver").getDeclaredConstructor().newInstance() as Driver
        val dataSource = SimpleDriverDataSource(
            driver,
            postgres.jdbcUrl,
            postgres.username ?: "test",
            postgres.password ?: "",
        )

        AppointmentPlanMigrationTestSupport.verifyV8Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
        )
    }
}
