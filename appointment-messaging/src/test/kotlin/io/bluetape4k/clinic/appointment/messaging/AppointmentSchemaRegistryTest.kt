package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class AppointmentSchemaRegistryTest {

    @Test
    fun `static registry accepts current schema and reports ready`() {
        val registry = StaticAppointmentSchemaRegistry()

        registry.validate(AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION)
        registry.readiness().ready.shouldBeTrue()
        registry.readiness().compatibilityLevel shouldBeEqualTo "BACKWARD_TRANSITIVE"
    }

    @Test
    fun `static registry rejects unsupported schema version`() {
        val registry = StaticAppointmentSchemaRegistry()

        assertFailsWith<IllegalArgumentException> {
            registry.validate(AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION + 1)
        }
    }

    @Test
    fun `remote compatibility gate fails closed when registry is unavailable or incompatible`() {
        val unavailable = HttpAppointmentSchemaRegistry(
            subject = "appointment-events-value",
            compatibilityReader = { throw IllegalStateException("registry unavailable") },
        )
        val incompatible = HttpAppointmentSchemaRegistry(
            subject = "appointment-events-value",
            compatibilityReader = { "NONE" },
        )

        unavailable.readiness().ready.shouldBeFalse()
        incompatible.readiness().ready.shouldBeFalse()
        incompatible.readiness().subject shouldBeEqualTo "appointment-events-value"
    }

    @Test
    fun `consumer identity and replay request reject unbounded or unapproved input`() {
        assertFailsWith<IllegalArgumentException> { AppointmentLogicalConsumerId("") }
        assertFailsWith<IllegalArgumentException> { AppointmentLogicalStreamId(" ") }
        assertFailsWith<IllegalArgumentException> {
            AppointmentReplayRequest(
                identity = AppointmentConsumerIdentity(
                    consumerId = AppointmentLogicalConsumerId("notification"),
                    streamId = AppointmentLogicalStreamId("appointment-events"),
                ),
                approver = "",
                fromOffset = 0,
                toOffset = 1,
                dryRun = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentReplayRequest(
                identity = AppointmentConsumerIdentity(
                    consumerId = AppointmentLogicalConsumerId("notification"),
                    streamId = AppointmentLogicalStreamId("appointment-events"),
                ),
                approver = "operator",
                fromOffset = 2,
                toOffset = 1,
                dryRun = true,
            )
        }
    }
}
