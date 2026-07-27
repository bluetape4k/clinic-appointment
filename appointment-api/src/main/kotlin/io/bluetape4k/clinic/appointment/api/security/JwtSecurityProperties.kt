package io.bluetape4k.clinic.appointment.api.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * Gateway JWT를 엄격하게 검증하기 위한 설정이다.
 *
 * issuer와 audience는 token을 신뢰된 authentication service와 이 API에 묶는다.
 * clock skew는 JWT validity와 authentication timestamp 검증에만 적용되는 유일한 허용 오차이며,
 * 예약 명령 처리의 grace period가 아니다. issuer, audience, skew, signing-key 설정이
 * 잘못되면 principal을 만들기 전에 fail-closed로 bean 구성이 실패한다.
 *
 * @property enabled protected profile에서 사용하는 호환성 guard. security 활성화 여부는
 * [SecurityConfig]의 Spring profile이 소유한다. `false`는 protected endpoint를 공개하지 못하며,
 * production-style bean construction을 실패시킨다.
 * @property secret 최소 256 bit의 Base64-encoded HMAC secret. decode된 값은 로그나 응답에
 * 절대 남기지 않는다. blank, malformed, undersized 값은 [JwtTokenParser] 생성 시 거절된다.
 * @property issuer 신뢰하는 정확한 `iss` 값.
 * @property audience `aud`에 반드시 포함되어야 하는 정확한 service audience.
 * @property allowedClockSkew expiration/not-before 검증과 manual issued/authentication-time
 * 검사에 적용되는 최대 non-negative 허용 오차. 5분을 초과하는 duration은 unsafe configuration으로 거절한다.
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
