package io.bluetape4k.clinic.appointment.api.config

/**
 * Sanitized application exception for a scheduling-policy command rejection.
 *
 * [detail] may be logged or returned to an authenticated caller and must
 * therefore be a bounded domain explanation only. It must never include raw
 * JSON, an idempotency key or digest, JWT data, actor claims, SQL text, or a
 * caught exception message. The stable machine contract is [errorCode].
 *
 * @property errorCode Stable public policy error.
 * @property detail Customer-safe explanation of this occurrence.
 */
class SchedulingPolicyApiException(
    val errorCode: SchedulingPolicyErrorCode,
    val detail: String,
) : RuntimeException(detail) {
    init {
        require(detail.isNotBlank() && detail.length <= 500) {
            "detail must contain 1..500 customer-safe characters"
        }
    }
}
