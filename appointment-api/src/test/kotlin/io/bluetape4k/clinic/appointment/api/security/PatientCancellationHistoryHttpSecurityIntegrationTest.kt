package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.controller.execute
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

/** 실제 servlet/security chain에서 환자 취소 이력 route의 인증·인가 경계를 검증합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "integration-test")
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PatientCancellationHistoryHttpSecurityIntegrationTest {

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
    fun `환자 취소 이력 route는 익명과 workforce를 거절하고 환자 요청만 controller 경계까지 전달한다`() {
        val client = RestClient.builder().baseUrl("http://localhost:$port").build()

        request(client, token = null).also { response ->
            response.statusCode shouldBeEqualTo HttpStatus.UNAUTHORIZED
            response.jsonPath<String>("$.errorCode") shouldBeEqualTo "UNAUTHORIZED"
            response.jsonPath<String>("$.correlationId").isNotBlank().shouldBeTrue()
        }

        request(client, token = TestJwtProvider.staffToken()).also { response ->
            response.statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
            response.jsonPath<String>("$.errorCode") shouldBeEqualTo "PATIENT_HISTORY_SCOPE_FORBIDDEN"
            response.jsonPath<String>("$.correlationId").isNotBlank().shouldBeTrue()
        }

        request(client, token = TestJwtProvider.patientToken()).also { response ->
            // test profile에서는 patient-history.api-enabled이 꺼져 있으므로 security를 통과한
            // 요청이 controller의 sanitized unavailable 경계까지 도달해야 합니다.
            response.statusCode shouldBeEqualTo HttpStatus.SERVICE_UNAVAILABLE
            response.jsonPath<String>("$.errorCode") shouldBeEqualTo "PATIENT_HISTORY_UNAVAILABLE"
            response.headers.getFirst(HttpHeaders.RETRY_AFTER) shouldBeEqualTo "1"
        }
    }

    private fun request(client: RestClient, token: String?) =
        client.get()
            .uri("/api/${TenantGroups.DEFAULT_TENANT_CODE}/patient/appointments/cancellation-history")
            .apply {
                token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
            }
            .execute()
}
