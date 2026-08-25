package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** appointment-messaging 테스트 fixture와 bluetape4k assertion 계약을 고정한다. */
class AppointmentMessagingFixturePatternComplianceTest {
    @org.junit.jupiter.api.Test
    fun `일반 fixture와 outbox consumer writer 테스트는 표준 계약을 따른다`() {
        val testRoot = Path.of("src/test/kotlin")
        val directCreate = Regex("SchemaUtils\\.create\\s*\\(")
        val forbiddenAssertions = listOf(
            Regex("import\\s+kotlin\\.test\\.assert(?:Equals|True|False)"),
            Regex("import\\s+org\\.junit\\.jupiter\\.api\\.assertThrows"),
            Regex("\\bassert(?:Equals|True|False|Throws)\\s*\\("),
            Regex("\\bcheck\\s*\\("),
        )
        val directCreateExceptions = setOf("AppointmentOutboxPerformanceTestSupport.kt")
        val violations = Files.walk(testRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .filter { it.getFileName().toString() != "AppointmentMessagingFixturePatternComplianceTest.kt" }
                .flatMap { source ->
                    val text = Files.readString(source)
                    buildList {
                        if (directCreate.containsMatchIn(text) &&
                            source.getFileName().toString() !in directCreateExceptions
                        ) {
                            add("직접 schema create: $source")
                        }
                        if (text.contains("SchemaUtils.createMissingTablesAndColumns") &&
                            !text.contains("deleteAll()")
                        ) {
                            add("fixture reset 누락: $source")
                        }
                        forbiddenAssertions.forEach { pattern ->
                            if (pattern.containsMatchIn(text)) add("일반 assertion/check: $source")
                        }
                    }.stream()
                }
                .toList()
        }

        violations.isEmpty().shouldBeTrue()
    }
}
