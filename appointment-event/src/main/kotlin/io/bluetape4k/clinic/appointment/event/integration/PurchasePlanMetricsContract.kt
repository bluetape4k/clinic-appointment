package io.bluetape4k.clinic.appointment.event.integration

import java.io.Serializable

data class PurchasePlanMetricLabels(
    val values: Map<String, String>,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

enum class CardinalitySignal {
    OK,
    WARNING,
    HIGH,
}

/**
 * 구매에서 plan으로 이어지는 처리의 low-cardinality metric label 계약입니다.
 */
object PurchasePlanMetricsContract {
    const val SERIES_BUDGET = 1_000
    private const val WARNING_THRESHOLD = 800
    private const val HIGH_THRESHOLD = 950
    private const val MAX_LABEL_VALUE_LENGTH = 32

    private val allowedLabelNames = setOf(
        "result",
        "reason",
        "mode",
        "eventType",
        "producerClass",
        "tenantPartition",
        "clinicPartition",
    )
    private val forbiddenLabelNames = setOf(
        "eventId",
        "correlationId",
        "sourcePurchaseId",
        "planId",
        "patientReference",
        "ciphertext",
        "kid",
        "keyId",
    )
    private val safeLabelValue = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")
    private val sensitiveValueMarkers = listOf(
        "patient",
        "token",
        "cipher",
        "key",
        "purchase-",
        "event-",
        "correlation-",
    )

    fun labels(vararg labels: Pair<String, String>): PurchasePlanMetricLabels {
        require(labels.isNotEmpty()) { "labels must not be empty" }
        val values = labels.toMap()
        require(values.size == labels.size) { "duplicate metric label names are not allowed" }
        values.forEach { (name, value) ->
            require(name !in forbiddenLabelNames) { "metric label name is forbidden: $name" }
            require(name in allowedLabelNames) { "metric label name is not allowlisted: $name" }
            require(value.length in 1..MAX_LABEL_VALUE_LENGTH) { "metric label value length is invalid" }
            require(safeLabelValue.matches(value)) { "metric label value contains unsafe characters" }
            require(sensitiveValueMarkers.none { value.contains(it, ignoreCase = true) }) {
                "metric label value looks high-cardinality or sensitive"
            }
        }
        return PurchasePlanMetricLabels(values)
    }

    fun cardinalitySignal(seriesCount: Int): CardinalitySignal {
        require(seriesCount >= 0) { "seriesCount must not be negative" }
        return when {
            seriesCount >= HIGH_THRESHOLD -> CardinalitySignal.HIGH
            seriesCount >= WARNING_THRESHOLD -> CardinalitySignal.WARNING
            else -> CardinalitySignal.OK
        }
    }
}
