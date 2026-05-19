package io.bluetape4k.clinic.appointment.api.security

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
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * JWT security configuration for non-local profiles.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile("(!dev & !test) | integration-test")
@EnableConfigurationProperties(JwtSecurityProperties::class)
class SecurityConfig {

    companion object : KLogging()

    @Bean
    fun jwtTokenParser(properties: JwtSecurityProperties): JwtTokenParser {
        log.info { "JWT 보안 활성화: issuer=${properties.issuer}" }
        return JwtTokenParser(properties)
    }

    @Bean
    fun jwtAuthenticationFilter(jwtTokenParser: JwtTokenParser): JwtAuthenticationFilter =
        JwtAuthenticationFilter(jwtTokenParser)

    @Bean
    fun tenantContextFilter(
        tenantGroupRepository: TenantGroupRepository,
        jwtTokenParser: JwtTokenParser,
    ): TenantContextFilter =
        TenantContextFilter(tenantGroupRepository, jwtTokenParser)

    @Bean
    fun tenantAuthorizationManager(): TenantAuthorizationManager =
        TenantAuthorizationManager()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
        tenantContextFilter: TenantContextFilter,
        tenantAuthorizationManager: TenantAuthorizationManager,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .exceptionHandling {
                it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                it.accessDeniedHandler { request, response, _ ->
                    val principal = SecurityContextHolder.getContext().authentication?.principal
                        ?: (request.userPrincipal as? Authentication)?.principal
                    val status = if (principal is SchedulingUserPrincipal) {
                        HttpStatus.FORBIDDEN
                    } else {
                        HttpStatus.UNAUTHORIZED
                    }
                    response.sendError(status.value())
                }
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // OpenAPI / Swagger / Actuator
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**").permitAll()
                    .requestMatchers("/api/{tenantCode}/admin/**")
                    .access(adminTenantAccess(tenantAuthorizationManager))
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
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
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
