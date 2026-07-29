package io.bluetape4k.clinic.appointment.api.tenant

import jakarta.servlet.http.HttpServletRequest

/**
 * Extracts URL tenant codes from API paths.
 */
object TenantPathResolver {
    private const val API_PREFIX = "/api/"
    private val RESERVED_ROOT_SEGMENTS = setOf("v2")

    fun resolve(request: HttpServletRequest): String? {
        val path = request.servletPath
            .ifBlank { request.requestURI.removePrefix(request.contextPath.orEmpty()) }

        if (!path.startsWith(API_PREFIX)) {
            return null
        }

        val rest = path.substring(API_PREFIX.length)
        return rest.substringBefore('/')
            .takeIf { it.isNotBlank() && it !in RESERVED_ROOT_SEGMENTS }
    }
}
