package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class AppointmentCommandContextTest {
    @Test
    fun `root command uses correlation as causation`() {
        val context = AppointmentCommandContext.root("http-request-41")

        context.correlationId.value shouldBeEqualTo "http-request-41"
        context.causationId.value shouldBeEqualTo "http-request-41"
    }

    @Test
    fun `derived command preserves explicit upstream causation`() {
        val context = AppointmentCommandContext.derived(
            correlationId = "workflow-41",
            causationId = "appointment-created-41",
        )

        context.correlationId.value shouldBeEqualTo "workflow-41"
        context.causationId.value shouldBeEqualTo "appointment-created-41"
    }

    @Test
    fun `metadata rejects untrusted values`() {
        listOf("", " ", "line\nbreak", "alice@example.com", "raw value", "a".repeat(129)).forEach {
            assertFailsWith<IllegalArgumentException> { AppointmentCommandContext.root(it) }
        }
    }
}
