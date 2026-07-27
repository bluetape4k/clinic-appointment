package io.bluetape4k.clinic.appointment.api.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * Strict Gateway JWT verification configuration.
 *
 * Issuer and audience bind a token to the trusted authentication service and
 * this API. Clock skew is the only tolerance applied to JWT validity and
 * authentication timestamps; it is not a grace period for appointment
 * commands. Invalid issuer, audience, skew, or signing-key configuration fails
 * closed before a principal can be established.
 *
 * @property enabled Compatibility guard for protected profiles. Security
 * activation is owned by Spring profiles in [SecurityConfig]; `false` cannot
 * make protected endpoints public and instead fails production-style bean
 * construction.
 * @property secret Base64-encoded HMAC secret with at least 256 bits. The
 * decoded value is never logged or returned. Blank, malformed, or undersized
 * values are rejected when [JwtTokenParser] is constructed.
 * @property issuer Exact trusted `iss` value.
 * @property audience Exact service audience required in `aud`.
 * @property allowedClockSkew Maximum non-negative tolerance applied to
 * expiration/not-before validation and manual issued/authentication-time
 * checks. Durations over five minutes are rejected as unsafe configuration.
 */
@ConfigurationProperties(prefix = "scheduling.security.jwt")
data class JwtSecurityProperties(
    val enabled: Boolean = true,
    val secret: String = "",
    val issuer: String = "appointment-auth-service",
    val audience: String = "appointment-api",
    val allowedClockSkew: Duration = Duration.ofSeconds(30),
) : Serializable {
    init {
        require(issuer.isNotBlank()) { "JWT issuer must not be blank" }
        require(audience.isNotBlank()) { "JWT audience must not be blank" }
        require(!allowedClockSkew.isNegative && allowedClockSkew <= Duration.ofMinutes(5)) {
            "JWT allowedClockSkew must be between zero and five minutes"
        }
    }

    companion object {
        private const val serialVersionUID = 2L
    }
}
