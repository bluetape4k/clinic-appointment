package io.bluetape4k.clinic.appointment.event

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** 일반 event fixture가 schema를 매번 파괴하지 않는다는 규칙을 고정합니다. */
class EventFixturePatternComplianceTest {

    @Test
    fun `일반 fixture는 incremental schema와 reverse deleteAll을 사용한다`() {
        val testRoot = Path.of("src/test/kotlin")
        val directCreate = Regex("SchemaUtils\\." + "create\\(")
        val violations = Files.walk(testRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .filter { it.fileName.toString() != "EventFixturePatternComplianceTest.kt" }
                .flatMap { path ->
                    val source = Files.readString(path)
                    val findings = buildList {
                        if (directCreate.containsMatchIn(source)) {
                            add("$path uses SchemaUtils.create")
                        }
                        if (source.contains("SchemaUtils.createMissingTablesAndColumns") &&
                            !source.contains("deleteAll()")
                        ) {
                            add("$path has no deleteAll cleanup")
                        }
                    }
                    findings.stream()
                }
                .toList()
        }

        violations.isEmpty().shouldBeTrue()
    }
}
