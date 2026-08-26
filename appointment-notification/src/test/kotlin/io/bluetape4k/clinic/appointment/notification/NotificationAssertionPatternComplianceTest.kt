package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** notification 테스트가 bluetape4k assertion vocabulary를 우회하지 않도록 source guard를 둔다. */
class NotificationAssertionPatternComplianceTest {

    @Test
    fun `모든 notification 테스트는 generic exception assertion을 사용하지 않는다`() {
        val forbiddenMarkers = listOf(
            "org.junit.jupiter.api.Assertions",
            "org.junit.jupiter.api.assertThrows",
            "kotlin.test.assertFailsWith",
            "assertThrows<",
        )
        val testRoot = listOf(
            Path.of("appointment-notification/src/test/kotlin"),
            Path.of("src/test/kotlin"),
        ).firstOrNull(Files::exists) ?: error("Notification test source root not found")

        val violations = Files.walk(testRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .filter { it.fileName.toString() != "NotificationAssertionPatternComplianceTest.kt" }
                .flatMap { path ->
                    val source = Files.readString(path)
                    forbiddenMarkers
                        .filter(source::contains)
                        .map { marker -> "$path: $marker" }
                        .stream()
                }
                .toList()
        }

        violations.shouldBeEmpty()
    }
}
