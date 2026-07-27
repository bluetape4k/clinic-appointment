package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.api.config.PlanFoundationError
import io.bluetape4k.clinic.appointment.api.tenant.TenantContextFilter
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.context.properties.EnableConfigurationProperties
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
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * JWT security configuration for non-local profiles.
 *
 * Rule flow:
 * 1) correlation ID is created first to keep every failure observable.
 * 2) JWT is parsed before tenant-context authorization.
 * 3) endpoint path authorization is evaluated as a composition of:
 *    tenant membership + role/scope checks + clinic membership checks.
 *
 * 401 means no trusted authenticated principal was established.
 * 403 means principal exists but failed one of policy checks.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile("(!dev & !test) | integration-test")
@EnableConfigurationProperties(JwtSecurityProperties::class)
class SecurityConfig {

    companion object : KLogging()

    /**
     * Builds the fail-closed token verifier.
     *
     * Invalid signing-key configuration fails bean construction. Individual
     * request token failures return no principal and disclose no claim detail.
     */
    @Bean
    fun jwtTokenParser(properties: JwtSecurityProperties): JwtTokenParser {
        require(properties.enabled) {
            "JWT security cannot be disabled in a protected profile"
        }
        log.info { "JWT 보안 활성화: issuer=${properties.issuer}" }
        return JwtTokenParser(properties)
    }

    /** Validates a bearer token before attaching its principal to the context. */
    @Bean
    fun jwtAuthenticationFilter(jwtTokenParser: JwtTokenParser): JwtAuthenticationFilter =
        JwtAuthenticationFilter(jwtTokenParser)

    /** Resolves verified principals into immutable command audit contexts. */
    @Bean
    fun actorContextResolver(): ActorContextResolver = ActorContextResolver()

    /** Establishes correlation before authentication or controller failures. */
    @Bean
    fun correlationIdFilter(): CorrelationIdFilter = CorrelationIdFilter()

    /**
     * Build tenant membership filter to keep tenant and clinic routing aligned
     * with scheduling data ownership before entering controllers.
     */
    @Bean
    fun tenantContextFilter(
        tenantGroupRepository: TenantGroupRepository,
        jwtTokenParser: JwtTokenParser,
    ): TenantContextFilter =
        TenantContextFilter(tenantGroupRepository, jwtTokenParser)

    /**
     * Shared tenant policy authorization manager reused by endpoint-specific rules.
     */
    @Bean
    fun tenantAuthorizationManager(): TenantAuthorizationManager =
        TenantAuthorizationManager()

    /**
     * Builds the stateless production-style security chain.
     *
     * Correlation is established before authentication, tenant routing follows
     * authentication, and authorization then composes role, capability,
     * tenant, and exact clinic membership. Swagger documentation is public;
     * admin APIs require `ADMIN`, catalog writes require
     * `SCOPE_catalog:write`, and clinic-plan reads require an operator role plus
     * membership in the requested positive clinic ID.
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
                    val error = if (status == HttpStatus.FORBIDDEN) {
                        PlanFoundationError.FORBIDDEN
                    } else {
                        PlanFoundationError.UNAUTHORIZED
                    }
                    SecurityErrorResponseWriter.write(response, error)
                }
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // OpenAPI / Swagger remain public; operational endpoints
                    // stay authenticated unless a deployment adds an explicit policy.
                    .requestMatchers(
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                    )
                    .permitAll()
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
}

/**
 * Local dev/test security configuration that permits all requests.
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
