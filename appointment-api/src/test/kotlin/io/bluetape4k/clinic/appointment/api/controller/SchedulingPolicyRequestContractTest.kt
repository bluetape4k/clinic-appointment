package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.api.dto.ActivateSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ApproveSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.CreateSchedulingPolicyDraftRequest
import io.bluetape4k.clinic.appointment.api.dto.PolicyGenerationRequest
import io.bluetape4k.clinic.appointment.api.dto.PreviewSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ReplaySchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.RetireSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ScheduleSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ValidateSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.config.GlobalExceptionHandler
import io.bluetape4k.clinic.appointment.api.config.CatalogPayloadSizeFilter
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyApiException
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyErrorCode
import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.api.config.isSchedulingPolicyRequestPath
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import jakarta.servlet.FilterChain
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Duration

class SchedulingPolicyRequestContractTest {

    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    @Test
    fun `policy error envelope selector accepts only the two public admin route families`() {
        isSchedulingPolicyRequestPath("/api/tenant-a/admin/scheduling-policies/effective").shouldBeTrue()
        isSchedulingPolicyRequestPath(
            "/api/tenant-a/admin/clinics/41/scheduling-policies/preview-jobs/7"
        ).shouldBeTrue()
        isSchedulingPolicyRequestPath("/api/tenant-a/scheduling-policies/effective").shouldBeFalse()
        isSchedulingPolicyRequestPath("/api/tenant-a/admin/not-scheduling-policies/effective").shouldBeFalse()
        isSchedulingPolicyRequestPath("/internal/scheduling-policies").shouldBeFalse()
    }

    @Test
    fun `policy requests cannot carry identity scope or escalation fields`() {
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
            "assurance",
        )
        listOf(
            CreateSchedulingPolicyDraftRequest::class,
            ValidateSchedulingPolicyRequest::class,
            PreviewSchedulingPolicyRequest::class,
            ApproveSchedulingPolicyRequest::class,
            ScheduleSchedulingPolicyRequest::class,
            ActivateSchedulingPolicyRequest::class,
            RetireSchedulingPolicyRequest::class,
            ReplaySchedulingPolicyRequest::class,
        ).forEach { requestType ->
            requestType.members.any { it.name in forbidden }.shouldBeFalse()
        }
    }

    @Test
    fun `unknown escalation field is rejected even when the shared mapper is permissive`() {
        val body = """
            {
              "expectedDraftRevision": 3,
              "expectedGeneration": {
                "tenantGeneration": 7,
                "clinicGeneration": 0
              },
              "actorType": "ADMIN"
            }
        """.trimIndent()

        runCatching {
            mapper.readValue(body, PreviewSchedulingPolicyRequest::class.java)
        }.isFailure.shouldBeTrue()
    }

    @Test
    fun `nested generation object also rejects escalation fields`() {
        val body = """
            {
              "expectedDraftRevision": 3,
              "expectedGeneration": {
                "tenantGeneration": 7,
                "clinicGeneration": 0,
                "clinicId": 999
              }
            }
        """.trimIndent()

        runCatching {
            mapper.readValue(body, PreviewSchedulingPolicyRequest::class.java)
        }.isFailure.shouldBeTrue()
    }

    @Test
    fun `generation request keeps tenant and clinic counters explicit`() {
        val generation = mapper.readValue(
            """{"tenantGeneration":7,"clinicGeneration":2}""",
            PolicyGenerationRequest::class.java,
        )

        (generation.tenantGeneration == 7L).shouldBeTrue()
        (generation.clinicGeneration == 2L).shouldBeTrue()
    }

    @Test
    fun `notification SLA payload는 재평가 목표 필드를 선택적으로 전달한다`() {
        val withTargets = mapper.readValue(
            """
            {
              "kind":"NOTIFICATION_AND_SLA",
              "schemaVersion":1,
              "effectiveFrom":"2026-07-30T00:00:00Z",
              "effectiveUntil":null,
              "payload":{
                "profileReevaluationHeldTargetSeconds":120,
                "profileReevaluationProposedTargetSeconds":600
              },
              "expectedScopeRevision":0,
              "changeReason":"병원 처리 목표 적용"
            }
            """.trimIndent(),
            CreateSchedulingPolicyDraftRequest::class.java,
        )
        val withoutTargets = mapper.readValue(
            """
            {
              "kind":"NOTIFICATION_AND_SLA",
              "schemaVersion":1,
              "effectiveFrom":"2026-07-30T00:00:00Z",
              "effectiveUntil":null,
              "payload":{},
              "expectedScopeRevision":0,
              "changeReason":"기존 계약 유지"
            }
            """.trimIndent(),
            CreateSchedulingPolicyDraftRequest::class.java,
        )

        withTargets.payload.has("profileReevaluationHeldTargetSeconds").shouldBeTrue()
        withoutTargets.payload.has("profileReevaluationHeldTargetSeconds").shouldBeFalse()
    }

    @Test
    fun `policy validation and retryable errors use stable sanitized envelope`() {
        val request = MockHttpServletRequest(
            "POST",
            "/api/clinic-a/admin/scheduling-policies/7/preview",
        ).apply {
            setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "corr-error")
        }
        val handler = GlobalExceptionHandler(
            SchedulingPolicyProperties(previewPollInterval = Duration.ofMillis(1_500))
        )

        val invalid = handler.handleIllegalArgument(
            IllegalArgumentException("secret-payload-detail"),
            request,
        )
        (invalid.statusCode.value() == 400).shouldBeTrue()
        val invalidBody = invalid.body.toString()
        invalidBody.contains("POLICY_PAYLOAD_INVALID").shouldBeTrue()
        invalidBody.contains("secret-payload-detail").shouldBeFalse()

        val limited = handler.handleSchedulingPolicy(
            SchedulingPolicyApiException(
                SchedulingPolicyErrorCode.POLICY_PREVIEW_LIMITED,
                "internal queue measurement",
            ),
            request,
        )
        (limited.statusCode.value() == 429).shouldBeTrue()
        (limited.headers.getFirst(HttpHeaders.RETRY_AFTER) == "2").shouldBeTrue()
        val body = limited.body.shouldNotBeNull()
        body.retryable.shouldBeTrue()
        body.correlationId.contains("corr-error").shouldBeTrue()
    }

    @Test
    fun `oversized policy envelope is rejected before controller deserialization`() {
        val request = MockHttpServletRequest(
            "POST",
            "/api/clinic-a/admin/scheduling-policies/drafts",
        ).apply {
            setContent(ByteArray(300 * 1_024) { 'x'.code.toByte() })
        }
        val response = MockHttpServletResponse()
        var invoked = false

        CatalogPayloadSizeFilter().doFilter(
            request,
            response,
            FilterChain { _, _ -> invoked = true },
        )

        invoked.shouldBeFalse()
        (response.status == 400).shouldBeTrue()
        response.contentAsString.contains("POLICY_PAYLOAD_INVALID").shouldBeTrue()
        response.contentAsString.contains("\"retryable\":false").shouldBeTrue()
    }
}
