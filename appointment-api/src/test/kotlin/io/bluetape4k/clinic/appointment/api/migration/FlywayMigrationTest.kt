package io.bluetape4k.clinic.appointment.api.migration

import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

class FlywayMigrationTest {

    @Test
    fun `V9 contract remains valid and V10 through V18 add durable scheduling schema`() {
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
        LegacyAppointmentVersionMigrationTestSupport.verifyV15Migration(
            dataSource = h2DataSource("legacy-version"),
            location = "classpath:db/migration/h2",
        )
        ReminderRecoveryCheckpointMigrationTestSupport.verifyV16Migration(
            dataSource = h2DataSource("reminder-checkpoint"),
            location = "classpath:db/migration/h2",
        )
        BookingReliabilityMigrationTestSupport.verifyV17Migration(
            dataSource = h2DataSource("booking-reliability"),
            location = "classpath:db/migration/h2",
        )
        WaitlistCoreMigrationTestSupport.verifyV18Migration(
            dataSource = h2DataSource("waitlist-core"),
            location = "classpath:db/migration/h2",
            dialect = WaitlistCoreMigrationTestSupport.Dialect.H2,
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
