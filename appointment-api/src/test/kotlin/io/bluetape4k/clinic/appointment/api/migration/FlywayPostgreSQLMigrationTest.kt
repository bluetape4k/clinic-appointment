package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

/**
 * 공유 PostgreSQL 컨테이너에서 Flyway 마이그레이션을 검증한다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class FlywayPostgreSQLMigrationTest {

    @Test
    fun `V9 contract remains valid and V10 through V17 add durable scheduling schema on PostgreSQL`() {
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
        VisitCommitmentMigrationTestSupport.verifyVisitCommitmentMigrations(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
        )
        ProfileReevaluationMigrationTestSupport.verifyV13Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
        )
        NotificationOutboxMigrationTestSupport.verifyV14Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
        )
        LegacyAppointmentVersionMigrationTestSupport.verifyV15Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
        )
        ReminderRecoveryCheckpointMigrationTestSupport.verifyV16Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
        )
        BookingReliabilityMigrationTestSupport.verifyV17Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/postgresql",
        )
    }
}
