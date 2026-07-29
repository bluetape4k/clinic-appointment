package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import io.bluetape4k.clinic.appointment.api.service.FailClosedPatientSubjectFingerprintResolver
import io.bluetape4k.clinic.appointment.api.service.PatientSubjectFingerprintResolver
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

/**
 * commitment v2 controller flag를 켠 배포에서 실제 application service wiring이 누락되지
 * 않는지 검증한다.
 */
class AppointmentCommitmentApplicationWiringTest {

    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(ServiceConfig::class.java)
            .withBean("meterRegistry", MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean("database", Database::class.java, Supplier {
                Database.connect(
                    url = "jdbc:h2:mem:commitment_wiring_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                    driver = "org.h2.Driver",
                )
            })

    @Test
    fun `api enabled context starts with the real commitment application service`() {
        contextRunner
            .withPropertyValues(
                "appointment.commitment.api-enabled=true",
                "appointment.commitment.idempotency-hash-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
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
}
