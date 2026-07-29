package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.clinic.appointment.api.migration.VisitCommitmentMigrationTestSupport
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
 * commitment v2의 additive Flyway 계약을 H2→PostgreSQL→MySQL 순서로 검증합니다.
 *
 * H2 성공을 운영 증거로 대체하지 않으며, 각 실제 dialect에서 V1→V9 기존 row를 만든
 * 뒤 V10·V11·V12를 적용해 legacy 보존, 신규 FK·unique·index, clean install을 확인합니다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class VisitCommitmentDialectIntegrationTest {

    @Test
    @Order(1)
    fun `H2 preserves legacy rows and adds commitment v2`() {
        val driver = driver("org.h2.Driver")
        VisitCommitmentMigrationTestSupport.verifyVisitCommitmentMigrations(
            dataSource =
                SimpleDriverDataSource(
                    driver,
                    "jdbc:h2:mem:visit_dialect_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                ),
            location = "classpath:db/migration/h2",
        )
    }

    @Test
    @Order(2)
    fun `PostgreSQL preserves legacy rows and adds commitment v2`() {
        val postgres = Containers.Postgres
        VisitCommitmentMigrationTestSupport.verifyVisitCommitmentMigrations(
            dataSource =
                SimpleDriverDataSource(
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
    fun `MySQL preserves legacy rows and adds commitment v2`() {
        val mysql = Containers.MySql8
        VisitCommitmentMigrationTestSupport.verifyVisitCommitmentMigrations(
            dataSource =
                SimpleDriverDataSource(
                    driver("com.mysql.cj.jdbc.Driver"),
                    mysql.jdbcUrl,
                    mysql.username ?: "test",
                    mysql.password ?: "",
                ),
            location = "classpath:db/migration/mysql",
        )
    }

    /** 명시한 JDBC driver를 reflection으로 생성한다. */
    private fun driver(className: String): Driver =
        Class.forName(className).getDeclaredConstructor().newInstance() as Driver
}
