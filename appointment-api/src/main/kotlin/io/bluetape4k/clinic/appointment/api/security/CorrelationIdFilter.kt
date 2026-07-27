package io.bluetape4k.clinic.appointment.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Establishes one bounded correlation ID for the complete HTTP request.
 *
 * A caller value is preserved only when it contains 1..128 safe ASCII
 * characters. Missing, blank, oversized, or unsafe values are replaced by a
 * UUID and are never reflected. The chosen value is stored as a request
 * attribute and response header for controllers and error handlers.
 */
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val supplied = request.getHeader(HEADER_NAME)
        val correlationId = supplied
            ?.takeIf(CORRELATION_ID_REGEX::matches)
            ?: UUID.randomUUID().toString()
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId)
        response.setHeader(HEADER_NAME, correlationId)
        filterChain.doFilter(request, response)
    }

    companion object {
        /** Public HTTP request/response header carrying the bounded trace ID. */
        const val HEADER_NAME = "X-Correlation-Id"

        /** Servlet request attribute containing the already validated trace ID. */
        const val REQUEST_ATTRIBUTE =
            "io.bluetape4k.clinic.appointment.api.security.correlationId"

        private val CORRELATION_ID_REGEX = Regex("[A-Za-z0-9._:/-]{1,128}")

        /**
         * Returns the validated request correlation ID.
         *
         * @throws IllegalStateException when invoked before this filter.
         */
        fun requireCorrelationId(request: HttpServletRequest): String =
            request.getAttribute(REQUEST_ATTRIBUTE) as? String
                ?: error("Correlation ID has not been established")
    }
}
