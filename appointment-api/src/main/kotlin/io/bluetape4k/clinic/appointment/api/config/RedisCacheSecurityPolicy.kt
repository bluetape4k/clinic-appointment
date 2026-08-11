package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.support.requireNotBlank
import java.net.URI

/**
 * Redis client가 운영 환경의 TLS와 ACL 경계를 넘기 전에 URL을 검증합니다.
 *
 * [requireTls]가 false이면 local/test fallback을 보존하고 URI를 그대로 반환합니다.
 * true이면 loopback이나 credential이 없는 endpoint를 허용하지 않습니다. 예외 메시지는
 * secret을 포함하지 않아 시작 실패를 안전하게 진단할 수 있습니다.
 */
class RedisCacheSecurityPolicy {

    /**
     * Redis URL을 검증하고 client 생성에 사용할 URI를 반환합니다.
     *
     * @param url Spring이 전달한 Redis URI입니다.
     * @param requireTls 운영 TLS 검증을 활성화할지 여부입니다.
     * @return 검증된 Redis URI입니다.
     * @throws IllegalArgumentException URL이 비어 있거나 보안 조건을 만족하지 않을 때
     */
    fun validate(url: String, requireTls: Boolean): URI {
        val rawUrl = url.requireNotBlank("redisUrl")
        val uri = runCatching { URI.create(rawUrl) }
            .getOrElse { cause ->
                throw IllegalArgumentException("Redis URL is invalid", cause)
            }

        if (!requireTls) {
            return uri
        }

        require(uri.scheme.equals("rediss", ignoreCase = true)) {
            "Redis TLS requires the rediss scheme"
        }

        val host = uri.host
            ?.trim()
            ?.trim('[', ']')
            ?.requireNotBlank("redisHost")
            ?: throw IllegalArgumentException("Redis TLS requires a host")
        require(!host.isLoopbackLiteral()) {
            "Redis TLS does not allow a loopback host"
        }

        val userInfo = uri.userInfo
            ?.requireNotBlank("redisUserInfo")
            ?: throw IllegalArgumentException("Redis TLS requires ACL username and password")
        val separator = userInfo.indexOf(':')
        require(separator > 0 && separator < userInfo.lastIndex) {
            "Redis TLS requires ACL username and password"
        }
        val username = userInfo.substring(0, separator)
        val password = userInfo.substring(separator + 1)
        require(username.isNotBlank() && password.isNotBlank()) {
            "Redis TLS requires ACL username and password"
        }

        return uri
    }

    private fun String.isLoopbackLiteral(): Boolean {
        val normalized = removePrefix("[").removeSuffix("]").lowercase()
        return normalized == "localhost" ||
            normalized == "::1" ||
            normalized == "0:0:0:0:0:0:0:1" ||
            normalized == "127.0.0.1" ||
            (normalized.startsWith("127.") && normalized.substringAfterLast('.', "").toIntOrNull() != null)
    }
}
