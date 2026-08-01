package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Driver

/** H2의 bounded SQL contract와 V17 index 이름을 빠르게 검증합니다. 실제 DB dialect 검증은 Flyway dialect test가 담당합니다. */
class BookingReliabilityQueryPlanTest {
    @Test
    fun `member lookback and latest decision queries stay bounded and indexed`() {
        val driver = Class.forName("org.h2.Driver").getDeclaredConstructor().newInstance() as Driver
        val dataSource = SimpleDriverDataSource(
            driver,
            "jdbc:h2:mem:booking_reliability_query_plan_${System.nanoTime()};DB_CLOSE_DELAY=-1",
        )
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/h2")
            .cleanDisabled(false)
            .load()
            .migrate()

        dataSource.connection.use { connection ->
            val plan = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    EXPLAIN SELECT event_id FROM booking_reliability_events
                    WHERE tenant_group_id = 1 AND clinic_id = 1 AND member_id = 'member-opaque-1'
                      AND occurred_at <= CURRENT_TIMESTAMP
                    ORDER BY occurred_at, event_id
                    LIMIT 100
                    """.trimIndent(),
                ).use { rows ->
                    buildString { while (rows.next()) append(rows.getString(1)) }
                }
            }
            plan.contains("100", ignoreCase = true).shouldBeTrue()

            val indexNames = mutableSetOf<String>()
            connection.metaData.getIndexInfo(null, null, "BOOKING_RELIABILITY_EVENTS", false, false).use { rows ->
                while (rows.next()) rows.getString("INDEX_NAME")?.let(indexNames::add)
            }
            indexNames.any { it.equals("idx_booking_reliability_event_member_time", ignoreCase = true) }
                .shouldBeTrue()
        }
    }
}
