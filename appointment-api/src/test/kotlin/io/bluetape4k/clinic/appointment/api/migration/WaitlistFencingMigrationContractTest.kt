package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

/** V31 waitlist fencing tuple의 dialect별 additive migration contract를 검증합니다. */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class WaitlistFencingMigrationContractTest {

    @Test
    fun `V31 fence columns and defaults hold across supported dialects`() {
        WaitlistDeliveryMigrationTestSupport.verifyV31Migration(
            dataSource = h2DataSource("waitlist-fencing-v31"),
            location = "classpath:db/migration/h2",
            dialect = WaitlistDeliveryMigrationTestSupport.Dialect.H2,
        )

        val postgres = Containers.Postgres
        WaitlistDeliveryMigrationTestSupport.verifyV31Migration(
            dataSource = SimpleDriverDataSource(
                Class.forName("org.postgresql.Driver").getDeclaredConstructor().newInstance() as Driver,
                postgres.jdbcUrl,
                postgres.username ?: "test",
                postgres.password ?: "",
            ),
            location = "classpath:db/migration/postgresql",
            dialect = WaitlistDeliveryMigrationTestSupport.Dialect.POSTGRESQL,
        )

        val mysql = Containers.MySql8
        WaitlistDeliveryMigrationTestSupport.verifyV31Migration(
            dataSource = SimpleDriverDataSource(
                Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance() as Driver,
                mysql.jdbcUrl,
                mysql.username ?: "test",
                mysql.password ?: "",
            ),
            location = "classpath:db/migration/mysql",
            dialect = WaitlistDeliveryMigrationTestSupport.Dialect.MYSQL,
        )
    }

    private fun h2DataSource(scope: String): SimpleDriverDataSource {
        val driver = Class.forName("org.h2.Driver").getDeclaredConstructor().newInstance() as Driver
        return SimpleDriverDataSource(
            driver,
            "jdbc:h2:mem:flyway_${scope}_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        )
    }
}
