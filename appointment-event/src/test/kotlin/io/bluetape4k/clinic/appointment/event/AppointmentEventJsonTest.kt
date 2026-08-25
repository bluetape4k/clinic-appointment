package io.bluetape4k.clinic.appointment.event

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.core.exc.StreamReadException

class AppointmentEventJsonTest {

    @Test
    fun `shared mapper serializes map entries in canonical key order`() {
        AppointmentEventJson.mapper.writeValueAsString(mapOf("z" to 1, "a" to 2)) shouldBeEqualTo
            "{\"a\":2,\"z\":1}"
    }

    @Test
    fun `shared mapper rejects duplicate tree keys`() {
        assertFailsWith<StreamReadException> {
            AppointmentEventJson.mapper.readTree("{\"a\":1,\"a\":2}")
        }
    }

    @Test
    fun `shared mapper rejects an oversized field name`() {
        assertFailsWith<StreamConstraintsException> {
            AppointmentEventJson.mapper.readTree("{\"${"x".repeat(4_097)}\":1}")
        }
    }

    @Test
    fun `shared writer rejects an oversized UTF-8 document`() {
        assertFailsWith<IllegalArgumentException> {
            AppointmentEventJson.writeCanonical(
                mapOf("payload" to "가".repeat(AppointmentEventJson.MAX_DOCUMENT_BYTES)),
            )
        }
    }
}
