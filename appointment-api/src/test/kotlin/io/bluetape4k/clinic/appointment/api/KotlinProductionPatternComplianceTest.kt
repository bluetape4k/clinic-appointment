package io.bluetape4k.clinic.appointment.api

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class KotlinProductionPatternComplianceTest {

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
    fun `reminder recovery cursor는 suspend 친화 lock과 IO boundary를 사용한다`() {
        val source = source("notification/JdbcAppointmentReminderRecoveryStore.kt")

        source.contains("synchronized(").shouldBeFalse()
        source.contains("Mutex(").shouldBeTrue()
        source.contains("withContext(ioDispatcher)").shouldBeTrue()
    }

    private fun source(relativePath: String): String {
        val moduleRelative = Path.of("src/main/kotlin/io/bluetape4k/clinic/appointment/api", relativePath)
        val rootRelative = Path.of("appointment-api").resolve(moduleRelative)
        val path = listOf(moduleRelative, rootRelative).firstOrNull(Files::exists)
            ?: error("Production source not found: $relativePath")
        return Files.readString(path)
    }
}
