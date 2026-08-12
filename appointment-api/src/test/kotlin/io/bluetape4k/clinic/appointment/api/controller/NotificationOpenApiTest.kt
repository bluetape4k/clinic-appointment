package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.notification.NotificationMemberApiError
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/** 실행 중 Springdoc이 게시한 알림·회원 식별 계약과 개인정보 경계를 검증합니다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "appointment.commitment.api-enabled=true",
        "appointment.commitment.idempotency-hash-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    ],
)
@ActiveProfiles("test", "integration-test")
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class NotificationOpenApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @MockitoBean
    private lateinit var commitmentService: AppointmentCommitmentApplicationService

    @LocalServerPort
    private var port: Int = 0

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `예약 진입점은 실제 회원 오류 registry와 privacy-safe 예시를 게시한다`() {
        val root = openApi()
        val legacy = operation(root, "/api/{tenantCode}/appointments", "post")
        val customer = operation(root, "/api/{tenantCode}/appointment-requests", "post")
        val administrator = operation(root, "/api/{tenantCode}/admin/appointments", "post")

        val legacyErrors = mapOf(
            "403" to NotificationMemberApiError.MEMBER_SCOPE_MISMATCH,
            "404" to NotificationMemberApiError.MEMBER_NOT_FOUND,
            "409" to NotificationMemberApiError.MEMBER_REFERENCE_AMBIGUOUS,
            "422" to NotificationMemberApiError.MEMBER_ID_REQUIRED,
            "503" to NotificationMemberApiError.MEMBER_DIRECTORY_UNAVAILABLE,
        )
        legacyErrors.forEach { (status, error) ->
            assertMemberErrorExample(legacy, status, error)
        }
        listOf(customer, administrator).forEach { create ->
            mapOf(
                "403" to NotificationMemberApiError.MEMBER_SCOPE_MISMATCH,
                "404" to NotificationMemberApiError.MEMBER_NOT_FOUND,
                "409" to NotificationMemberApiError.MEMBER_REFERENCE_AMBIGUOUS,
                "503" to NotificationMemberApiError.MEMBER_DIRECTORY_UNAVAILABLE,
            ).forEach { (status, error) -> assertMemberErrorExample(create, status, error) }
            create.path("responses").path("503").path("headers").has("Retry-After").shouldBeTrue()
            create.path("description").stringValue().contains("never carries a member identifier").shouldBeTrue()
        }
        legacy.path("responses").path("503").path("headers").has("Retry-After").shouldBeTrue()
        legacy.path("description").stringValue().contains("requires a verified memberId by default").shouldBeTrue()
        legacy.path("description").stringValue().contains("expiring clinic-scoped OBSERVE").shouldBeTrue()
        val legacyMemberId = root.at("/components/schemas/CreateAppointmentRequest/properties/memberId")
        legacyMemberId.path("description").stringValue().contains("required by default").shouldBeTrue()
        legacyMemberId.path("description").stringValue().contains("OBSERVE transition exception").shouldBeTrue()

        val examples = root.findValues("examples").joinToString("\n")
        listOf("memberId", "patientExternalId", "recipient", "phone", "email", "payloadJson").forEach {
            examples.contains(it, ignoreCase = true).shouldBeFalse()
        }
    }

    @Test
    fun `운영 API는 EXHAUSTED 상태와 re-notify dry-run 예시를 게시한다`() {
        val root = openApi()
        val status = operation(
            root,
            "/api/{tenantCode}/clinics/{clinicId}/notifications/appointments/{appointmentId}/status",
            "get",
        )
        val exhausted = firstExample(status.path("responses").path("200"))
        exhausted.at("/data/status").stringValue() shouldBeEqualTo "EXHAUSTED"
        exhausted.at("/data/recommendedAction").stringValue() shouldBeEqualTo "CONTACT_NOTIFICATION_SUPPORT"
        exhausted.at("/data/patientVisible").asBoolean() shouldBeEqualTo false

        val reNotify = operation(root, "/api/{tenantCode}/clinics/{clinicId}/notifications/re-notify", "post")
        val request = firstExample(reNotify.path("requestBody"))
        request.path("dryRun").asBoolean() shouldBeEqualTo true
        request.path("platformApproval").path("authority").stringValue() shouldBeEqualTo "notification-platform"
        request.path("clinicApproval").path("authority").stringValue() shouldBeEqualTo "clinic-operations"
        val response = firstExample(reNotify.path("responses").path("200"))
        response.at("/data/dryRun").asBoolean() shouldBeEqualTo true
        response.at("/data/requestedCount").asInt() shouldBeEqualTo 2
        val reNotifySchema = root.at("/components/schemas/ReNotifyRequest/properties")
        reNotifySchema.path("appointmentIds").path("minItems").asInt() shouldBeEqualTo 1
        reNotifySchema.path("appointmentIds").path("maxItems").asInt() shouldBeEqualTo 100
        reNotifySchema.path("appointmentIds").path("uniqueItems").asBoolean() shouldBeEqualTo true
        reNotifySchema.path("appointmentIds").path("items").path("minimum").asInt() shouldBeEqualTo 1
        reNotifySchema.path("generation").path("maxLength").asInt() shouldBeEqualTo 128
        reNotifySchema.path("generation").path("pattern").stringValue() shouldBeEqualTo
            "[A-Za-z0-9][A-Za-z0-9._:-]*"
        val approvalSchema = root.at("/components/schemas/ApprovalReferenceRequest/properties")
        listOf("authority", "reference").forEach { property ->
            approvalSchema.path(property).path("maxLength").asInt() shouldBeEqualTo 128
            approvalSchema.path(property).path("pattern").stringValue() shouldBeEqualTo
                "[A-Za-z0-9][A-Za-z0-9._:-]*"
        }
        listOf(status, reNotify).forEach { operation ->
            operation.path("responses").path("503").path("headers").has("Retry-After").shouldBeTrue()
            operation.path("responses").path("503").at("/content/application~1json/schema/\$ref")
                .stringValue().endsWith("/SchedulingApiErrorResponse").shouldBeTrue()
        }
    }

    private fun openApi(): JsonNode {
        val content = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .body(String::class.java)
            .orEmpty()
        val report = Path.of("build/reports/openapi/notification.json")
        Files.createDirectories(report.parent)
        Files.writeString(report, content)
        return mapper.readTree(content)
    }

    private fun operation(root: JsonNode, path: String, method: String): JsonNode =
        root.at("/paths/${pointer(path)}/$method").also { it.isMissingNode.shouldBeFalse() }

    private fun assertMemberErrorExample(operation: JsonNode, status: String, error: NotificationMemberApiError) {
        val response = operation.path("responses").path(status)
        response.at("/content/application~1json/schema/\$ref")
            .stringValue().endsWith("/SchedulingApiErrorResponse").shouldBeTrue()
        val example = firstExample(response)
        example.path("errorCode").stringValue() shouldBeEqualTo error.name
        example.path("error").stringValue() shouldBeEqualTo error.safeMessage
        example.path("retryable").asBoolean() shouldBeEqualTo error.retryable
        example.path("action").stringValue() shouldBeEqualTo error.action
        example.path("correlationId").stringValue().isNotBlank().shouldBeTrue()
    }

    private fun firstExample(container: JsonNode): JsonNode =
        container.at("/content/application~1json/examples")
            .properties()
            .asSequence()
            .first()
            .value
            .path("value")

    /** RFC 6901 JSON pointer segment escaping을 적용합니다. */
    private fun pointer(value: String): String = value.replace("~", "~0").replace("/", "~1")
}
