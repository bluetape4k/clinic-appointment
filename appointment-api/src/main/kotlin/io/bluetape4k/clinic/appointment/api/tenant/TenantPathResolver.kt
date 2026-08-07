package io.bluetape4k.clinic.appointment.api.tenant

import jakarta.servlet.http.HttpServletRequest

/**
 * API 경로에서 URL tenant code를 추출합니다.
 */
object TenantPathResolver {
    private const val API_PREFIX = "/api/"

    fun resolve(request: HttpServletRequest): String? {
        val path = request.servletPath
            .plus(request.pathInfo.orEmpty())
            .ifBlank { request.requestURI.removePrefix(request.contextPath.orEmpty()) }

        if (!path.startsWith(API_PREFIX)) {
            return null
        }

        val rest = path.substring(API_PREFIX.length)
        return rest.substringBefore('/')
            .takeIf(TenantCodeRules::isCanonical)
    }
}
