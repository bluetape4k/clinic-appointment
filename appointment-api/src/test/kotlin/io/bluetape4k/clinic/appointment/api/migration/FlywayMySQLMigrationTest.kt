package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
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

    @Test
    fun `V9 contract remains valid and V10 through V15 add durable scheduling schema on MySQL 8`() {
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
    }
}
