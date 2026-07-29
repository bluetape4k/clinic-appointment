package io.bluetape4k.clinic.appointment.api.migration

import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

class FlywayMigrationTest {

    @Test
    fun `V9 contract remains valid and V10 through V12 add versioned visit commitment schema`() {
        val driver = Class.forName("org.h2.Driver").getDeclaredConstructor().newInstance() as Driver
        val dataSource = SimpleDriverDataSource(
            driver,
            "jdbc:h2:mem:flyway_plan_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        )
        AppointmentPlanMigrationTestSupport.verifyV9Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/h2",
        )
        VisitCommitmentMigrationTestSupport.verifyVisitCommitmentMigrations(
            dataSource = dataSource,
            location = "classpath:db/migration/h2",
        )
    }
}
