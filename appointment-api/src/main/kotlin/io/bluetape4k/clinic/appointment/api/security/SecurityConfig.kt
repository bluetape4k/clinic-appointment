package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * JWT 보안 설정. dev/test 외 모든 환경(prod, staging 등)에서 활성화됩니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile("!dev & !test")
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
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // OpenAPI / Swagger / Actuator
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/**").permitAll()
                    // 읽기 API — 인증만 필요
                    .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                    // 쓰기 API — ADMIN 또는 STAFF
                    .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.STAFF)
                    .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.STAFF)
                    .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyRole(SchedulingRole.ADMIN, SchedulingRole.STAFF)
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
}

/**
 * dev/test 프로파일 전용 — 모든 요청 허용 (JWT 인증 없음).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Profile("dev", "test")
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
