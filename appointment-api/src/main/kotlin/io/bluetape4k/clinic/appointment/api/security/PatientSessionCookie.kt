package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.api.auth.PatientAuthenticationProperties
import org.springframework.http.ResponseCookie
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 환자 JWT를 브라우저에 직접 노출하지 않고 HttpOnly cookie로 전달하는 포맷터입니다. */
class PatientSessionCookie(
    private val properties: PatientAuthenticationProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** bounded JWT lifetime을 cookie Max-Age로 보존합니다. */
    fun issue(token: String, expiresAt: Instant): String {
        require(token.isNotBlank()) { "patient session token must not be blank" }
        val maxAge = Duration.between(Instant.now(clock), expiresAt)
            .seconds
            .coerceIn(1L, properties.sessionTtl.seconds)
        return base(token)
            .maxAge(Duration.ofSeconds(maxAge))
            .build()
            .toString()
    }

    /** logout/stale session에서 host-only cookie를 즉시 제거합니다. */
    fun delete(): String = base("")
        .maxAge(Duration.ZERO)
        .build()
        .toString()

    private fun base(value: String): ResponseCookie.ResponseCookieBuilder =
        ResponseCookie.from(properties.cookieName, value)
            .httpOnly(true)
            .secure(properties.cookieSecure)
            .sameSite(properties.cookieSameSite)
            .path(properties.cookiePath)
}
