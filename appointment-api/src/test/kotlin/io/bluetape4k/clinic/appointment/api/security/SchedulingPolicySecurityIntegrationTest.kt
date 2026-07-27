package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.Containers
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "integration-test")
class SchedulingPolicySecurityIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private val filter = CorrelationIdFilter()
    private lateinit var client: RestClient

    @BeforeEach
    fun setUp() {
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
    }

    @Test
    fun `bounded safe correlation id is preserved in request and response`() {
        val request = MockHttpServletRequest().apply {
            addHeader(CorrelationIdFilter.HEADER_NAME, "policy-flow-7")
        }
        val response = MockHttpServletResponse()
        var observed: String? = null

        filter.doFilter(
            request,
            response,
            FilterChain { filteredRequest, _ ->
                observed = filteredRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as String
            },
        )

        observed shouldBeEqualTo "policy-flow-7"
        response.getHeader(CorrelationIdFilter.HEADER_NAME) shouldBeEqualTo "policy-flow-7"
    }

    @Test
    fun `unsafe correlation id is replaced without reflecting attacker input`() {
        val attackerValue = "raw-token\\nclaim-secret"
        val request = MockHttpServletRequest().apply {
            addHeader(CorrelationIdFilter.HEADER_NAME, attackerValue)
        }
        val response = MockHttpServletResponse()
        var observed = ""

        filter.doFilter(
            request,
            response,
            FilterChain { filteredRequest, _ ->
                observed = filteredRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as String
            },
        )

        (observed == attackerValue).shouldBeFalse()
        observed.matches(Regex("[0-9a-f-]{36}")).shouldBeTrue()
        response.getHeader(CorrelationIdFilter.HEADER_NAME) shouldBeEqualTo observed
    }

    @Test
    fun `authentication failure exposes only generic code and the established correlation id`() {
        val attackerToken = "raw-claim-secret.invalid.jwt"
        val response = client.get()
            .uri("/api/tenant-default/admin/scheduling-policies")
            .header(CorrelationIdFilter.HEADER_NAME, "policy-auth-7")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $attackerToken")
            .exchange { _, serverResponse ->
                AuthenticationFailureResponse(
                    status = serverResponse.statusCode.value(),
                    correlationId = serverResponse.headers.getFirst(CorrelationIdFilter.HEADER_NAME),
                    body = serverResponse.bodyTo(String::class.java).orEmpty(),
                )
            }

        response.status shouldBeEqualTo HttpStatus.UNAUTHORIZED.value()
        response.correlationId shouldBeEqualTo "policy-auth-7"
        response.body.contains("\"errorCode\":\"UNAUTHORIZED\"").shouldBeTrue()
        response.body.contains("\"correlationId\":\"policy-auth-7\"").shouldBeTrue()
        response.body.contains(attackerToken).shouldBeFalse()
        response.body.contains("raw-claim-secret").shouldBeFalse()
        response.body.contains("parser").shouldBeFalse()
    }

    private data class AuthenticationFailureResponse(
        val status: Int,
        val correlationId: String?,
        val body: String,
    )
}
