package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

class ApiCorsPropertiesTest {
    @Test
    fun `기본 CORS는 기존 same-origin 배포를 보존한다`() {
        val properties = ApiCorsProperties()

        properties.enabled shouldBeEqualTo false
        properties.allowedOrigins shouldBeEqualTo emptyList()
        properties.allowCredentials shouldBeEqualTo true
    }

    @Test
    fun `enabled CORS는 유한한 HTTPS origin과 비음수 maxAge를 요구한다`() {
        assertFailsWith<IllegalArgumentException> {
            ApiCorsProperties(enabled = true)
        }
        assertFailsWith<IllegalArgumentException> {
            ApiCorsProperties(enabled = true, allowedOrigins = listOf("*"))
        }
        assertFailsWith<IllegalArgumentException> {
            ApiCorsProperties(enabled = true, allowedOrigins = listOf("http://api.example.test"))
        }
        assertFailsWith<IllegalArgumentException> {
            ApiCorsProperties(
                enabled = true,
                allowedOrigins = listOf("https://app.example.test"),
                allowCredentials = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ApiCorsProperties(
                enabled = true,
                allowedOrigins = listOf("https://app.example.test"),
                maxAge = Duration.ofSeconds(-1),
            )
        }
    }

    @Test
    fun `local HTTP origin은 개발 진단에만 허용한다`() {
        val properties = ApiCorsProperties(
            enabled = true,
            allowedOrigins = listOf("http://localhost:4200", "http://127.0.0.1:4200"),
        )

        properties.allowedOrigins.size shouldBeEqualTo 2
    }
}
