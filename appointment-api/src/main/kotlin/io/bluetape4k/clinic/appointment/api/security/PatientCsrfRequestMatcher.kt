package io.bluetape4k.clinic.appointment.api.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * bearer API 호환성을 유지하면서 patient cookie transport에만 CSRF를 요구합니다.
 *
 * 로그인/회원가입은 아직 cookie가 없으므로 patient auth mutation path를 명시적으로
 * 포함하고, 나머지 API는 cookie가 실제로 전송된 경우에만 보호합니다. Authorization
 * bearer가 있는 요청은 기존 workforce/system client의 contract를 그대로 유지합니다.
 */
class PatientCsrfRequestMatcher(
    private val cookieName: String,
) : RequestMatcher {

    override fun matches(request: HttpServletRequest): Boolean {
        if (request.method in SAFE_METHODS) return false
        if (request.getHeader("Authorization")?.startsWith("Bearer ") == true) return false

        val path = request.requestURI.removePrefix(request.contextPath.orEmpty())
        val isPatientAuthMutation = path.matches(PATIENT_AUTH_MUTATION)
        val hasPatientCookie = request.cookies?.any { it.name == cookieName } == true
        return isPatientAuthMutation || hasPatientCookie
    }

    private companion object {
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
        private val PATIENT_AUTH_MUTATION = Regex("/api/[a-z0-9]+(?:-[a-z0-9]+)*/auth/(register|login|logout)")
    }
}
