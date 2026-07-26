package io.bluetape4k.clinic.appointment.api.dto

data class SchedulingApiErrorResponse(
    val success: Boolean = false,
    val data: Nothing? = null,
    val error: String,
    val errorCode: String,
    val correlationId: String,
)
