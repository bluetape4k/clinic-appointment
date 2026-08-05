package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationError
import io.bluetape4k.clinic.appointment.api.security.SecurityErrorResponseWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT 인증보다 먼저 tenant path의 모호한 표현을 차단한다.
 *
 * Servlet container가 URI를 디코딩하거나 path parameter를 제거하는 과정에 의존하면 같은
 * 요청이 서로 다른 route로 매칭될 수 있다. 따라서 raw request URI와 servlet path/path-info
 * 조합에서 percent escape, path parameter, duplicate separator, traversal segment를 허용하지
 * 않고, `/api/{tenantCode}`의 첫 segment가 canonical tenant slug인지 확인한다. 이 filter는
 * tenant 저장소나 JWT claim을 읽지 않으며, 문법 오류는 항상 privacy-safe 404로 끝낸다.
 */
class TenantPathValidationFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val paths = request.pathRepresentations()
        val apiPaths = paths.filter(::isApiPath)
        if (apiPaths.any { !isValidApiRepresentation(it) }) {
            SecurityErrorResponseWriter.write(response, PlanFoundationError.RESOURCE_NOT_FOUND)
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun isApiPath(path: String): Boolean =
        path == API_ROOT || path.startsWith(API_PREFIX)

    private fun isValidApiRepresentation(path: String): Boolean {
        if (!isApiPath(path) || !path.startsWith(API_PREFIX)) {
            return false
        }
        if (path.contains('%') || path.contains(';') || path.contains('\\') || path.contains("//")) {
            return false
        }
        if (path.any(Char::isISOControl)) {
            return false
        }

        val segments = path.split('/')
        if (segments.any { it == "." || it == ".." }) {
            return false
        }
        return TenantCodeRules.isCanonical(segments[API_SEGMENT_INDEX])
    }

    private fun HttpServletRequest.pathRepresentations(): List<String> = buildList {
        val contextPath = contextPath.orEmpty()
        val requestPath = requestURI.removeContextPath(contextPath)
        add(requestPath)

        val servletPath = servletPath
        val pathInfo = pathInfo.orEmpty()
        if (servletPath.isNotBlank()) {
            add(servletPath + pathInfo)
            add(servletPath)
        }
        if (pathInfo.isNotBlank()) {
            add(pathInfo)
        }
    }.distinct().filter(String::isNotBlank)

    private fun String.removeContextPath(contextPath: String): String =
        if (contextPath.isNotBlank() && contextPath != "/" && startsWith(contextPath)) {
            removePrefix(contextPath)
        } else {
            this
        }

    private companion object {
        const val API_ROOT = "/api"
        const val API_PREFIX = "/api/"
        const val API_SEGMENT_INDEX = 2
    }
}
