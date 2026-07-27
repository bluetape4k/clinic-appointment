package io.bluetape4k.clinic.appointment.api.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Stable privacy-safe error envelope for scheduling foundation APIs.
 *
 * @property success Always `false` for this envelope.
 * @property data Reserved compatibility field; always `null` for errors.
 * @property error Customer-safe message without parser, token, claim, payload,
 * stack, or internal identifier detail.
 * @property errorCode Stable machine-readable error code.
 * @property correlationId Bounded request trace ID established before security
 * and controller processing.
 * @property retryable Whether retrying the same intent without modification may
 * succeed. `false` is the compatibility default and is omitted from JSON so
 * existing error contracts keep their exact five-field shape. A future error
 * that is explicitly retryable serializes this field as `true`.
 * @property action Optional stable customer/operator action identifier. `null`
 * is omitted from JSON; free-form internal instructions or exception details
 * must not be exposed through this property.
 */
data class SchedulingApiErrorResponse(
    val success: Boolean = false,
    val data: Any? = null,
    val error: String,
    val errorCode: String,
    val correlationId: String,
    @field:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val retryable: Boolean = false,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val action: String? = null,
)
