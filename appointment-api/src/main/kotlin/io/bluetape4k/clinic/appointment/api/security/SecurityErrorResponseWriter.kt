package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationError
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID

object SecurityErrorResponseWriter {
    fun write(response: HttpServletResponse, error: PlanFoundationError) {
        response.status = error.status.value()
        response.contentType = "application/json"
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"success":false,"data":null,"error":"${error.safeMessage}","errorCode":"${error.code}","correlationId":"${UUID.randomUUID()}"}"""
        )
    }
}
