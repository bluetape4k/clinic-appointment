package io.bluetape4k.clinic.appointment.api.migration

import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

/**
 * 공유 MySQL 8 컨테이너에서 Flyway 마이그레이션을 검증한다.
 */
class FlywayMySQLMigrationTest {

    @Test
    fun `V8 adds the plan foundation without changing a legacy appointment on MySQL 8`() {
        val mysql = Containers.MySql8
        val driver = Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance() as Driver
        val dataSource = SimpleDriverDataSource(
            driver,
            mysql.jdbcUrl,
            mysql.username ?: "test",
            mysql.password ?: "",
        )

        AppointmentPlanMigrationTestSupport.verifyV8Migration(
            dataSource = dataSource,
            location = "classpath:db/migration/mysql",
        )
    }
}
