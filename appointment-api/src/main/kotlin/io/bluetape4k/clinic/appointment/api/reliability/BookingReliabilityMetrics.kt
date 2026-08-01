package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration

/** 회원 식별자를 tag로 사용하지 않는 저카디널리티 reliability metric입니다. */
class BookingReliabilityMetrics(
    private val registry: MeterRegistry,
) {
    fun recordDecision(
        mode: BookingReliabilityProperties.Mode,
        verdict: BookingReliabilityVerdict,
        reasonCodes: Set<BookingReliabilityReasonCode>,
        duration: Duration,
    ) {
        Counter.builder(DECISIONS)
            .tags("mode", mode.name.lowercase(), "verdict", verdict.name.lowercase())
            .register(registry)
            .increment()
        reasonCodes.forEach { reason ->
            Counter.builder(REASONS)
                .tag("reason", reason.name.lowercase())
                .register(registry)
                .increment()
        }
        Timer.builder(LATENCY)
            .publishPercentileHistogram()
            .tag("mode", mode.name.lowercase())
            .register(registry)
            .record(duration)
    }

    fun recordEvent(result: EventResult) =
        Counter.builder(EVENTS).tag("result", result.name.lowercase()).register(registry).increment()

    fun recordJob(result: JobResult) =
        Counter.builder(JOBS).tag("result", result.name.lowercase()).register(registry).increment()

    enum class EventResult { ACCEPTED, DUPLICATE, REJECTED, STALE, QUARANTINED }
    enum class JobResult { CLAIMED, COMPLETED, RETRY, DEAD_LETTER, LEASE_LOST }

    companion object {
        const val DECISIONS = "clinic.booking.reliability.decisions"
        const val REASONS = "clinic.booking.reliability.reasons"
        const val LATENCY = "clinic.booking.reliability.latency"
        const val EVENTS = "clinic.booking.reliability.events"
        const val JOBS = "clinic.booking.reliability.jobs"
    }
}
