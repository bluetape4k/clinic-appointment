package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.lettuce.core.RedisClient
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.sql.Connection
import java.sql.Driver
import java.time.Duration
import java.util.function.Supplier
import javax.sql.DataSource

@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class WaitlistFencedSchedulingConfigurationTest {

    @Test
    fun `global disabled does not create fenced scheduler`() {
        contextRunner()
            .withPropertyValues("appointment.waitlist.delivery.enabled=false")
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType(WaitlistFencedDeliverySchedulingRunner::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(WaitlistFencedScheduler::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `enabled wiring without dispatcher fails closed`() {
        contextRunner()
            .withPropertyValues("appointment.waitlist.delivery.enabled=true")
            .withBean(DataSource::class.java, Supplier { readyDataSource() })
            .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            .withBean(RedisClient::class.java, Supplier { RedisClient.create("redis://localhost:6379") })
            .withBean(WaitlistOfferExpiryRunner::class.java, Supplier { WaitlistOfferExpiryRunner { _, _ -> 0 } })
            .withBean(WaitlistNotificationSuppressionRunner::class.java, Supplier { WaitlistNotificationSuppressionRunner { _, _ -> 0 } })
            .withBean(WaitlistHoldReconciler::class.java, Supplier { WaitlistHoldReconciler { _, _ -> 0 } })
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType(WaitlistFencedDeliverySchedulingRunner::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(WaitlistFencedScheduler::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `missing V31 columns produce typed startup failure`() {
        contextRunner()
            .withPropertyValues("appointment.waitlist.delivery.enabled=true")
            .withBean(DataSource::class.java, Supplier { incompleteDataSource() })
            .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            .withBean(RedisClient::class.java, Supplier { RedisClient.create("redis://localhost:6379") })
            .withBean(WaitlistFencedVacancyDispatcher::class.java, Supplier { WaitlistFencedVacancyDispatcher { _, _, _ -> 0 } })
            .withBean(WaitlistOfferExpiryRunner::class.java, Supplier { WaitlistOfferExpiryRunner { _, _ -> 0 } })
            .withBean(WaitlistNotificationSuppressionRunner::class.java, Supplier { WaitlistNotificationSuppressionRunner { _, _ -> 0 } })
            .withBean(WaitlistHoldReconciler::class.java, Supplier { WaitlistHoldReconciler { _, _ -> 0 } })
            .run { context ->
                context.startupFailure.shouldNotBeNull().let { failure ->
                    failure.findCause<WaitlistFencingReadinessException>()
                        .shouldNotBeNull()
                }
            }
    }

    @Test
    fun `ready V31 schema and complete ports create fenced scheduler`() {
        contextRunner()
            .withPropertyValues(
                "appointment.waitlist.delivery.enabled=true",
                "appointment.waitlist.delivery.fence-epoch=3",
                "appointment.waitlist.delivery.job-lease=30s",
            )
            .withBean(DataSource::class.java, Supplier { readyDataSource() })
            .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            .withBean(RedisClient::class.java, Supplier { RedisClient.create(Containers.Redis.url) })
            .withBean(WaitlistFencedVacancyDispatcher::class.java, Supplier { WaitlistFencedVacancyDispatcher { _, _, _ -> 0 } })
            .withBean(WaitlistOfferExpiryRunner::class.java, Supplier { WaitlistOfferExpiryRunner { _, _ -> 0 } })
            .withBean(WaitlistNotificationSuppressionRunner::class.java, Supplier { WaitlistNotificationSuppressionRunner { _, _ -> 0 } })
            .withBean(WaitlistHoldReconciler::class.java, Supplier { WaitlistHoldReconciler { _, _ -> 0 } })
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType(WaitlistFencingReadiness::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(WaitlistDeliveryMetrics::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(WaitlistFencedDeliverySchedulingRunner::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(WaitlistFencedScheduler::class.java).size shouldBeEqualTo 1
                context.getBean(WaitlistFencedDeliverySchedulingRunner::class.java).shouldNotBeNull()
            }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withUserConfiguration(WaitlistFencedSchedulingConfiguration::class.java)

    private fun readyDataSource(): DataSource = h2DataSource { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE scheduling_waitlist_vacancy_jobs(" +
                    "fence_epoch BIGINT NOT NULL DEFAULT 0, " +
                    "fence_sequence BIGINT NOT NULL DEFAULT 0)",
            )
        }
    }

    private fun incompleteDataSource(): DataSource = h2DataSource { connection ->
        connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE scheduling_waitlist_vacancy_jobs(id BIGINT NOT NULL)")
        }
    }

    private fun h2DataSource(seed: (Connection) -> Unit): DataSource {
        val driver = Class.forName("org.h2.Driver").getDeclaredConstructor().newInstance() as Driver
        return SimpleDriverDataSource(
            driver,
            "jdbc:h2:mem:waitlist_fenced_config_${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
        ).also { dataSource -> dataSource.connection.use(seed) }
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? =
    generateSequence(this) { it.cause }.filterIsInstance<T>().firstOrNull()
