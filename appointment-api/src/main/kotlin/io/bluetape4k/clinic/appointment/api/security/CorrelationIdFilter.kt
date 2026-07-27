package io.bluetape4k.clinic.appointment.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * HTTP 요청 전체에서 사용할 길이 제한 correlation ID 하나를 수립한다.
 *
 * caller가 보낸 값은 1..128자의 safe ASCII인 경우에만 보존한다. 값이 없거나 blank,
 * oversize, unsafe이면 UUID로 대체하고 원본 값을 반사하지 않는다. 선택된 값은 controller와
 * error handler가 사용할 수 있도록 request attribute 및 response header에 저장한다.
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
        /** 길이가 제한된 trace ID를 전달하는 공개 HTTP request/response header. */
        const val HEADER_NAME = "X-Correlation-Id"

        /** 이미 검증된 trace ID를 담는 Servlet request attribute. */
        const val REQUEST_ATTRIBUTE =
            "io.bluetape4k.clinic.appointment.api.security.correlationId"

        private val CORRELATION_ID_REGEX = Regex("[A-Za-z0-9._:/-]{1,128}")

        /**
         * 검증된 request correlation ID를 반환한다.
         *
         * @throws IllegalStateException 이 filter가 실행되기 전에 호출된 경우.
         */
        fun requireCorrelationId(request: HttpServletRequest): String =
            request.getAttribute(REQUEST_ATTRIBUTE) as? String
                ?: error("Correlation ID has not been established")
    }
}
