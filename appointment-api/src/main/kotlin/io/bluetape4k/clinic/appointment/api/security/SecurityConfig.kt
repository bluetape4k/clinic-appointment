package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.isAppointmentCommitmentRequestPath
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.isSchedulingPolicyRequestPath
import io.bluetape4k.clinic.appointment.api.tenant.TenantContextFilter
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import jakarta.servlet.Filter
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authorization.AuthenticatedAuthorizationManager
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.AuthorizationManagers
import org.springframework.security.authorization.AuthorityAuthorizationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationEndpoint
import io.bluetape4k.clinic.appointment.api.profile.PROFILE_REEVALUATION_OPERATE_SCOPE
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * non-local profile에서 사용하는 JWT security configuration이다.
 *
 * 규칙 흐름:
 * 1. 모든 실패를 추적할 수 있도록 correlation ID를 가장 먼저 만든다.
 * 2. tenant-context authorization 전에 JWT를 parsing한다.
 * 3. endpoint path authorization은 tenant membership, role/scope check,
 *    clinic membership check의 조합으로 평가한다.
 *
 * 401은 신뢰된 authenticated principal이 수립되지 않았다는 뜻이다.
 * 403은 principal은 있지만 policy check 중 하나를 통과하지 못했다는 뜻이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile("(!dev & !test) | integration-test")
@EnableConfigurationProperties(JwtSecurityProperties::class)
class SecurityConfig {

    companion object : KLogging()

    /**
     * fail-closed token verifier를 생성한다.
     *
     * signing-key 설정이 잘못되면 bean construction이 실패한다. 개별 request token 실패는
     * principal을 만들지 않고 어떤 claim이 실패했는지도 공개하지 않는다.
     */
    @Bean
    fun jwtTokenParser(properties: JwtSecurityProperties): JwtTokenParser {
        require(properties.enabled) {
            "JWT security cannot be disabled in a protected profile"
        }
        log.info { "JWT 보안 활성화: issuer=${properties.issuer}" }
        return JwtTokenParser(properties)
    }

    /** principal을 context에 붙이기 전에 bearer token을 검증한다. */
    @Bean
    fun jwtAuthenticationFilter(jwtTokenParser: JwtTokenParser): JwtAuthenticationFilter =
        JwtAuthenticationFilter(jwtTokenParser)

    /** 검증된 principal을 불변 command audit context로 변환한다. */
    @Bean
    fun actorContextResolver(): ActorContextResolver = ActorContextResolver()

    /** authentication 또는 controller 실패 전에 correlation을 수립한다. */
    @Bean
    fun correlationIdFilter(): CorrelationIdFilter = CorrelationIdFilter()

    /**
     * Security chain이 소유하는 JWT filter를 embedded servlet container에 다시 등록하지 않는다.
     *
     * Spring Boot는 모든 [Filter] bean을 기본적으로 전체 request path의 servlet filter로 자동 등록한다.
     * 같은 instance를 [securityFilterChain]에도 추가한 채 자동 등록을 허용하면 Security의
     * context 생성·정리 경계 밖에서 filter가 먼저 실행될 수 있다. 그 결과 이전 요청의
     * authentication이 재사용되거나 chain 내부 실행이 `OncePerRequestFilter` 표식 때문에
     * 건너뛰어질 수 있으므로 servlet 등록은 끄고 Security chain 순서만 권위로 사용한다.
     */
    @Bean
    fun jwtAuthenticationFilterRegistration(
        filter: JwtAuthenticationFilter,
    ): FilterRegistrationBean<JwtAuthenticationFilter> =
        securityChainOnlyRegistration(filter)

    /** tenant context는 JWT 검증 뒤 Security chain 안에서만 수립하고 반드시 같은 요청에서 복구한다. */
    @Bean
    fun tenantContextFilterRegistration(
        filter: TenantContextFilter,
    ): FilterRegistrationBean<TenantContextFilter> =
        securityChainOnlyRegistration(filter)

    /** correlation ID도 Security exception boundary의 첫 단계에서 정확히 한 번만 생성한다. */
    @Bean
    fun correlationIdFilterRegistration(
        filter: CorrelationIdFilter,
    ): FilterRegistrationBean<CorrelationIdFilter> =
        securityChainOnlyRegistration(filter)

    /**
     * controller 진입 전에 tenant/clinic routing을 scheduling data ownership과 맞추는
     * tenant membership filter를 생성한다.
     */
    @Bean
    fun tenantContextFilter(
        tenantGroupRepository: TenantGroupRepository,
        jwtTokenParser: JwtTokenParser,
    ): TenantContextFilter =
        TenantContextFilter(tenantGroupRepository, jwtTokenParser)

    /**
     * endpoint-specific rule에서 재사용하는 공용 tenant policy authorization manager.
     */
    @Bean
    fun tenantAuthorizationManager(): TenantAuthorizationManager =
        TenantAuthorizationManager()

    /**
     * stateless production-style security chain을 구성한다.
     *
     * correlation은 authentication 전에 수립하고, tenant routing은 authentication 이후에 수행한다.
     * authorization은 role, capability, tenant, 정확한 clinic membership을 조합한다.
     * Swagger 문서는 공개한다. admin API는 `ADMIN`, catalog write는 `SCOPE_catalog:write`,
     * clinic-plan read는 operator role과 요청된 양수 clinic ID membership을 요구한다.
     */
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
        correlationIdFilter: CorrelationIdFilter,
        tenantContextFilter: TenantContextFilter,
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    SecurityErrorResponseWriter.write(response, PlanFoundationError.UNAUTHORIZED)
                }
                it.accessDeniedHandler { request, response, _ ->
                    val principal = SecurityContextHolder.getContext().authentication?.principal
                        ?: (request.userPrincipal as? Authentication)?.principal
                    val status = if (principal is SchedulingUserPrincipal) {
                        HttpStatus.FORBIDDEN
                    } else {
                        HttpStatus.UNAUTHORIZED
                    }
                    if (status == HttpStatus.FORBIDDEN && request.isAppointmentCommitmentRequest()) {
                        SecurityErrorResponseWriter.write(
                            response,
                            AppointmentCommitmentApiError.SCOPE_FORBIDDEN,
                        )
                    } else if (status == HttpStatus.FORBIDDEN && request.isSchedulingPolicyRequest()) {
                        SecurityErrorResponseWriter.write(
                            response,
                            SchedulingPolicyErrorCode.POLICY_ACTOR_FORBIDDEN,
                        )
                    } else {
                        val error = if (status == HttpStatus.FORBIDDEN) {
                            PlanFoundationError.FORBIDDEN
                        } else {
                            PlanFoundationError.UNAUTHORIZED
                        }
                        SecurityErrorResponseWriter.write(response, error)
                    }
                }
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(EndpointRequest.to(ProfileReevaluationEndpoint::class.java))
                    .access(profileReevaluationAccess())
                    // OpenAPI / Swagger remain public; operational endpoints
                    // stay authenticated unless a deployment adds an explicit policy.
                    .requestMatchers(
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                    )
                    .permitAll()
                    .requestMatchers("/api/v2/admin/**")
                    .hasRole(SchedulingRole.ADMIN)
                    .requestMatchers(HttpMethod.POST, "/api/v2/appointment-requests")
                    .hasRole(SchedulingRole.PATIENT)
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v2/appointments/*/proposals/*/accept",
                        "/api/v2/appointments/*/proposals/*/decline",
                    )
                    .hasRole(SchedulingRole.PATIENT)
                    .requestMatchers(HttpMethod.GET, "/api/v2/appointments/*/commitment")
                    .hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.PATIENT)
                    .requestMatchers("/api/v2/**")
                    .hasRole(SchedulingRole.ADMIN)
                    .requestMatchers(
                        "/api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/**",
                    )
                    .access(clinicPolicyAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        "/api/{tenantCode}/admin/scheduling-policies/**",
                    )
                    .access(tenantPolicyAccess(tenantAuthorizationManager))
                    .requestMatchers("/api/{tenantCode}/admin/**")
                    .access(adminTenantAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.PUT,
                        "/api/{tenantCode}/clinics/*/catalog-sources/*/catalog-products/*/versions/*",
                    )
                    .access(catalogWriteTenantAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/{tenantCode}/clinics/{clinicId}/appointment-plans/**",
                    )
                    .access(clinicOperatorReadAccess(tenantAuthorizationManager))
                    .requestMatchers(HttpMethod.GET, "/api/{tenantCode}/**")
                    .access(readTenantAccess(tenantAuthorizationManager))
                    .requestMatchers(HttpMethod.POST, "/api/{tenantCode}/**")
                    .access(writeTenantAccess(tenantAuthorizationManager))
                    .requestMatchers(HttpMethod.PATCH, "/api/{tenantCode}/**")
                    .access(writeTenantAccess(tenantAuthorizationManager))
                    .requestMatchers(HttpMethod.DELETE, "/api/{tenantCode}/**")
                    .access(writeTenantAccess(tenantAuthorizationManager))
                    .anyRequest().authenticated()
            }
            .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterAfter(jwtAuthenticationFilter, CorrelationIdFilter::class.java)
            .addFilterAfter(tenantContextFilter, JwtAuthenticationFilter::class.java)
            .build()

    private fun adminTenantAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasRole(SchedulingRole.ADMIN),
            tenantAuthorizationManager,
        )

    /**
     * 전역 actuator 경로는 tenant path가 없으므로 관리자 role만으로 열지 않습니다.
     * Gateway가 부여한 전용 운영 capability를 추가로 요구하고, write operation은
     * endpoint에서 요청 clinic과 principal allow-list를 다시 대조합니다.
     */
    private fun profileReevaluationAccess(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasRole(SchedulingRole.ADMIN),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_$PROFILE_REEVALUATION_OPERATE_SCOPE"),
        )

    /**
     * tenant policy transport boundary는 human operator role, explicit capability,
     * tenant membership을 모두 요구한다. 세부 승인 수와 assurance는 domain command가
     * exact draft evidence를 기준으로 다시 평가한다.
     */
    private fun tenantPolicyAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(
                SchedulingRole.ADMIN,
                SchedulingRole.STAFF,
            ),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_policy:write"),
            tenantAuthorizationManager,
        )

    /**
     * clinic policy는 tenant policy 권한에 path clinic의 exact membership을 추가한다.
     *
     * 빈 clinic allow-list를 tenant 전체 권한으로 해석하지 않으며, 숫자로 해석할 수 없는
     * path variable도 fail-closed로 거절한다.
     */
    private fun clinicPolicyAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            tenantPolicyAccess(tenantAuthorizationManager),
            AuthorizationManager { authentication, context ->
                val principal = authentication.get().principal as? SchedulingUserPrincipal
                val requestedClinicId = context.variables["clinicId"]?.toLongOrNull()
                AuthorizationDecision(
                    requestedClinicId != null &&
                        requestedClinicId > 0 &&
                        requestedClinicId in principal?.allowedClinicIds.orEmpty()
                )
            },
        )

    private fun readTenantAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthenticatedAuthorizationManager.authenticated(),
            tenantAuthorizationManager,
        )

    private fun writeTenantAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.STAFF),
            tenantAuthorizationManager,
        )

    private fun catalogWriteTenantAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAuthority("SCOPE_catalog:write"),
            tenantAuthorizationManager,
        )

    private fun clinicOperatorReadAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(
                SchedulingRole.ADMIN,
                SchedulingRole.STAFF,
                SchedulingRole.DOCTOR,
            ),
            tenantAuthorizationManager,
            AuthorizationManager { authentication, context ->
                val principal = authentication.get().principal as? SchedulingUserPrincipal
                val requestedClinicId = context.variables["clinicId"]?.toLongOrNull()
                AuthorizationDecision(requestedClinicId != null && requestedClinicId in principal?.allowedClinicIds.orEmpty())
            },
        )

    /**
     * Spring bean lifecycle은 유지하면서 embedded container의 독립 servlet filter 등록만 끈다.
     */
    private fun <T : Filter> securityChainOnlyRegistration(filter: T): FilterRegistrationBean<T> =
        FilterRegistrationBean(filter).apply {
            isEnabled = false
        }
}

/** security filter chain에서도 policy 전용 privacy-safe 오류 계약을 선택한다. */
private fun jakarta.servlet.http.HttpServletRequest.isSchedulingPolicyRequest(): Boolean =
    isSchedulingPolicyRequestPath(requestURI)

/** commitment v2 Security 실패가 기존 foundation 오류로 축약되지 않게 path를 구분한다. */
private fun jakarta.servlet.http.HttpServletRequest.isAppointmentCommitmentRequest(): Boolean =
    isAppointmentCommitmentRequestPath(requestURI)

/**
 * local dev/test에서 모든 요청을 허용하는 security configuration이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile("(dev | test) & !integration-test")
class NoOpSecurityConfig {

    companion object : KLogging()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        log.info { "JWT 보안 비활성화 — 모든 요청 허용" }
        return http
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
    }
}
