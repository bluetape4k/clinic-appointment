package io.bluetape4k.clinic.appointment.api.migration

import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

class FlywayMigrationTest {

    @Test
    fun `V9 contract remains valid and V10 through V14 add visit reevaluation and notification schema`() {
        AppointmentPlanMigrationTestSupport.verifyV9Migration(
            dataSource = h2DataSource("plan"),
            location = "classpath:db/migration/h2",
        )
        VisitCommitmentMigrationTestSupport.verifyVisitCommitmentMigrations(
            dataSource = h2DataSource("visit"),
            location = "classpath:db/migration/h2",
        )
        ProfileReevaluationMigrationTestSupport.verifyV13Migration(
            dataSource = h2DataSource("profile"),
            location = "classpath:db/migration/h2",
        )
        NotificationOutboxMigrationTestSupport.verifyV14Migration(
            dataSource = h2DataSource("notification"),
            location = "classpath:db/migration/h2",
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
