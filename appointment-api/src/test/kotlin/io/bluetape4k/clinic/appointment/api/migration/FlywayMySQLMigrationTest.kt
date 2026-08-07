package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

/**
 * 공유 MySQL 8 컨테이너에서 Flyway 마이그레이션을 검증한다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class FlywayMySQLMigrationTest {

    /**
     * Optional, read-only metadata smoke test for a deployed/staging MySQL endpoint.
     *
     * The test deliberately does not run Flyway against the endpoint: applying or cleaning a
     * production schema belongs to an approved change window and must not be hidden in CI.
     */
    @Test
    fun `production MySQL metadata readiness is verified when endpoint is configured`() {
        val url = System.getenv("APPOINTMENT_PRODUCTION_MYSQL_JDBC_URL")
        val user = System.getenv("APPOINTMENT_PRODUCTION_MYSQL_USER")
        val password = System.getenv("APPOINTMENT_PRODUCTION_MYSQL_PASSWORD")
        assumeTrue(
            !url.isNullOrBlank() && !user.isNullOrBlank() && password != null,
            "Set APPOINTMENT_PRODUCTION_MYSQL_JDBC_URL/USER/PASSWORD for the read-only endpoint smoke test",
        )

        val driver = Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance() as Driver
        AppointmentMessagingMigrationTestSupport.verifyV23Metadata(
            dataSource = SimpleDriverDataSource(driver, requireNotNull(url), requireNotNull(user), password),
        )
    }

    @Test
    fun `V9 contract remains valid and V10 through V19 add durable scheduling schema on MySQL 8`() {
        val mysql = Containers.MySql8
        val driver = Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance() as Driver
        val dataSource = SimpleDriverDataSource(
            driver,
            mysql.jdbcUrl,
            mysql.username ?: "test",
            mysql.password ?: "",
        )

        AppointmentPlanMigrationTestSupport.verifyV9Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/mysql",
        )
        VisitCommitmentMigrationTestSupport.verifyVisitCommitmentMigrations(
            dataSource = dataSource,
            location = "classpath:db/migration/mysql",
        )
        ProfileReevaluationMigrationTestSupport.verifyV13Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/mysql",
        )
        NotificationOutboxMigrationTestSupport.verifyV14Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/mysql",
        )
        LegacyAppointmentVersionMigrationTestSupport.verifyV15Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/mysql",
        )
        ReminderRecoveryCheckpointMigrationTestSupport.verifyV16Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/mysql",
        )
        BookingReliabilityMigrationTestSupport.verifyV17Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/mysql",
        )
        WaitlistCoreMigrationTestSupport.verifyV18Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/mysql",
            dialect = WaitlistCoreMigrationTestSupport.Dialect.MYSQL,
        )
        WaitlistDeliveryMigrationTestSupport.verifyV19Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/mysql",
            dialect = WaitlistDeliveryMigrationTestSupport.Dialect.MYSQL,
        )
    }

    @Test
    fun `V22 appointment messaging outbox lease contract remains additive on MySQL 8`() {
        val mysql = Containers.MySql8
        val driver = Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance() as Driver
        AppointmentMessagingMigrationTestSupport.verifyV22Migration(
            dataSource = SimpleDriverDataSource(
                driver,
                mysql.jdbcUrl,
                mysql.username ?: "test",
                mysql.password ?: "",
            ),
            location = "classpath:db/migration/mysql",
        )
    }

    @Test
    fun `V23 consumer metadata contract is complete on MySQL 8`() {
        val mysql = Containers.MySql8
        val driver = Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance() as Driver
        AppointmentMessagingMigrationTestSupport.verifyV23Migration(
            dataSource = SimpleDriverDataSource(
                driver,
                mysql.jdbcUrl,
                mysql.username ?: "test",
                mysql.password ?: "",
            ),
            location = "classpath:db/migration/mysql",
        )
    }
}
