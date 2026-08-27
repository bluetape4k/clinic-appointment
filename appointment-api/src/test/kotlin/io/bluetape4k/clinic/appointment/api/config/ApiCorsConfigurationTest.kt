package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration

class ApiCorsConfigurationTest {
    @Test
    fun `API CORS source는 tenant API에 explicit credentials 계약을 적용한다`() {
        val properties = ApiCorsProperties(
            enabled = true,
            allowedOrigins = listOf("https://app.example.test"),
            maxAge = Duration.ofMinutes(10),
        )
        val source = ApiCorsConfiguration().corsConfigurationSource(properties)

        val configuration = source.getCorsConfiguration(
            MockHttpServletRequest("OPTIONS", "/api/tenant-a/auth/login"),
        ).shouldNotBeNull()

        configuration.allowedOrigins shouldBeEqualTo listOf("https://app.example.test")
        configuration.allowedMethods shouldBeEqualTo listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders shouldBeEqualTo listOf(
            "Content-Type",
            "Accept",
            "Authorization",
            "X-XSRF-TOKEN",
            "Idempotency-Key",
            "If-None-Match",
            "If-Match",
            "ngsw-bypass",
            "Cache-Control",
            "Pragma",
        )
        configuration.exposedHeaders shouldBeEqualTo listOf(
            "ETag",
            "Retry-After",
            "X-Correlation-Id",
            "X-Tenant-Identity-Generation",
        )
        configuration.allowCredentials.shouldBeTrue()
        configuration.maxAge shouldBeEqualTo Duration.ofMinutes(10).seconds
    }

    @Test
    fun `API CORS source는 api 외부 경로를 열지 않는다`() {
        val source = ApiCorsConfiguration().corsConfigurationSource(
            ApiCorsProperties(enabled = true, allowedOrigins = listOf("https://app.example.test")),
        )

        source.getCorsConfiguration(MockHttpServletRequest("OPTIONS", "/actuator/health")) shouldBeEqualTo null
    }

    @Test
    fun `비활성 CORS source는 same-origin 요청을 위해 빈 mapping을 제공한다`() {
        val source = ApiCorsConfiguration().corsConfigurationSource(ApiCorsProperties())

        source.getCorsConfiguration(MockHttpServletRequest("OPTIONS", "/api/tenant-a/auth/login")) shouldBeEqualTo null
    }
}
