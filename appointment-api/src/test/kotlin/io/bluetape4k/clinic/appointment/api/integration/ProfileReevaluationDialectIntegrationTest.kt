package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.clinic.appointment.api.migration.ProfileReevaluationMigrationTestSupport
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

/**
 * 프로필 재평가 V13의 테이블·제약·조회 index를 실제 지원 dialect에서 검증합니다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class ProfileReevaluationDialectIntegrationTest {

    @Test
    @Order(1)
    fun `H2 exposes the profile reevaluation schema`() {
        ProfileReevaluationMigrationTestSupport.verifyV13Migration(
            dataSource = SimpleDriverDataSource(
                driver("org.h2.Driver"),
                "jdbc:h2:mem:profile_reevaluation_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            ),
            location = "classpath:db/migration/h2",
        )
    }

    @Test
    @Order(2)
    fun `PostgreSQL exposes the profile reevaluation schema`() {
        val postgres = Containers.Postgres
        ProfileReevaluationMigrationTestSupport.verifyV13Migration(
            dataSource = SimpleDriverDataSource(
                driver("org.postgresql.Driver"),
                postgres.jdbcUrl,
                postgres.username ?: "test",
                postgres.password ?: "",
            ),
            location = "classpath:db/migration/postgresql",
        )
    }

    @Test
    @Order(3)
    fun `MySQL exposes the profile reevaluation schema`() {
        val mysql = Containers.MySql8
        ProfileReevaluationMigrationTestSupport.verifyV13Migration(
            dataSource = SimpleDriverDataSource(
                driver("com.mysql.cj.jdbc.Driver"),
                mysql.jdbcUrl,
                mysql.username ?: "test",
                mysql.password ?: "",
            ),
            location = "classpath:db/migration/mysql",
        )
    }

    private fun driver(className: String): Driver =
        Class.forName(className).getDeclaredConstructor().newInstance() as Driver
}
