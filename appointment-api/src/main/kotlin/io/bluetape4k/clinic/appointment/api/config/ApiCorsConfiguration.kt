package io.bluetape4k.clinic.appointment.api.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Security가 항상 찾을 수 있는 CORS source를 제공하고, 명시적인 property가 켜진 경우에만
 * tenant API mapping을 등록합니다. 비활성 상태의 빈 source는 기존 same-origin 동작을
 * 보존하면서 Spring Security의 preflight 진입점을 안정적으로 구성합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiCorsProperties::class)
class ApiCorsConfiguration {
    @Bean
    fun corsConfigurationSource(properties: ApiCorsProperties): UrlBasedCorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = properties.allowedOrigins
            allowedMethods = properties.allowedMethods
            allowedHeaders = properties.allowedHeaders
            exposedHeaders = properties.exposedHeaders
            allowCredentials = properties.allowCredentials
            maxAge = properties.maxAge.seconds
        }
        return UrlBasedCorsConfigurationSource().also { source ->
            if (properties.enabled) source.registerCorsConfiguration("/api/**", configuration)
        }
    }
}
