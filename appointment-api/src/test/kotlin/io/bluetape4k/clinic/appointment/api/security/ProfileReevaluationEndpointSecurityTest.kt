package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationEndpoint
import io.bluetape4k.clinic.appointment.api.profile.PROFILE_REEVALUATION_OPERATE_SCOPE
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "integration-test")
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ProfileReevaluationEndpointSecurityTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @jakarta.annotation.Resource
    private lateinit var applicationContext: ApplicationContext

    private lateinit var client: RestClient

    @BeforeEach
    fun setUp() {
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
    }

    @Test
    fun `재평가 actuator는 admin만 접근하고 일반 API에는 노출하지 않는다`() {
        applicationContext.getBeansOfType(ProfileReevaluationEndpoint::class.java).size shouldBeEqualTo 1
        val operatorToken =
            token(
                SchedulingRole.ADMIN,
                ActorType.ADMIN,
                scopes = setOf(PROFILE_REEVALUATION_OPERATE_SCOPE),
            )
        status("/actuator/profileReevaluation") shouldBeEqualTo HttpStatus.UNAUTHORIZED
        status(
            "/actuator/profileReevaluation",
            token(SchedulingRole.STAFF, ActorType.STAFF),
        ) shouldBeEqualTo HttpStatus.FORBIDDEN
        status(
            "/actuator/profileReevaluation",
            token(SchedulingRole.ADMIN, ActorType.ADMIN),
        ) shouldBeEqualTo HttpStatus.FORBIDDEN
        status(
            "/actuator/profileReevaluation",
            operatorToken,
        ) shouldBeEqualTo HttpStatus.OK
        body("/actuator/profileReevaluation", operatorToken)["drainState"] shouldBeEqualTo "DRAINED"
        status(
            "/api/v2/profileReevaluation",
            token(SchedulingRole.ADMIN, ActorType.ADMIN),
        ) shouldBeEqualTo HttpStatus.NOT_FOUND
    }

    private fun status(
        path: String,
        token: String? = null,
    ): HttpStatus =
        client.get()
            .uri(path)
            .apply {
                if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }
            .exchange { _, response -> HttpStatus.valueOf(response.statusCode.value()) }

    private fun body(
        path: String,
        token: String,
    ): Map<String, Any> =
        requireNotNull(
            client.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(object : ParameterizedTypeReference<Map<String, Any>>() {}),
        )

    private fun token(
        role: String,
        actorType: ActorType,
        scopes: Set<String> = emptySet(),
    ): String =
        TestJwtProvider.createToken(
            userId = "profile-ops",
            clinicId = null,
            roles = listOf(role),
            actorType = actorType,
            allowedClinicIds = emptySet(),
            scopes = scopes,
        )
}
