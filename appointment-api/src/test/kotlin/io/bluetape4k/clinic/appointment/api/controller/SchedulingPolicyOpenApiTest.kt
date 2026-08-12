package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * source annotation이 아니라 실행 중 Springdoc이 생성한 scheduling-policy 계약을 검증한다.
 *
 * 이 테스트가 남기는 JSON은 PR review에서 route/status/schema drift를 재현하는 진단
 * artifact다. identity와 raw secret 입력이 schema에 들어오는 순간 테스트가 실패한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "scheduling.policy.shadow-compile-enabled=true",
        "scheduling.policy.effective-read-enabled=true",
        "scheduling.policy.admin-write-enabled=true",
        "scheduling.policy.idempotency-hash-secret=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
    ],
)
@ActiveProfiles("test")
@Import(SchedulingPolicyOpenApiTest.OpenApiTestConfiguration::class)
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SchedulingPolicyOpenApiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `generated contract publishes exact scoped routes without authority escalation fields`() {
        val content = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .body(String::class.java)
            .orEmpty()
        val report = Path.of("build/reports/openapi/scheduling-policy.json")
        Files.createDirectories(report.parent)
        Files.writeString(report, content)
        val root = mapper.readTree(content)

        val tenantBase = "/api/{tenantCode}/admin/scheduling-policies"
        val clinicBase = "/api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies"
        val suffixes = listOf(
            "/drafts" to "post",
            "/{id}/validate" to "post",
            "/{id}/preview" to "post",
            "/preview-jobs/{jobId}" to "get",
            "/{id}/approve" to "post",
            "/{id}/schedule" to "post",
            "/{id}/activate" to "post",
            "/{id}/retire" to "post",
            "/activation-commands/{commandId}/replay" to "post",
            "/effective" to "get",
        )
        listOf(tenantBase, clinicBase).forEach { base ->
            suffixes.forEach { (suffix, method) ->
                root.at("/paths/${pointer(base + suffix)}/$method").shouldNotBeNull()
                root.at("/paths/${pointer(base + suffix)}/$method").isMissingNode.shouldBeFalse()
            }
        }

        listOf(tenantBase, clinicBase).forEach { base ->
            val effective = root.at("/paths/${pointer("$base/effective")}/get")
            val parameters = effective.path("parameters").associateBy { it.path("name").stringValue() }
            listOf("decisionAt", "serviceAt").forEach { name ->
                parameters[name]?.path("required")?.asBoolean().shouldBeTrue()
                parameters[name]?.path("schema")?.path("format")?.stringValue() shouldBeEqualTo "date-time"
            }
            effective.path("responses").has("400").shouldBeTrue()
            effective.path("responses").has("409").shouldBeTrue()
            effective.path("responses").has("503").shouldBeTrue()
        }

        listOf(tenantBase, clinicBase).forEach { base ->
            val previewResponses = root.at("/paths/${pointer("$base/{id}/preview")}/post/responses")
            previewResponses.has("200").shouldBeTrue()
            previewResponses.has("202").shouldBeTrue()
            previewResponses.path("202").path("headers").has(HttpHeader.LOCATION).shouldBeTrue()
            previewResponses.path("202").path("headers").has(HttpHeader.RETRY_AFTER).shouldBeTrue()
            val pollingHeaders = root.at(
                "/paths/${pointer("$base/preview-jobs/{jobId}")}/get/responses/200/headers"
            )
            pollingHeaders.has(HttpHeader.RETRY_AFTER).shouldBeTrue()

            listOf("/{id}/activate", "/activation-commands/{commandId}/replay").forEach { suffix ->
                val parameters = root.at("/paths/${pointer(base + suffix)}/post/parameters")
                    .associateBy { it.path("name").stringValue() }
                val idempotency = requireNotNull(parameters[HttpHeader.IDEMPOTENCY_KEY]) { "$base$suffix" }
                idempotency.path("required").asBoolean().shouldBeTrue()
                idempotency.path("in").stringValue() shouldBeEqualTo "header"
            }

            val approvalResponses = root.at("/paths/${pointer("$base/{id}/approve")}/post/responses")
            listOf("200", "400", "403", "404", "409", "422").forEach {
                approvalResponses.has(it).shouldBeTrue()
            }
            val scheduledResponses = root.at("/paths/${pointer("$base/{id}/schedule")}/post/responses")
            scheduledResponses.has("200").shouldBeFalse()
            listOf("202", "400", "403", "404", "409", "422").forEach {
                scheduledResponses.has(it).shouldBeTrue()
            }
            listOf("/{id}/activate", "/activation-commands/{commandId}/replay").forEach { suffix ->
                val responses = root.at("/paths/${pointer(base + suffix)}/post/responses")
                responses.has("202").shouldBeFalse()
                listOf("200", "400", "403", "404", "409", "422").forEach {
                    responses.has(it).shouldBeTrue()
                }
            }
        }

        val schemas = root.at("/components/schemas")
        listOf(
            "CreateSchedulingPolicyDraftRequest",
            "ValidateSchedulingPolicyRequest",
            "PreviewSchedulingPolicyRequest",
            "ApproveSchedulingPolicyRequest",
            "ScheduleSchedulingPolicyRequest",
            "ActivateSchedulingPolicyRequest",
            "RetireSchedulingPolicyRequest",
            "ReplaySchedulingPolicyRequest",
            "SchedulingPolicyPreviewResponse",
            "EffectiveSchedulingPolicyResponse",
            "SchedulingApiErrorResponse",
        ).forEach { schemaName ->
            schemas.path(schemaName).isMissingNode.shouldBeFalse()
        }

        val forbidden = setOf(
            "actor",
            "actorType",
            "role",
            "roles",
            "tenantId",
            "tenantGroupId",
            "clinicId",
            "patientSubject",
            "patientSubjectId",
            "bookingOrigin",
            "jwt",
            "token",
            "idempotencyKey",
        )
        val requestSchemas = schemas.properties().asSequence()
            .filter { (name, _) -> name.endsWith("SchedulingPolicyRequest") }
            .map { it.value }
            .toList()
        requestSchemas.forEach { schema ->
            val propertyNames = schema.path("properties").properties().asSequence().map { it.key }.toSet()
            propertyNames.intersect(forbidden).isEmpty().shouldBeTrue()
        }

        val draftPayload = schemas.path("CreateSchedulingPolicyDraftRequest")
            .path("properties")
            .path("payload")
        draftPayload.path("description").stringValue()
            .contains("profileReevaluationHeldTargetSeconds").shouldBeTrue()
        val effectiveTargets = schemas.path("EffectiveSchedulingPolicyResponse")
            .path("properties")
            .path("profileReevaluationTargets")
        effectiveTargets.isMissingNode.shouldBeFalse()
        val targetSchemaName = effectiveTargets.path("\$ref").stringValue().substringAfterLast("/")
        val targetFields = schemas.path(targetSchemaName).path("properties")
        listOf(
            "heldTargetSeconds",
            "heldSource",
            "proposedTargetSeconds",
            "proposedSource",
        ).forEach { targetFields.has(it).shouldBeTrue() }

        val errorFields = schemas.path("SchedulingApiErrorResponse").path("properties")
        listOf("errorCode", "correlationId", "retryable", "action").forEach {
            errorFields.has(it).shouldBeTrue()
        }
    }

    /** OpenAPI 경로 키에 적용하는 RFC 6901 JSON Pointer 세그먼트 이스케이프. */
    private fun pointer(value: String): String =
        value.replace("~", "~0").replace("/", "~1")

    private object HttpHeader {
        const val LOCATION = "Location"
        const val RETRY_AFTER = "Retry-After"
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }

    @TestConfiguration(proxyBeanMethods = false)
    class OpenApiTestConfiguration {
        /** no-op security profile에는 없는 controller constructor dependency만 제공한다. */
        @Bean
        fun actorContextResolver(): ActorContextResolver = ActorContextResolver()
    }
}
