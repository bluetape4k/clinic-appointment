package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT 인증 필터.
 *
 * 이 서비스의 stateless request authentication은 이 filter에서 검증한 bearer token만
 * 권위로 사용한다. servlet thread에 남아 있거나 upstream에서 수립된 이전 authentication은
 * 요청 시작 시 제거하며, token이 없거나 유효하지 않으면 anonymous 상태를 유지한다.
 * 검증에 성공한 경우에만 [SecurityContextHolder]에 새 인증 정보를 설정한다.
 *
 * @param jwtTokenParser JWT 토큰 검증 및 Claims 파서
 */
class JwtAuthenticationFilter(
    private val jwtTokenParser: JwtTokenParser,
) : OncePerRequestFilter() {

    companion object : KLogging() {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        SecurityContextHolder.clearContext()

        val token = extractToken(request)
        if (token != null) {
            val principal = jwtTokenParser.parse(token)
            if (principal != null) {
                val authentication = UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.authorities,
                )
                SecurityContextHolder.getContext().authentication = authentication
                log.debug { "JWT 인증 성공: userId=${principal.userId}, roles=${principal.roles}" }
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun extractToken(request: HttpServletRequest): String? {
        val header = request.getHeader(AUTHORIZATION_HEADER)
        return if (header != null && header.startsWith(BEARER_PREFIX)) {
            header.substring(BEARER_PREFIX.length)
        } else {
            null
        }
    }
}
