package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.clinic.appointment.api.controller.execute
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * commitment v2 path가 Gateway JWT envelope와 actor role을 controller 전에 강제하는지 검증한다.
 *
 * v2 application bean은 Task 9 feature wiring 전에는 등록되지 않는다. 이 검사는 endpoint
 * 업무 결과가 아니라 Security filter가 누락된 envelope, 관리자 위장, 서비스 principal을
 * fail-closed로 거절하는 transport 경계만 검증한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "appointment.commitment.api-enabled=true",
        "appointment.commitment.idempotency-hash-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    ],
)
@ActiveProfiles("test", "integration-test")
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class AppointmentCommitmentSecurityIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @MockitoBean
    private lateinit var appointmentCommitmentApplicationService: AppointmentCommitmentApplicationService

    @LocalServerPort
    private var port: Int = 0

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `missing Gateway envelope is unauthorized`() {
        val response = request("/api/v2/appointment-requests", token = null)

        response.statusCode shouldBeEqualTo HttpStatus.UNAUTHORIZED
        response.jsonPath<String>("$.errorCode") shouldBeEqualTo "UNAUTHORIZED"
        response.jsonPath<String>("$.correlationId").isNotBlank().shouldBeTrue()
    }

    @Test
    fun `workforce and service principals cannot impersonate a patient request`() {
        listOf(
            TestJwtProvider.adminToken(clinicId = 7L),
            TestJwtProvider.createToken(
                userId = "scheduling-service",
                clinicId = 7L,
                roles = listOf(SchedulingRole.SYSTEM),
                actorType = ActorType.SYSTEM,
                allowedClinicIds = setOf(7L),
                assurance = AuthenticationAssurance.SERVICE,
            ),
        ).forEach { token ->
            val response = request("/api/v2/appointment-requests", token)
            response.statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
            response.jsonPath<String>("$.errorCode") shouldBeEqualTo "SCOPE_FORBIDDEN"
        }
    }

    @Test
    fun `patient and service principals cannot invoke administrator creation`() {
        val patientToken = TestJwtProvider.createToken(
            userId = "patient-user",
            clinicId = 7L,
            roles = listOf(SchedulingRole.PATIENT),
            actorType = ActorType.PATIENT,
            allowedClinicIds = setOf(7L),
            patientSubjectId = "patient-subject-7",
        )
        val systemToken = TestJwtProvider.createToken(
            userId = "scheduling-service",
            clinicId = 7L,
            roles = listOf(SchedulingRole.SYSTEM),
            actorType = ActorType.SYSTEM,
            allowedClinicIds = setOf(7L),
            assurance = AuthenticationAssurance.SERVICE,
        )

        listOf(patientToken, systemToken).forEach { token ->
            val response = request("/api/v2/admin/appointments", token)
            response.statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
            response.jsonPath<String>("$.errorCode") shouldBeEqualTo "SCOPE_FORBIDDEN"
        }
    }

    @Test
    fun `request body cannot inject actor tenant clinic or policy fields`() {
        val patientToken = TestJwtProvider.createToken(
            userId = "patient-user",
            clinicId = 7L,
            roles = listOf(SchedulingRole.PATIENT),
            actorType = ActorType.PATIENT,
            allowedClinicIds = setOf(7L),
            patientSubjectId = "patient-subject-7",
        )
        val response = request(
            path = "/api/v2/appointment-requests",
            token = patientToken,
            body = """
                {
                  "appointmentPlanId": 101,
                  "preferredStartAt": "2026-08-01T01:00:00Z",
                  "preferredEndAt": "2026-08-01T02:00:00Z",
                  "actorId": "forged-admin",
                  "clinicId": 999,
                  "policyMode": "DIRECT_CONFIRM",
                  "evidence": {
                    "evidenceAuthority": "tenant-default:consent-service",
                    "evidenceId": "ev_01J1M6Y6XRK8N0W2M3P4Q5R6S7"
                  }
                }
            """.trimIndent(),
            extraHeaders = mapOf(
                "Idempotency-Key" to "request_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
                HttpHeaders.IF_NONE_MATCH to "*",
            ),
        )

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        response.jsonPath<String>("$.errorCode") shouldBeEqualTo "PAYLOAD_INVALID"
    }

    @Test
    fun `OpenAPI publishes provisional consent expiry conflict and ETag mutation contract`() {
        val content = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .body(String::class.java)
            .orEmpty()
        val root = mapper.readTree(content)

        content shouldContain "/api/v2/appointment-requests"
        content shouldContain "/api/v2/appointments/{id}/approve"
        content shouldContain "/api/v2/appointments/{id}/proposals/{proposalId}/accept"
        content shouldContain "PROPOSED"
        content shouldContain "expired"
        content shouldContain "conflict"

        assertRequiredHeaders(
            root,
            "/api/v2/appointment-requests",
            "Idempotency-Key",
            HttpHeaders.IF_NONE_MATCH,
        )
        assertRequiredHeaders(
            root,
            "/api/v2/admin/appointments",
            "Idempotency-Key",
            HttpHeaders.IF_NONE_MATCH,
        )
        listOf(
            "/api/v2/appointments/{id}/approve",
            "/api/v2/appointments/{id}/confirm",
            "/api/v2/appointments/{id}/change-proposals",
            "/api/v2/appointments/{id}/proposals/{proposalId}/accept",
            "/api/v2/appointments/{id}/proposals/{proposalId}/decline",
        ).forEach { path ->
            assertRequiredHeaders(root, path, "Idempotency-Key", HttpHeaders.IF_MATCH)
        }

        assertErrorResponses(
            root,
            "/api/v2/appointment-requests",
            successCode = "202",
            expectedErrorCodes = listOf("400", "401", "403", "409", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/v2/admin/appointments",
            successCode = "201",
            expectedErrorCodes = listOf("400", "401", "403", "409", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/v2/appointments/{id}/approve",
            successCode = "200",
            expectedErrorCodes = listOf("400", "401", "403", "404", "409", "410", "412", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/v2/appointments/{id}/confirm",
            successCode = "200",
            expectedErrorCodes = listOf("400", "401", "403", "404", "409", "410", "412", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/v2/appointments/{id}/change-proposals",
            successCode = "202",
            expectedErrorCodes = listOf("400", "401", "403", "404", "409", "412", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/v2/appointments/{id}/proposals/{proposalId}/accept",
            successCode = "200",
            expectedErrorCodes = listOf("400", "401", "403", "404", "409", "410", "412", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/v2/appointments/{id}/proposals/{proposalId}/decline",
            successCode = "200",
            expectedErrorCodes = listOf("400", "401", "403", "404", "409", "410", "412", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/v2/appointments/{id}/commitment",
            successCode = "200",
            expectedErrorCodes = listOf("400", "401", "403", "404", "500"),
            method = "get",
        )

        val errorProperties = root.at("/components/schemas/SchedulingApiErrorResponse/properties")
        listOf("errorCode", "correlationId", "retryable", "action").forEach { property ->
            errorProperties.has(property).shouldBeTrue()
        }
    }

    /**
     * Spring MVC에서는 필수 header 누락을 공통 `428`로 정규화하기 위해 nullable로 받지만,
     * 생성된 OpenAPI는 caller가 실행 전에 계약을 알 수 있도록 필수 header로 선언해야 한다.
     */
    private fun assertRequiredHeaders(
        root: JsonNode,
        path: String,
        vararg expectedHeaders: String,
    ) {
        val parameters = root.at("/paths/${pointer(path)}/post/parameters")
            .associateBy { it.path("name").stringValue() }
        expectedHeaders.forEach { header ->
            val parameter = requireNotNull(parameters[header]) { "$path must publish $header" }
            parameter.path("in").stringValue() shouldBeEqualTo "header"
            parameter.path("required").asBoolean().shouldBeTrue()
        }
    }

    /**
     * 각 operation이 안정 오류 code뿐 아니라 실제 공통 오류 envelope schema까지
     * 연결하는지 검증합니다. 문서상 status만 있고 body 계약이 빠지는 회귀를 막습니다.
     */
    private fun assertErrorResponses(
        root: JsonNode,
        path: String,
        successCode: String,
        expectedErrorCodes: List<String>,
        method: String = "post",
    ) {
        val responses = root.at("/paths/${pointer(path)}/$method/responses")
        responses.has(successCode).shouldBeTrue()
        expectedErrorCodes.forEach { responseCode ->
            val response = responses.path(responseCode)
            response.isMissingNode.shouldBeEqualTo(false)
            response
                .at("/content/application~1json/schema/\$ref")
                .stringValue() shouldBeEqualTo "#/components/schemas/SchedulingApiErrorResponse"
        }
    }

    /** RFC 6901 JSON pointer segment escaping for an OpenAPI path key. */
    private fun pointer(value: String): String =
        value.replace("~", "~0").replace("/", "~1")

    private fun request(
        path: String,
        token: String?,
        body: String = "{}",
        extraHeaders: Map<String, String> = emptyMap(),
    ) = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .build()
        .post()
        .uri(path)
        .apply {
            if (token != null) {
                header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }
            extraHeaders.forEach(::header)
        }
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .execute()
}
