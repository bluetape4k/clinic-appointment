package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationError
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID

/**
 * Writes the stable, privacy-safe JSON contract used when authentication or
 * authorization fails before a controller can handle the request.
 *
 * Security failures deliberately expose only the public [PlanFoundationError]
 * code and message. Raw JWTs, claim values, parser exception text, signing
 * details, and authorization rules must never be copied into this response.
 *
 * The correlation identifier is established by [CorrelationIdFilter]. The
 * header and JSON body must contain exactly the same value so an operator can
 * join a customer's error report to server-side audit records without using
 * sensitive authentication material. The UUID fallback exists only for direct
 * invocations outside the normal filter chain; when used, it is also written
 * to the response header to preserve that invariant.
 */
object SecurityErrorResponseWriter {
    /**
     * Writes one terminal security error response.
     *
     * This method owns response serialization but does not authenticate,
     * authorize, log, or retry the request. [error] must therefore contain an
     * already classified public error contract, never an exception message.
     *
     * @param response servlet response whose status, media type, correlation
     * header, and JSON body are completed by this call
     * @param error public error classification with a non-sensitive message
     */
    fun write(response: HttpServletResponse, error: PlanFoundationError) {
        val correlationId = response.getHeader(CorrelationIdFilter.HEADER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also {
                response.setHeader(CorrelationIdFilter.HEADER_NAME, it)
            }

        response.status = error.status.value()
        response.contentType = "application/json"
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"success":false,"data":null,"error":"${escapeJson(error.safeMessage)}","errorCode":"${escapeJson(error.code)}","correlationId":"${escapeJson(correlationId)}"}"""
        )
    }

    /**
     * Escapes a bounded public response value for a JSON string literal.
     *
     * This is intentionally a serializer-local helper rather than a general
     * JSON API. It prevents future public error text from breaking the response
     * shape or injecting additional fields. Authentication inputs must never be
     * passed to this method.
     */
    private fun escapeJson(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}
