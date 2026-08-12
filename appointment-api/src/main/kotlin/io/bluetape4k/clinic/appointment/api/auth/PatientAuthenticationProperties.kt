package io.bluetape4k.clinic.appointment.api.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/** 환자 cookie/password 정책입니다. 모든 값은 bounded validation을 통과해야 합니다. */
@ConfigurationProperties(prefix = "scheduling.security.patient")
data class PatientAuthenticationProperties(
    val enabled: Boolean = true,
    val cookieName: String = "appointment_patient_session",
    val sessionTtl: Duration = Duration.ofHours(1),
    val cookieSecure: Boolean = true,
    val cookieSameSite: String = "Strict",
    val cookiePath: String = "/",
    val dummyPasswordHash: String = DEFAULT_DUMMY_PASSWORD_HASH,
    val minPasswordLength: Int = 12,
    val maxPasswordLength: Int = 128,
) : Serializable {
    init {
        require(cookieName.matches(COOKIE_NAME)) { "patient cookieName is invalid" }
        require(!sessionTtl.isNegative && !sessionTtl.isZero && sessionTtl <= Duration.ofHours(24)) {
            "patient sessionTtl must be between one second and twenty-four hours"
        }
        require(cookieSameSite in ALLOWED_SAME_SITE) { "patient cookieSameSite is invalid" }
        require(cookiePath.startsWith('/') && cookiePath.length <= 128) { "patient cookiePath is invalid" }
        require(dummyPasswordHash.length in 50..100) { "patient dummyPasswordHash is invalid" }
        require(minPasswordLength in 8..128 && maxPasswordLength in minPasswordLength..128) {
            "patient password bounds are invalid"
        }
    }

    companion object {
        private const val serialVersionUID = 1L
        private val COOKIE_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")
        private val ALLOWED_SAME_SITE = setOf("Strict", "Lax", "None")

        // BCrypt hash of a fixed dummy value; it is verified for every missing identity.
        private const val DEFAULT_DUMMY_PASSWORD_HASH =
            "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    }
}
