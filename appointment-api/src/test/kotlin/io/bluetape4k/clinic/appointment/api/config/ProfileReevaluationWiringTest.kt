package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.profile.ProfileAssessmentClient
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationAdminService
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationAppointmentProcessor
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDispatcher
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationEndpoint
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationHealthIndicator
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationRuntimeGate
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationWorker
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class ProfileReevaluationWiringTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(ProfileReevaluationConfiguration::class.java)
            .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            .withBean(Database::class.java, Supplier {
                Database.connect(
                    "jdbc:h2:mem:profile_wiring_${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                )
            })
            .withBean(AppointmentRepository::class.java, Supplier { AppointmentRepository() })

    @Test
    fun `기본 비활성 구성도 운영 조회와 health를 제공하지만 worker는 만들지 않는다`() {
        runner.run { context ->
            context.startupFailure shouldBeEqualTo null
            context.getBeansOfType(ProfileReevaluationRuntimeGate::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ProfileReevaluationAdminService::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ProfileReevaluationEndpoint::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ProfileReevaluationHealthIndicator::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ProfileReevaluationWorker::class.java).size shouldBeEqualTo 0
            context.getBeansOfType(ProfileReevaluationDispatcher::class.java).size shouldBeEqualTo 0
            context.getBeansOfType(ProfileReevaluationSchedulingRunner::class.java).size shouldBeEqualTo 0
        }
    }

    @Test
    fun `활성 구성은 assessment와 processor가 준비된 경우에만 worker graph를 만든다`() {
        runner
            .withBean(ProfileAssessmentClient::class.java, Supplier {
                ProfileAssessmentClient { error("not called by wiring test") }
            })
            .withBean(ProfileReevaluationAppointmentProcessor::class.java, Supplier {
                ProfileReevaluationAppointmentProcessor { _, _, _, _ -> null }
            })
            .withPropertyValues(
                "appointment.profile-reevaluation.enabled=true",
                "appointment.profile-reevaluation.mutation-mode=APPLY_PROPOSED",
                "appointment.profile-reevaluation.clinic-allowlist[0]=11",
                "appointment.profile-reevaluation.assessment.base-url=https://crm.example.test",
                "appointment.profile-reevaluation.assessment.allowed-hosts[0]=crm.example.test",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType(ProfileReevaluationWorker::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(ProfileReevaluationDispatcher::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(ProfileReevaluationSchedulingRunner::class.java).size shouldBeEqualTo 1
            }
    }
}
