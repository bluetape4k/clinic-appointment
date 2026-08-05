package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.api.controller.execute
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

/**
 * Task 9 wiring 전 기본 설정이 commitment controller와 OpenAPI를 노출하지 않는지 검증한다.
 *
 * 이미 v2 row가 생성된 운영 환경의 rollback은 이 전체 flag를 끄지 않고
 * `appointment.commitment.ingress-enabled=false`만 사용해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "integration-test")
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class AppointmentCommitmentFeatureOffIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `default configuration exposes neither commitment handler nor OpenAPI contract`() {
        val client = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()
        val token = TestJwtProvider.createToken(
            userId = "patient-user",
            clinicId = 7L,
            roles = listOf(SchedulingRole.PATIENT),
            actorType = ActorType.PATIENT,
            allowedClinicIds = setOf(7L),
            patientSubjectId = "patient-subject-7",
        )

        val route = client.get()
            .uri("/api/tenant-default/appointments/7/commitment")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .execute()
        val openApi = client.get()
            .uri("/v3/api-docs")
            .execute()

        route.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
        openApi.statusCode shouldBeEqualTo HttpStatus.OK
        openApi.body shouldNotContain "/api/{tenantCode}/appointment-requests"
        openApi.body shouldNotContain "/api/{tenantCode}/appointments/{id}/commitment"
    }
}
