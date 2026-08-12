package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.isAppointmentCommitmentRequestPath
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.isSchedulingPolicyRequestPath
import io.bluetape4k.clinic.appointment.api.config.isBookingReliabilityRequestPath
import io.bluetape4k.clinic.appointment.api.config.isWaitlistRequestPath
import io.bluetape4k.clinic.appointment.api.reliability.BookingReliabilityApiError
import io.bluetape4k.clinic.appointment.api.tenant.TenantContextFilter
import io.bluetape4k.clinic.appointment.api.tenant.TenantPathValidationFilter
import io.bluetape4k.clinic.appointment.api.waitlist.WaitlistApiError
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import jakarta.servlet.DispatcherType
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
import io.bluetape4k.clinic.appointment.api.auth.PatientAuthenticationProperties
import org.springframework.beans.factory.ObjectProvider
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
    fun jwtAuthenticationFilter(
        jwtTokenParser: JwtTokenParser,
        patientProperties: ObjectProvider<PatientAuthenticationProperties>,
        patientSessionCookies: ObjectProvider<PatientSessionCookie>,
    ): JwtAuthenticationFilter {
        val properties = patientProperties.getIfAvailable { PatientAuthenticationProperties() }
        val cookie = patientSessionCookies.getIfAvailable { PatientSessionCookie(properties) }
        return JwtAuthenticationFilter(jwtTokenParser, properties, cookie)
    }

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

    /** tenant path 문법 검증은 embedded servlet 등록 없이 Security chain에서 한 번만 수행한다. */
    @Bean
    fun tenantPathValidationFilterRegistration(
        filter: TenantPathValidationFilter,
    ): FilterRegistrationBean<TenantPathValidationFilter> =
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

    /** JWT parser보다 앞서 raw/decoded tenant path 표현을 검증한다. */
    @Bean
    fun tenantPathValidationFilter(): TenantPathValidationFilter =
        TenantPathValidationFilter()

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
        tenantPathValidationFilter: TenantPathValidationFilter,
        tenantContextFilter: TenantContextFilter,
        tenantAuthorizationManager: TenantAuthorizationManager,
        patientProperties: ObjectProvider<PatientAuthenticationProperties>,
    ): SecurityFilterChain =
        http
            // 환자 JWT는 HttpOnly cookie로 전송되므로 bearer-only API의 예외를 두지 않고
            // Angular가 읽는 XSRF-TOKEN cookie/header 계약을 활성화한다. SPA 전략은
            // authentication/logout 뒤 지워진 token을 다음 GET에서 재발급한다.
            .csrf {
                it.spa()
                it.requireCsrfProtectionMatcher(
                    PatientCsrfRequestMatcher(
                        patientProperties.getIfAvailable { PatientAuthenticationProperties() }.cookieName,
                    ),
                )
            }
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
                    } else if (status == HttpStatus.FORBIDDEN && request.isBookingReliabilityRequest()) {
                        SecurityErrorResponseWriter.write(
                            response,
                            BookingReliabilityApiError.BOOKING_RELIABILITY_FORBIDDEN,
                        )
                    } else if (status == HttpStatus.FORBIDDEN && request.isWaitlistRequest()) {
                        SecurityErrorResponseWriter.write(
                            response,
                            WaitlistApiError.WAITLIST_FORBIDDEN,
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
                    // suspend controller의 ASYNC 재디스패치는 최초 REQUEST가 권한 검사를
                    // 통과한 뒤에만 발생한다. 최초 요청의 tenant·scope·clinic 검사는 아래
                    // matcher가 담당하며, 보안 통합 테스트가 거절·허용 경로를 함께 고정한다.
                    .dispatcherTypeMatchers(DispatcherType.ASYNC)
                    .permitAll()
                    .requestMatchers(EndpointRequest.to(ProfileReevaluationEndpoint::class.java))
                    .access(profileReevaluationAccess())
                    // OpenAPI / Swagger는 공개로 유지하며, 운영 endpoint는 배포 환경이
                    // 명시적인 정책을 추가하지 않는 한 인증을 요구한다.
                    .requestMatchers(
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                    )
                    .permitAll()
                    // 환자 account 생성/login은 active tenant 확인을 TenantContextFilter가
                    // 담당하고, credential 자체는 controller/service에서 검증한다.
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/{tenantCode}/auth/csrf",
                    )
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/{tenantCode}/auth/register",
                        "/api/{tenantCode}/auth/login",
                    )
                    .permitAll()
                    // session/logout은 HttpOnly patient cookie에서 수립된 PATIENT만
                    // 동일 tenant에서 사용할 수 있다. generic tenant read/write rule보다
                    // 먼저 두어 workforce role이 환자 session 경계를 우회하지 못하게 한다.
                    .requestMatchers(HttpMethod.GET, "/api/{tenantCode}/auth/session")
                    .access(patientTenantAccess(tenantAuthorizationManager))
                    .requestMatchers(HttpMethod.POST, "/api/{tenantCode}/auth/logout")
                    .access(patientTenantAccess(tenantAuthorizationManager))
                    // Commitment route는 일반 tenant 읽기/쓰기 규칙보다 먼저 평가해야 한다.
                    // 경로의 tenant만 selector로 사용하며, tenant manager가 인증된 grant 집합과
                    // 대조해 검증한다.
                    .requestMatchers(HttpMethod.POST, "/api/{tenantCode}/appointment-requests")
                    .access(patientTenantAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/{tenantCode}/appointments/*/proposals/*/accept",
                        "/api/{tenantCode}/appointments/*/proposals/*/decline",
                    )
                    .access(patientTenantAccess(tenantAuthorizationManager))
                    // query clinic selector를 사용하는 closure mutation은 tenant-wide POST 규칙으로
                    // 흘러가면 안 된다. controller 호출 전에 tenant grant와 principal의 정확한
                    // clinic allow-list를 모두 확인한다.
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/{tenantCode}/appointments/*/reschedule/closure",
                    )
                    .access(closureRescheduleAccess(tenantAuthorizationManager))
                    .requestMatchers(HttpMethod.GET, "/api/{tenantCode}/appointments/*/commitment")
                    .access(commitmentReadTenantAccess(tenantAuthorizationManager))
                    .requestMatchers(HttpMethod.POST, "/api/{tenantCode}/appointments/*/cancel")
                    .access(commitmentCancellationTenantAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/{tenantCode}/appointments/*/approve",
                        "/api/{tenantCode}/appointments/*/confirm",
                        "/api/{tenantCode}/appointments/*/proposals/*/expire",
                        "/api/{tenantCode}/appointments/*/change-proposals",
                    )
                    .access(commitmentAdminTenantAccess(tenantAuthorizationManager))
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
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/{tenantCode}/clinics/{clinicId}/notifications/**",
                    )
                    .access(notificationReadAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/{tenantCode}/clinics/{clinicId}/notifications/re-notify",
                    )
                    .access(notificationReNotifyAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/{tenantCode}/clinics/{clinicId}/members/*/booking-reliability/decision",
                    )
                    .access(bookingReliabilityReadAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/{tenantCode}/clinics/{clinicId}/members/*/booking-reliability/audit",
                    )
                    .access(bookingReliabilityAuditAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/{tenantCode}/clinics/{clinicId}/members/*/booking-reliability/override",
                        "/api/{tenantCode}/clinics/{clinicId}/members/*/booking-reliability/clear",
                    )
                    .access(bookingReliabilityWriteAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/{tenantCode}/clinics/{clinicId}/waitlist/**",
                    )
                    .access(waitlistReadAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/{tenantCode}/clinics/{clinicId}/waitlist/policies/**",
                    )
                    .access(waitlistPolicyAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/{tenantCode}/clinics/{clinicId}/waitlist/restrictions/**",
                        "/api/{tenantCode}/clinics/{clinicId}/waitlist/recovery-credits/**",
                        "/api/{tenantCode}/clinics/{clinicId}/waitlist/benefit-grants/**",
                    )
                    .access(waitlistAdjustmentAccess(tenantAuthorizationManager))
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/{tenantCode}/clinics/{clinicId}/waitlist/**",
                    )
                    .access(waitlistWriteAccess(tenantAuthorizationManager))
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
            .addFilterAfter(tenantPathValidationFilter, CorrelationIdFilter::class.java)
            .addFilterAfter(jwtAuthenticationFilter, TenantPathValidationFilter::class.java)
            .addFilterAfter(tenantContextFilter, JwtAuthenticationFilter::class.java)
            .build()

    private fun adminTenantAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasRole(SchedulingRole.ADMIN),
            tenantAuthorizationManager,
        )

    private fun patientTenantAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasRole(SchedulingRole.PATIENT),
            tenantAuthorizationManager,
        )

    private fun commitmentReadTenantAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(
                SchedulingRole.ADMIN,
                SchedulingRole.STAFF,
                SchedulingRole.PATIENT,
            ),
            tenantAuthorizationManager,
        )

    private fun commitmentAdminTenantAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasRole(SchedulingRole.ADMIN),
            tenantAuthorizationManager,
        )

    private fun commitmentCancellationTenantAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(
                SchedulingRole.ADMIN,
                SchedulingRole.STAFF,
                SchedulingRole.PATIENT,
            ),
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

    private fun closureRescheduleAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.STAFF),
            tenantAuthorizationManager,
            AuthorizationManager { authentication, context ->
                val principal = authentication.get().principal as? SchedulingUserPrincipal
                val requestedClinicId = context.request.getParameter("clinicId")?.toLongOrNull()
                AuthorizationDecision(
                    requestedClinicId != null &&
                        requestedClinicId > 0 &&
                        principal?.allowedClinicIds?.isNotEmpty() == true &&
                        requestedClinicId in principal.allowedClinicIds,
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

    private fun notificationReadAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(
                SchedulingRole.ADMIN,
                SchedulingRole.STAFF,
                SchedulingRole.DOCTOR,
            ),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_notification:read"),
            tenantAuthorizationManager,
            exactClinicMembershipAccess(),
        )

    private fun notificationReNotifyAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasRole(SchedulingRole.SYSTEM),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_notification:renotify"),
            tenantAuthorizationManager,
            exactClinicMembershipAccess(),
            platformNotificationServiceAccess(),
        )

    private fun bookingReliabilityReadAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(
                SchedulingRole.ADMIN,
                SchedulingRole.STAFF,
                SchedulingRole.DOCTOR,
            ),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_booking-reliability:read"),
            tenantAuthorizationManager,
            exactClinicMembershipAccess(),
        )

    private fun bookingReliabilityAuditAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.STAFF),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_booking-reliability:audit"),
            tenantAuthorizationManager,
            exactClinicMembershipAccess(),
        )

    private fun bookingReliabilityWriteAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.STAFF),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_booking-reliability:write"),
            tenantAuthorizationManager,
            exactClinicMembershipAccess(),
        )

    private fun waitlistReadAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(
                SchedulingRole.ADMIN,
                SchedulingRole.STAFF,
                SchedulingRole.DOCTOR,
            ),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_waitlist:read"),
            tenantAuthorizationManager,
            exactClinicMembershipAccess(),
        )

    private fun waitlistWriteAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.STAFF),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_waitlist:write"),
            tenantAuthorizationManager,
            exactClinicMembershipAccess(),
        )

    private fun waitlistPolicyAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.STAFF),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_waitlist:policy"),
            tenantAuthorizationManager,
            exactClinicMembershipAccess(),
        )

    private fun waitlistAdjustmentAccess(
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(
            AuthorityAuthorizationManager.hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.STAFF),
            AuthorityAuthorizationManager.hasAuthority("SCOPE_waitlist:adjustment"),
            tenantAuthorizationManager,
            exactClinicMembershipAccess(),
        )

    private fun platformNotificationServiceAccess(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, _ ->
            val principal = authentication.get().principal as? SchedulingUserPrincipal
            AuthorizationDecision(
                principal?.actorType == ActorType.SYSTEM &&
                    principal.assurance == AuthenticationAssurance.SERVICE &&
                    principal.roles == setOf(SchedulingRole.SYSTEM)
            )
        }

    private fun exactClinicMembershipAccess(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, context ->
            val principal = authentication.get().principal as? SchedulingUserPrincipal
            val requestedClinicId = context.variables["clinicId"]?.toLongOrNull()
            AuthorizationDecision(
                requestedClinicId != null &&
                    requestedClinicId > 0 &&
                    requestedClinicId in principal?.allowedClinicIds.orEmpty()
            )
        }

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

private fun jakarta.servlet.http.HttpServletRequest.isBookingReliabilityRequest(): Boolean =
    isBookingReliabilityRequestPath(requestURI)

private fun jakarta.servlet.http.HttpServletRequest.isWaitlistRequest(): Boolean =
    isWaitlistRequestPath(requestURI)

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
    fun correlationIdFilter(): CorrelationIdFilter = CorrelationIdFilter()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        correlationIdFilter: CorrelationIdFilter,
    ): SecurityFilterChain {
        log.info { "JWT 보안 비활성화 — 모든 요청 허용" }
        return http
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
    }
}
