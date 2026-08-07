package io.bluetape4k.clinic.appointment.api

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class KotlinProductionPatternComplianceTest {

    private val issue209TouchedTests = listOf(
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/ClinicSchedulingPolicyControllerTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyOpenApiTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyPreviewJobControllerTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/SchedulingPolicyRequestContractTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/TenantSchedulingPolicyControllerTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/integration/SchedulingPolicyDialectIntegrationTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/policy/ExposedSchedulingPolicyPreviewStoreTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/policy/SchedulingPolicyActivationConcurrencyTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/policy/SchedulingPolicyAdministrationServiceTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/policy/SchedulingPolicyCommandServiceTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/policy/SchedulingPolicyPreviewServiceTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/DashboardStatsServiceTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/AbstractApiIntegrationTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentCommandServiceTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentMetricsTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileAssessmentResilienceTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationEndpointTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/ProfileReevaluationWorkerTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/profile/RestClientProfileAssessmentClientTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ProfileReevaluationPrivacyIntegrationTest.kt",
        "appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/profile/ProfileReevaluationEventServiceTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NotificationContractExceptionHandlerTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/notification/AppointmentNotificationWriterTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentNotificationAtomicityTest.kt",
        "appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepositoryTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/BookingReliabilityContractTest.kt",
        "appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/BookingReliabilityDocumentationTest.kt",
        "appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/BookingReliabilityReevaluationJobRepositoryTest.kt",
    )

    @Test
    fun `scheduling policy command는 nullable identifier를 명시적으로 검증한다`() {
        val source = source("policy/SchedulingPolicyCommandService.kt")

        source.contains("!!").shouldBeFalse()
    }

    @Test
    fun `profile reevaluation adapter는 coroutine thread를 block하지 않는다`() {
        val sources = listOf(
            source("profile/ProfileReevaluationEndpoint.kt"),
            source("profile/ProfileReevaluationHealthIndicator.kt"),
            source("config/ProfileReevaluationConfiguration.kt"),
        )

        sources.none { it.contains("runBlocking") }.shouldBeTrue()
    }

    @Test
    fun `reminder recovery cursor는 suspend 친화 lock과 IO boundary guard를 사용한다`() {
        val source = source("notification/JdbcAppointmentReminderRecoveryStore.kt")

        source.contains("synchronized(").shouldBeFalse()
        source.contains("Mutex(").shouldBeTrue()
        // The JDBC execution-thread test is authoritative for each materializer path;
        // keep this source check as a lightweight guard against removing the IO boundary entirely.
        source.contains("withContext(ioDispatcher)").shouldBeTrue()
    }

    @Test
    fun `Issue 209 touched tests use bluetape assertions and avoid unsafe chains`() {
        issue209TouchedTests
            .flatMap { path ->
                val source = projectSource(path)
                listOf(
                    path to source.contains("org.junit.jupiter.api.Assertions"),
                    path to source.contains("org.junit.jupiter.api.assertThrows"),
                    path to source.contains("kotlin.test.assertFailsWith"),
                    path to source.contains("assertThrows"),
                    path to source.contains("!!"),
                )
            }
            .forEach { (path, violation) ->
                violation.shouldBeFalse()
            }
    }

    private fun source(relativePath: String): String {
        val moduleRelative = Path.of("src/main/kotlin/io/bluetape4k/clinic/appointment/api", relativePath)
        val rootRelative = Path.of("appointment-api").resolve(moduleRelative)
        val path = listOf(moduleRelative, rootRelative).firstOrNull(Files::exists)
            ?: error("Production source not found: $relativePath")
        return Files.readString(path)
    }

    private fun projectSource(relativePath: String): String {
        val path = listOf(Path.of(relativePath), Path.of("..").resolve(relativePath))
            .firstOrNull(Files::exists)
            ?: error("Test source not found: $relativePath")
        return Files.readString(path)
    }
}
