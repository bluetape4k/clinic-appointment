package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * API가 실제 호출하지 않는 solver/Timefold 공개 의존성을 다시 노출하지 않는지 검증한다.
 *
 * API의 배포 경계는 `appointment-core`, `appointment-event`,
 * `appointment-notification`, `appointment-messaging`으로 충분하다. 이 계약은
 * build script와 dependency lock을 함께 읽어 compile/runtime metadata가 같은 경계를
 * 가리키도록 고정한다.
 */
class ApiDependencyBoundaryContractTest {

    @Test
    fun `api build and lock metadata do not publish solver or timefold`() {
        val buildScript = read("appointment-api/build.gradle.kts")
        val lockfile = read("appointment-api/gradle.lockfile")

        buildScript.contains(":appointment-solver") shouldBeEqualTo false

        val runtimeTimefoldLocks = lockfile.lineSequence()
            .filter { it.startsWith("ai.timefold.solver:timefold-solver-") }
            .filter { line ->
                line.substringAfter('=', "")
                    .split(',')
                    .any { scope -> scope in RUNTIME_SCOPES }
            }
            .toList()

        runtimeTimefoldLocks shouldBeEqualTo emptyList()
    }

    @Test
    fun `reader facing dependency inventory matches the application boundary`() {
        val readmes = listOf(read("appointment-api/README.md"), read("appointment-api/README.ko.md"))

        readmes.map { readme ->
            readme.lineSequence()
                .firstOrNull { it.startsWith("- **내부**:") }
                ?.contains("appointment-solver") == true
        }.shouldBeEqualTo(listOf(false, false))
    }

    @Test
    fun `api notification consumers depend on the event writer port`() {
        val serviceConfig = read("appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt")
        val appointmentWriter = read("appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/AppointmentNotificationWriter.kt")
        val recoveryStore = read("appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/JdbcAppointmentReminderRecoveryStore.kt")

        serviceConfig.contains("JdbcNotificationOutboxRepository").shouldBeFalse()
        serviceConfig.contains("NotificationOutboxCodec").shouldBeFalse()
        appointmentWriter.contains("NotificationOutboxWriter").shouldBeTrue()
        appointmentWriter.contains("JdbcNotificationOutboxRepository").shouldBeFalse()
        recoveryStore.contains("NotificationOutboxWriter").shouldBeTrue()
        recoveryStore.contains("JdbcNotificationOutboxRepository").shouldBeFalse()
    }

    private fun read(relativePath: String): String =
        listOf(Path.of(relativePath), Path.of("../$relativePath"))
            .firstOrNull(Files::exists)
            ?.let(Files::readString)
            ?: error("파일을 찾을 수 없습니다: $relativePath")

    private companion object {
        val RUNTIME_SCOPES = setOf("productionRuntimeClasspath", "runtimeClasspath", "testRuntimeClasspath")
    }
}
