package io.bluetape4k.clinic.appointment.api.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import io.bluetape4k.clinic.appointment.api.service.FailClosedPatientSubjectFingerprintResolver
import io.bluetape4k.clinic.appointment.api.service.PatientSubjectFingerprintResolver
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import javax.sql.DataSource
import java.util.function.Supplier

/**
 * commitment v2 controller flag를 켠 배포에서 실제 application service wiring이 누락되지
 * 않는지 검증한다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class AppointmentCommitmentApplicationWiringTest {

    private var lastDataSource: HikariDataSource? = null

    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(ServiceConfig::class.java)
            .withBean("meterRegistry", MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean("dataSource", DataSource::class.java, Supplier {
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = "jdbc:h2:mem:commitment_wiring_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                        driverClassName = "org.h2.Driver"
                        username = "sa"
                    },
                ).also { dataSource ->
                    seedMarker(dataSource)
                    lastDataSource = dataSource
                }
            })

    @AfterEach
    fun dataSourceIsClosedBySpringContext() {
        lastDataSource?.isClosed?.shouldBeEqualTo(true)
    }

    @Test
    fun `api enabled context starts with the real commitment application service`() {
        contextRunner
            .withPropertyValues(
                "appointment.commitment.api-enabled=true",
                "appointment.commitment.idempotency-hash-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                val database = context.getBean(Database::class.java)
                transaction(database) {
                    exec("SELECT marker_value FROM datasource_marker") { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                } shouldBeEqualTo 223
                context.getBeansOfType(AppointmentCommitmentApplicationService::class.java).size shouldBeEqualTo 1
                context.getBean(PatientSubjectFingerprintResolver::class.java)::class shouldBeEqualTo
                    FailClosedPatientSubjectFingerprintResolver::class
            }
    }

    @Test
    fun `deployment supplied patient fingerprint resolver replaces the fail closed default`() {
        val configuredResolver = PatientSubjectFingerprintResolver { tenantGroupId, subject ->
            "$tenantGroupId:$subject"
        }

        contextRunner
            .withBean(
                "configuredPatientSubjectFingerprintResolver",
                PatientSubjectFingerprintResolver::class.java,
                { configuredResolver },
            )
            .withPropertyValues(
                "appointment.commitment.api-enabled=true",
                "appointment.commitment.idempotency-hash-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBean(PatientSubjectFingerprintResolver::class.java)
                    .fingerprint(7L, "patient") shouldBeEqualTo "7:patient"
            }
    }

    @Test
    fun `api enabled context fails closed without a dedicated idempotency secret`() {
        contextRunner
            .withPropertyValues("appointment.commitment.api-enabled=true")
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                context.startupFailure.hasCauseMessage("idempotency-hash-secret").shouldBeTrue()
            }
    }

    @Test
    fun `api enabled context rejects an undersized idempotency secret`() {
        contextRunner
            .withPropertyValues(
                "appointment.commitment.api-enabled=true",
                "appointment.commitment.idempotency-hash-secret=c2hvcnQ=",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                context.startupFailure.hasCauseMessage("at least 32 bytes").shouldBeTrue()
            }
    }

    private fun Throwable?.hasCauseMessage(fragment: String): Boolean =
        generateSequence(this) { it.cause }
            .mapNotNull(Throwable::message)
            .any { fragment in it }

    private fun seedMarker(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE datasource_marker (marker_value INT NOT NULL)")
                statement.execute("INSERT INTO datasource_marker(marker_value) VALUES (223)")
            }
        }
    }
}
