package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
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
                assertNotNull(root.at("/paths/${pointer(base + suffix)}/$method"), "$method $base$suffix")
                assertFalse(root.at("/paths/${pointer(base + suffix)}/$method").isMissingNode)
            }
        }

        listOf(tenantBase, clinicBase).forEach { base ->
            val effective = root.at("/paths/${pointer("$base/effective")}/get")
            val parameters = effective.path("parameters").associateBy { it.path("name").stringValue() }
            listOf("decisionAt", "serviceAt").forEach { name ->
                assertEquals(true, parameters[name]?.path("required")?.asBoolean(), name)
                assertEquals("date-time", parameters[name]?.path("schema")?.path("format")?.stringValue(), name)
            }
            assertTrue(effective.path("responses").has("400"))
            assertTrue(effective.path("responses").has("409"))
            assertTrue(effective.path("responses").has("503"))
        }

        listOf(tenantBase, clinicBase).forEach { base ->
            val previewResponses = root.at("/paths/${pointer("$base/{id}/preview")}/post/responses")
            assertTrue(previewResponses.has("200"))
            assertTrue(previewResponses.has("202"))
            assertTrue(previewResponses.path("202").path("headers").has(HttpHeader.LOCATION))
            assertTrue(previewResponses.path("202").path("headers").has(HttpHeader.RETRY_AFTER))
            val pollingHeaders = root.at(
                "/paths/${pointer("$base/preview-jobs/{jobId}")}/get/responses/200/headers"
            )
            assertTrue(pollingHeaders.has(HttpHeader.RETRY_AFTER))

            listOf("/{id}/activate", "/activation-commands/{commandId}/replay").forEach { suffix ->
                val parameters = root.at("/paths/${pointer(base + suffix)}/post/parameters")
                    .associateBy { it.path("name").stringValue() }
                val idempotency = requireNotNull(parameters[HttpHeader.IDEMPOTENCY_KEY]) { "$base$suffix" }
                assertEquals(true, idempotency.path("required").asBoolean(), "$base$suffix")
                assertEquals("header", idempotency.path("in").stringValue(), "$base$suffix")
            }

            val approvalResponses = root.at("/paths/${pointer("$base/{id}/approve")}/post/responses")
            listOf("200", "400", "403", "404", "409", "422").forEach {
                assertTrue(approvalResponses.has(it), "$base approve $it")
            }
            val scheduledResponses = root.at("/paths/${pointer("$base/{id}/schedule")}/post/responses")
            assertFalse(scheduledResponses.has("200"), "$base schedule must not advertise 200")
            listOf("202", "400", "403", "404", "409", "422").forEach {
                assertTrue(scheduledResponses.has(it), "$base schedule $it")
            }
            listOf("/{id}/activate", "/activation-commands/{commandId}/replay").forEach { suffix ->
                val responses = root.at("/paths/${pointer(base + suffix)}/post/responses")
                assertFalse(responses.has("202"), "$base$suffix must not advertise 202")
                listOf("200", "400", "403", "404", "409", "422").forEach {
                    assertTrue(responses.has(it), "$base$suffix $it")
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
            assertFalse(schemas.path(schemaName).isMissingNode, schemaName)
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
            assertTrue(propertyNames.intersect(forbidden).isEmpty(), propertyNames.toString())
        }

        val draftPayload = schemas.path("CreateSchedulingPolicyDraftRequest")
            .path("properties")
            .path("payload")
        assertTrue(
            draftPayload.path("description").stringValue()
                .contains("profileReevaluationHeldTargetSeconds"),
            draftPayload.toString(),
        )
        val effectiveTargets = schemas.path("EffectiveSchedulingPolicyResponse")
            .path("properties")
            .path("profileReevaluationTargets")
        assertFalse(effectiveTargets.isMissingNode, "profileReevaluationTargets")
        val targetSchemaName = effectiveTargets.path("\$ref").stringValue().substringAfterLast("/")
        val targetFields = schemas.path(targetSchemaName).path("properties")
        listOf(
            "heldTargetSeconds",
            "heldSource",
            "proposedTargetSeconds",
            "proposedSource",
        ).forEach { assertTrue(targetFields.has(it), it) }

        val errorFields = schemas.path("SchedulingApiErrorResponse").path("properties")
        listOf("errorCode", "correlationId", "retryable", "action").forEach {
            assertTrue(errorFields.has(it), it)
        }
    }

    /** RFC 6901 JSON pointer segment escaping for an OpenAPI path key. */
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
