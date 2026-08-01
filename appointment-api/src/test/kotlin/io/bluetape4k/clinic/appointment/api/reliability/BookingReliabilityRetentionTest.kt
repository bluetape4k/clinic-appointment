package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Instant

class BookingReliabilityRetentionTest {
    @Test
    fun `legal hold makes retention idempotently skip deletion`() {
        var executions = 0
        val service = BookingReliabilityRetentionService(BookingReliabilityRetentionExecutor { executions++ ; 10 })
        val request = BookingReliabilityRetentionRequest(1, 2, Instant.EPOCH, "STANDARD", legalHold = true)

        val result = service.execute(request)

        result.skipped shouldBeEqualTo true
        result.reason shouldBeEqualTo "LEGAL_HOLD"
        executions shouldBeEqualTo 0
    }
}
