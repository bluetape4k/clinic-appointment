package io.bluetape4k.clinic.appointment.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * Capacitor/browser cross-origin API 전송 정책입니다.
 *
 * 기본값은 CORS를 끄므로 기존 same-origin 배포를 바꾸지 않습니다. 활성화할 때는
 * patient cookie credentials를 보낼 수 있는 유한한 origin을 명시해야 하며 wildcard는
 * 허용하지 않습니다.
 */
@ConfigurationProperties(prefix = "scheduling.security.cors")
data class ApiCorsProperties(
    val enabled: Boolean = false,
    val allowedOrigins: List<String> = emptyList(),
    val allowedMethods: List<String> = DEFAULT_ALLOWED_METHODS,
    val allowedHeaders: List<String> = DEFAULT_ALLOWED_HEADERS,
    val exposedHeaders: List<String> = DEFAULT_EXPOSED_HEADERS,
    val allowCredentials: Boolean = true,
    val maxAge: Duration = Duration.ofMinutes(30),
) {
    init {
        if (enabled) {
            require(allowedOrigins.isNotEmpty()) {
                "scheduling.security.cors.allowed-origins must not be empty when CORS is enabled"
            }
            require(allowCredentials) {
                "scheduling.security.cors.allow-credentials must be true for patient cookie requests"
            }
        }
        require(allowedOrigins.none { it.contains('*') }) {
            "scheduling.security.cors.allowed-origins must not contain wildcard origins"
        }
        allowedOrigins.forEach(::validateOrigin)
        require(allowedMethods.isNotEmpty() && allowedMethods.all(String::isNotBlank)) {
            "scheduling.security.cors.allowed-methods must contain non-blank values"
        }
        require(allowedHeaders.isNotEmpty() && allowedHeaders.all(String::isNotBlank)) {
            "scheduling.security.cors.allowed-headers must contain non-blank values"
        }
        require(exposedHeaders.all(String::isNotBlank)) {
            "scheduling.security.cors.exposed-headers must contain non-blank values"
        }
        require(!maxAge.isNegative) {
            "scheduling.security.cors.max-age must not be negative"
        }
    }

    private fun validateOrigin(origin: String) {
        require(origin.isNotBlank()) {
            "scheduling.security.cors.allowed-origins must not contain blank values"
        }
        val parsed = runCatching { URI(origin) }.getOrElse { failure ->
            throw IllegalArgumentException("CORS origin must be a valid absolute origin: $origin", failure)
        }
        require(parsed.isAbsolute && parsed.host != null) {
            "CORS origin must be an absolute origin: $origin"
        }
        require(parsed.scheme == "https" || parsed.scheme == "http") {
            "CORS origin must use HTTP or HTTPS: $origin"
        }
        require(parsed.userInfo == null && parsed.path.isEmpty() && parsed.query == null && parsed.fragment == null) {
            "CORS origin must not contain credentials, path, query, or fragment: $origin"
        }
        if (parsed.scheme == "http") {
            require(parsed.host in LOCAL_DEVELOPMENT_HOSTS) {
                "HTTP CORS origins are limited to localhost development hosts: $origin"
            }
        }
    }

    companion object {
        val DEFAULT_ALLOWED_METHODS: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        val DEFAULT_ALLOWED_HEADERS: List<String> = listOf(
            "Content-Type",
            "Accept",
            "Authorization",
            "X-XSRF-TOKEN",
            "Idempotency-Key",
            "If-None-Match",
            "If-Match",
            "ngsw-bypass",
            "Cache-Control",
            "Pragma",
        )
        val DEFAULT_EXPOSED_HEADERS: List<String> = listOf(
            "ETag",
            "Retry-After",
            "X-Correlation-Id",
            "X-Tenant-Identity-Generation",
        )

        private val LOCAL_DEVELOPMENT_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    }
}
