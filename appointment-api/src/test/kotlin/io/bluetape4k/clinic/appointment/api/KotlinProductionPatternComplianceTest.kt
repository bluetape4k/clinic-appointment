package io.bluetape4k.clinic.appointment.api

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEmpty
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
// JDBC 실행 스레드 테스트가 각 materializer 경로의 기준 검증이다.
// 이 source check는 IO 경계가 완전히 제거되는 것을 막는 가벼운 guard로 유지한다.
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

    @Test
    fun `all API production sources avoid unsafe non null assertions`() {
        val violations = productionSources()
            .filter { (_, source) -> source.contains("!!") }
            .map { (path, _) -> path.toString() }

        violations.shouldBeEmpty()
    }

    @Test
    fun `all API test sources use bluetape assertion helpers`() {
        val genericAssertionImports = Regex(
            """(?m)^import (org\.junit\.jupiter\.api\.Assertions(?:\.\*)?|org\.junit\.jupiter\.api\.assert[A-Z]\w*|kotlin\.test\.assert[A-Z]\w*)""",
        )
        val violations = testSources()
            .filterNot { (path, _) -> path.fileName.toString() == "KotlinProductionPatternComplianceTest.kt" }
            .filter { (_, source) ->
                genericAssertionImports.containsMatchIn(source)
            }
            .map { (path, _) -> path.toString() }

        violations.shouldBeEmpty()
    }

    @Test
    fun `all API test sources use migration safe schema creation`() {
        val violations = testSources()
            .filterNot { (path, _) -> path.fileName.toString() == "KotlinProductionPatternComplianceTest.kt" }
            .filter { (_, source) -> source.contains("SchemaUtils.create(") }
            .map { (path, _) -> path.toString() }

        violations.shouldBeEmpty()
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

    private fun productionSources(): List<Pair<Path, String>> {
        val root = listOf(
            Path.of("appointment-api/src/main/kotlin"),
            Path.of("src/main/kotlin"),
        ).firstOrNull(Files::exists) ?: error("API production source root not found")
        val sources = mutableListOf<Pair<Path, String>>()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { path -> sources += path to Files.readString(path) }
        }
        return sources
    }

    private fun testSources(): List<Pair<Path, String>> {
        val root = listOf(
            Path.of("appointment-api/src/test/kotlin"),
            Path.of("src/test/kotlin"),
        ).firstOrNull(Files::exists) ?: error("API test source root not found")
        val sources = mutableListOf<Pair<Path, String>>()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { path -> sources += path to Files.readString(path) }
        }
        return sources
    }
}
