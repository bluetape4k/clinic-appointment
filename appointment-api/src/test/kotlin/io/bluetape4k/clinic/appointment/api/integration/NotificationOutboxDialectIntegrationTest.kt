package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.clinic.appointment.api.migration.NotificationOutboxMigrationTestSupport
import io.bluetape4k.clinic.appointment.api.migration.TenantQueryIsolationMigrationTestSupport
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

/** V14 알림 outbox 스키마를 세 지원 dialect에서 같은 순서로 검증합니다. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class NotificationOutboxDialectIntegrationTest {

    @Test
    @Order(1)
    fun `H2는 알림 outbox lifecycle 계약을 제공한다`() {
        NotificationOutboxMigrationTestSupport.verifyV14Migration(
            SimpleDriverDataSource(
                driver("org.h2.Driver"),
                "jdbc:h2:mem:notification_dialect_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            ),
            "classpath:db/migration/h2",
        )
        TenantQueryIsolationMigrationTestSupport.verifyV21Migration(
            SimpleDriverDataSource(
                driver("org.h2.Driver"),
                "jdbc:h2:mem:tenant_scope_dialect_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            ),
            "classpath:db/migration/h2",
        )
    }

    @Test
    @Order(2)
    fun `PostgreSQL은 알림 outbox lifecycle 계약을 제공한다`() {
        val postgres = Containers.Postgres
        NotificationOutboxMigrationTestSupport.verifyV14Migration(
            SimpleDriverDataSource(
                driver("org.postgresql.Driver"),
                postgres.jdbcUrl,
                postgres.username ?: "test",
                postgres.password ?: "",
            ),
            "classpath:db/migration/postgresql",
        )
        TenantQueryIsolationMigrationTestSupport.verifyV21Migration(
            SimpleDriverDataSource(
                driver("org.postgresql.Driver"),
                postgres.jdbcUrl,
                postgres.username ?: "test",
                postgres.password ?: "",
            ),
            "classpath:db/migration/postgresql",
        )
    }

    @Test
    @Order(3)
    fun `MySQL은 알림 outbox lifecycle 계약을 제공한다`() {
        val mysql = Containers.MySql8
        NotificationOutboxMigrationTestSupport.verifyV14Migration(
            SimpleDriverDataSource(
                driver("com.mysql.cj.jdbc.Driver"),
                mysql.jdbcUrl,
                mysql.username ?: "test",
                mysql.password ?: "",
            ),
            "classpath:db/migration/mysql",
        )
        TenantQueryIsolationMigrationTestSupport.verifyV21Migration(
            SimpleDriverDataSource(
                driver("com.mysql.cj.jdbc.Driver"),
                mysql.jdbcUrl,
                mysql.username ?: "test",
                mysql.password ?: "",
            ),
            "classpath:db/migration/mysql",
        )
    }

    private fun driver(className: String): Driver =
        Class.forName(className).getDeclaredConstructor().newInstance() as Driver
}
