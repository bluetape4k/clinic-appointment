package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.api.auth.PatientAuthenticationProperties
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JWT 인증 필터.
 *
 * 이 서비스의 stateless request authentication은 이 filter에서 검증한 bearer token만
 * 권위로 사용한다. servlet thread에 남아 있거나 upstream에서 수립된 이전 authentication은
 * 요청 시작 시 제거하며, token이 없거나 유효하지 않으면 anonymous 상태를 유지한다.
 * 검증에 성공한 경우에만 [SecurityContextHolder]에 새 인증 정보를 설정하고, downstream
 * filter가 context를 변경했더라도 요청이 끝나면 다시 제거한다.
 *
 * @param jwtTokenParser JWT 토큰 검증 및 Claims 파서
 */
class JwtAuthenticationFilter(
    private val jwtTokenParser: JwtTokenParser,
    private val patientAuthenticationProperties: PatientAuthenticationProperties = PatientAuthenticationProperties(),
    private val patientSessionCookie: PatientSessionCookie = PatientSessionCookie(patientAuthenticationProperties),
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

        try {
            val bearerHeader = request.getHeader(AUTHORIZATION_HEADER)
            val bearerToken = bearerHeader
                ?.takeIf { it.startsWith(BEARER_PREFIX) }
                ?.substring(BEARER_PREFIX.length)
                ?.takeIf(String::isNotBlank)
            val cookieToken = if (bearerHeader == null) extractPatientCookie(request) else null
            val token = bearerToken ?: cookieToken?.value
            if (token != null || cookieToken != null) {
                val principal = token
                    ?.takeIf(String::isNotBlank)
                    ?.let(jwtTokenParser::parse)
                if (principal != null) {
                    val authentication = UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.authorities,
                    )
                    SecurityContextHolder.getContext().authentication = authentication
                    log.debug { "JWT 인증 성공: userId=${principal.userId}, roles=${principal.roles}" }
                } else if (cookieToken != null) {
                    // malformed/expired browser session은 다음 요청에서 재전송되지 않도록
                    // token 값을 절대 포함하지 않는 deletion cookie만 반환한다.
                    response.addHeader(HttpHeaders.SET_COOKIE, patientSessionCookie.delete())
                }
            }

            filterChain.doFilter(request, response)
        } finally {
            // downstream filter가 context를 덮어써도 servlet thread 재사용 시 다음 요청으로
            // 인증이 전파되지 않도록 이 filter의 요청 범위에서 마지막으로 비운다.
            SecurityContextHolder.clearContext()
        }
    }

    private fun extractPatientCookie(request: HttpServletRequest): PatientCookieToken? {
        val cookies = request.cookies
            ?.filter { it.name == patientAuthenticationProperties.cookieName }
            .orEmpty()
        if (cookies.size != 1) return null
        return PatientCookieToken(cookies.single().value)
    }

    private data class PatientCookieToken(val value: String)
}
