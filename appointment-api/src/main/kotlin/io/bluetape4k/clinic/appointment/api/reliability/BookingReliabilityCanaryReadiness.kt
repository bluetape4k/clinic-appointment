package io.bluetape4k.clinic.appointment.api.reliability

import java.time.Duration

/** ENFORCE 승격 전에 필요한 최소 관측 증거입니다. */
data class BookingReliabilityCanaryEvidence(
    val observation: Duration,
    val decisions: Long,
    val p95LatencyMillis: Long,
    val p99LatencyMillis: Long,
    val duplicateDecisions: Long,
    val unavailableBacklog: Long,
    val attributionMissingRatio: Double,
    val rawPiiFindings: Long,
    val closedMetricTags: Boolean,
) {
    init {
        require(!observation.isNegative) { "observation must be non-negative" }
        require(decisions >= 0 && p95LatencyMillis >= 0 && p99LatencyMillis >= 0)
        require(duplicateDecisions >= 0 && unavailableBacklog >= 0 && rawPiiFindings >= 0)
        require(attributionMissingRatio in 0.0..1.0) { "attributionMissingRatio must be in 0..1" }
    }
}

class BookingReliabilityCanaryReadiness(
    private val maximumP95Millis: Long = 250,
    private val maximumP99Millis: Long = 500,
    private val maximumAttributionMissingRatio: Double = 0.01,
) {
    init {
        require(maximumP95Millis > 0 && maximumP99Millis >= maximumP95Millis)
        require(maximumAttributionMissingRatio in 0.0..1.0)
    }

    fun ready(evidence: BookingReliabilityCanaryEvidence): Boolean =
        evidence.observation >= Duration.ofHours(24) &&
            evidence.decisions >= 1_000 &&
            evidence.p95LatencyMillis <= maximumP95Millis &&
            evidence.p99LatencyMillis <= maximumP99Millis &&
            evidence.duplicateDecisions == 0L &&
            evidence.unavailableBacklog == 0L &&
            evidence.attributionMissingRatio < maximumAttributionMissingRatio &&
            evidence.rawPiiFindings == 0L &&
            evidence.closedMetricTags
}
