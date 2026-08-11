package io.bluetape4k.clinic.appointment.event

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.event.profile.PatientSchedulingAssessmentChanged
import io.bluetape4k.clinic.appointment.event.profile.ProfileReevaluationEventResult
import io.bluetape4k.clinic.appointment.event.profile.ProfileReevaluationEventStatus
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isRegularFile

/**
 * event module의 data class가 broker·outbox·recovery 경계를 통과할 때 사용할
 * Java serialization 계약을 source-level로 고정합니다.
 *
 * `FulfillmentProjection`은 transaction 내부 계산용 projection이므로 의도적으로
 * durable 계약에서 제외합니다. 나머지 data class는 직접 `Serializable`을 구현하거나
 * 이미 직렬화 가능한 sealed/interface 계약을 상속하고, 명시적 UID를 가져야 합니다.
 */
class EventKotlinProductionPatternComplianceTest {

    @Test
    fun `모든 durable event data class는 Serializable과 serialVersionUID를 가진다`() {
        val sourceRoot = Path.of("src/main/kotlin")
        val serializableTypes = Files.walk(sourceRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .flatMap { path ->
                    Files.readAllLines(path).stream()
                        .filter { line ->
                            line.contains(": Serializable") &&
                                (line.contains("class ") || line.contains("interface "))
                        }
                        .map { line -> declarationName(line) }
                }
                .filter { it != null }
                .map { it!! }
                .toList()
        }.toSet()

        val violations = mutableListOf<String>()
        Files.walk(sourceRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .forEach { path ->
                    val source = Files.readString(path)
                    DATA_CLASS.findAll(source).forEach { match ->
                        val name = match.groupValues[1]
                        if (name in TRANSIENT_DATA_CLASSES) return@forEach

                        val bodyStart = source.indexOf('{', match.range.last)
                        if (bodyStart < 0) {
                            violations += "$path:$name missing class body"
                            return@forEach
                        }
                        val bodyEnd = matchingBrace(source, bodyStart)
                        val declaration = source.substring(match.range.first, bodyStart)
                        val body = source.substring(bodyStart, bodyEnd + 1)
                        val parent = PARENT.find(declaration)?.groupValues?.get(1)
                        val serializable = declaration.contains("Serializable") ||
                            parent in serializableTypes
                        if (!serializable || !body.contains("serialVersionUID")) {
                            violations +=
                                "$path:$name serializable=$serializable uid=${body.contains("serialVersionUID")}"
                        }
                    }
                }
        }

        violations.joinToString("\n").isEmpty().shouldBeTrue()
    }

    @Test
    fun `대표 profile event DTO는 Java serialization round trip을 보존한다`() {
        val event = PatientSchedulingAssessmentChanged(
            eventId = "profile-event-1",
            tenantGroupId = 7L,
            clinicId = 11L,
            patientReferenceFingerprint = "a".repeat(64),
            profileRevision = 4L,
            materialChange = true,
            assessmentRef = "assessment-4",
            assessmentHash = "b".repeat(64),
            occurredAt = Instant.parse("2026-08-11T00:00:00Z"),
        )
        val result = ProfileReevaluationEventResult(
            status = ProfileReevaluationEventStatus.PROCESSED,
            reasonCode = "PROFILE_CHANGED",
        )

        val restored = listOf(event, result).map { value ->
            val bytes = ByteArrayOutputStream().also { output ->
                ObjectOutputStream(output).use { it.writeObject(value) }
            }.toByteArray()
            ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
        }

        restored[0] shouldBeEqualTo event
        restored[1] shouldBeEqualTo result
    }

    private fun matchingBrace(source: String, openingBrace: Int): Int {
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return source.lastIndex
    }

    private fun declarationName(line: String): String? =
        Regex("(?:class|interface)\\s+(\\w+)").find(line)?.groupValues?.get(1)

    private companion object {
        val DATA_CLASS = Regex("(?m)^\\s*(?:private\\s+)?data class\\s+(\\w+)")
        val PARENT = Regex("\\)\\s*:\\s*([A-Za-z_][A-Za-z0-9_]*)")
        val TRANSIENT_DATA_CLASSES = setOf("FulfillmentProjection")
    }
}
