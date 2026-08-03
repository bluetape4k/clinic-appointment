package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WaitlistRetentionRunnerTest {
    @Test
    fun `retention is bounded per kind and isolates one failed purge`() {
        val requests = mutableListOf<WaitlistRetentionRequest>()
        val runner = WaitlistRetentionRunner(
            store = WaitlistRetentionStore { request ->
                requests += request
                if (request.kind == WaitlistRetentionKind.AUDIT_EVENT) {
                    error("temporary database failure")
                }
                WaitlistRetentionResult(request.kind, deleted = 1)
            },
            properties = WaitlistDeliveryProperties(retentionBatchSize = 100),
            clock = Clock.fixed(Instant.parse("2026-08-03T10:00:00Z"), ZoneOffset.UTC),
        )

        val result = runner.run()

        result.results.size shouldBeEqualTo WaitlistRetentionKind.entries.size - 1
        result.failures.map { it.kind } shouldBeEqualTo listOf(WaitlistRetentionKind.AUDIT_EVENT)
        requests.all { it.limit == 100 } shouldBeEqualTo true
    }
}
