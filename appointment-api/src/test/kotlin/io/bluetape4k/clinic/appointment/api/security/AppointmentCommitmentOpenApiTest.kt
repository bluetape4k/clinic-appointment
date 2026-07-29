package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

/**
 * 실행 중 Springdoc 계약이 고객 가예약→승인·수락→확정 흐름과 Gateway 인증 경계를
 * 외부 caller가 이해할 수 있도록 노출하는지 검증한다.
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
class AppointmentCommitmentOpenApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @MockitoBean
    private lateinit var service: AppointmentCommitmentApplicationService

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `publishes exact paths preconditions and bounded error contracts`() {
        val content = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .body(String::class.java)
            .orEmpty()
        val root = JsonMapper.builder().build().readTree(content)

        val create = root.at("/paths/${pointer("/api/v2/appointment-requests")}/post")
        create.isMissingNode.shouldBeFalse()
        create.path("responses").has("202").shouldBeTrue()
        listOf("400", "401", "403", "409", "422", "428", "500", "503").forEach { code ->
            create.path("responses").has(code).shouldBeTrue()
        }
        val createHeaders = create.path("parameters").associateBy { it.path("name").stringValue() }
        createHeaders["Idempotency-Key"]?.path("required")?.asBoolean() shouldBeEqualTo true
        createHeaders[HttpHeaders.IF_NONE_MATCH]?.path("required")?.asBoolean() shouldBeEqualTo true

        val directCreate = root.at("/paths/${pointer("/api/v2/admin/appointments")}/post")
        directCreate.isMissingNode.shouldBeFalse()
        directCreate.path("responses").has("201").shouldBeTrue()
        directCreate.path("responses").has("503").shouldBeTrue()

        val query = root.at("/paths/${pointer("/api/v2/appointments/{id}/commitment")}/get")
        query.isMissingNode.shouldBeFalse()
        val querySuccess = query.path("responses").path("200")
        querySuccess.path("headers").has(HttpHeaders.ETAG).shouldBeTrue()
        querySuccess.at("/content/application~1json/schema/\$ref").stringValue()
            .endsWith("/AppointmentCommitmentResponse").shouldBeTrue()

        listOf(
            "/api/v2/appointments/{id}/approve",
            "/api/v2/appointments/{id}/confirm",
            "/api/v2/appointments/{id}/change-proposals",
            "/api/v2/appointments/{id}/cancel",
            "/api/v2/appointments/{id}/proposals/{proposalId}/expire",
            "/api/v2/appointments/{id}/proposals/{proposalId}/accept",
            "/api/v2/appointments/{id}/proposals/{proposalId}/decline",
        ).forEach { path ->
            val operation = root.at("/paths/${pointer(path)}/post")
            operation.isMissingNode.shouldBeFalse()
            val headers = operation.path("parameters").associateBy { it.path("name").stringValue() }
            headers["Idempotency-Key"]?.path("required")?.asBoolean() shouldBeEqualTo true
            headers[HttpHeaders.IF_MATCH]?.path("required")?.asBoolean() shouldBeEqualTo true
        }

        val createSchema = root.at("/components/schemas/CreateAppointmentRequestV2")
        createSchema.path("required").toList().map { it.stringValue() }.toSet() shouldBeEqualTo
            setOf("appointmentPlanId", "preferredStartAt", "preferredEndAt", "evidence")
        val evidenceSchema = root.at("/components/schemas/ConsentEvidenceRequest")
        evidenceSchema.path("required").toList().map { it.stringValue() }.toSet() shouldBeEqualTo
            setOf("evidenceAuthority", "evidenceId")
        root.at("/components/schemas/AppointmentProposalSummary/properties/policySnapshot/\$ref")
            .stringValue()
            .endsWith("/AppointmentPolicySnapshotSummary")
            .shouldBeTrue()
    }

    /** RFC 6901 JSON pointer segment escaping을 적용한다. */
    private fun pointer(value: String): String =
        value.replace("~", "~0").replace("/", "~1")
}
