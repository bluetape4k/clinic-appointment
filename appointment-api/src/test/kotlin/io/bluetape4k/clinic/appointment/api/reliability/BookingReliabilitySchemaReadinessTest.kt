package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class BookingReliabilitySchemaReadinessTest {

    @Test
    fun `later flyway migrations keep reliability readiness current`() {
        val readiness = BookingReliabilitySchemaReadiness(
            migrationVersion = 18,
            requiredTablesPresent = true,
            requiredIndexesPresent = true,
            migrationCurrent = true,
        )

        readiness.ready.shouldBeTrue()
        readiness.allows(BookingReliabilityProperties.Mode.ENFORCE).shouldBeTrue()
    }
}
