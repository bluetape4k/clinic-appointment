package io.bluetape4k.clinic.appointment.api.auth

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.security.PatientSessionCookie
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** tenant path에 묶인 환자 회원가입, login, session, logout, CSRF bootstrap API입니다. */
@RestController
@ConditionalOnBean(PatientAuthenticationService::class)
@RequestMapping("/api/{tenantCode}/auth")
class PatientAuthenticationController(
    private val authenticationService: PatientAuthenticationService,
    private val sessionCookie: PatientSessionCookie,
) {
    /** SPA가 CSRF cookie를 준비하도록 하는 공개 bootstrap입니다. */
    @GetMapping("/csrf")
    fun csrf(csrfToken: CsrfToken? = null): ResponseEntity<ApiResponse<PatientCsrfResponse>> {
        // Spring Security SPA handler가 deferred token을 materialize하도록 명시적으로
        // 읽는다. 응답 body에는 token을 절대 반영하지 않고 CookieCsrfTokenRepository가
        // 발급한 XSRF-TOKEN만 브라우저에 남긴다.
        csrfToken?.token
        return ResponseEntity.ok(ApiResponse.ok(PatientCsrfResponse()))
    }

    /** account와 requested identifier를 생성하고 session은 발급하지 않습니다. */
    @PostMapping("/register")
    fun register(
        @PathVariable tenantCode: String,
        @RequestBody request: PatientRegisterRequest,
    ): ResponseEntity<ApiResponse<PatientRegistrationResponse>> {
        authenticationService.register(tenantCode, request)
        return ResponseEntity.status(201)
            .body(ApiResponse.ok(PatientRegistrationResponse()))
    }

    /** 성공 login의 JWT는 response body가 아니라 HttpOnly cookie로만 전달합니다. */
    @PostMapping("/login")
    fun login(
        @PathVariable tenantCode: String,
        @RequestBody request: PatientLoginRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<PatientSessionSummary>> {
        val fingerprint = servletRequest.getHeader(CLIENT_FINGERPRINT_HEADER)
            ?.take(MAX_FINGERPRINT_LENGTH)
            ?.ifBlank { UNKNOWN_FINGERPRINT }
            ?: UNKNOWN_FINGERPRINT
        val result = authenticationService.login(tenantCode, request, fingerprint)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie.issue(result.token, result.session.expiresAt))
            .body(ApiResponse.ok(result.session))
    }

    /** 검증된 PATIENT cookie의 public summary만 반환합니다. */
    @GetMapping("/session")
    fun session(
        @PathVariable tenantCode: String,
        @AuthenticationPrincipal principal: SchedulingUserPrincipal,
    ): ResponseEntity<ApiResponse<PatientSessionSummary>> =
        ResponseEntity.ok(ApiResponse.ok(authenticationService.session(tenantCode, principal)))

    /** credential 유무와 무관하게 cookie를 idempotently 삭제합니다. */
    @PostMapping("/logout")
    fun logout(): ResponseEntity<Void> =
        ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, sessionCookie.delete())
            .build()

    private companion object {
        const val CLIENT_FINGERPRINT_HEADER = "X-Client-Fingerprint"
        const val MAX_FINGERPRINT_LENGTH = 256
        const val UNKNOWN_FINGERPRINT = "unknown"
    }
}
