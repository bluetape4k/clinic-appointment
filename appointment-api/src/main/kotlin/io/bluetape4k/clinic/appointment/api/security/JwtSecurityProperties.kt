package io.bluetape4k.clinic.appointment.api.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable

/**
 * JWT 보안 설정 프로퍼티.
 *
 * ```yaml
 * scheduling:
 *   security:
 *     jwt:
 *       enabled: true
 *       secret: base64-encoded-secret-key-at-least-256-bits
 *       issuer: appointment-auth-service
 * ```
 *
 * @property enabled JWT 인증 활성화 여부
 * @property secret JWT 서명 검증용 Base64 인코딩 비밀키
 * @property issuer 허용할 JWT issuer
 */
@ConfigurationProperties(prefix = "scheduling.security.jwt")
data class JwtSecurityProperties(
    val enabled: Boolean = true,
    val secret: String = "",
    val issuer: String = "appointment-auth-service",
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
