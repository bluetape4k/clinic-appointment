package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.api.dto.ActivateSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.ApproveSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.CreateSchedulingPolicyDraftRequest
import io.bluetape4k.clinic.appointment.api.dto.EffectiveSchedulingPolicyApiResponse
import io.bluetape4k.clinic.appointment.api.dto.EffectiveSchedulingPolicyResponse
import io.bluetape4k.clinic.appointment.api.dto.PreviewSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ReplaySchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.RetireSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.ScheduleSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyActivationApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyActivationResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyApprovalApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyApprovalResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyMutationApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyMutationResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewResponse
import io.bluetape4k.clinic.appointment.api.dto.ValidateSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyAdministrationService
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * SaaS tenant baseline scheduling-policy 관리 API다.
 *
 * tenant database identity와 actor는 body에서 받지 않는다. [tenantCode]를 권위 tenant로
 * 해석하고 검증된 Gateway principal을 [ActorContext]로 변환한 뒤 application service에
 * 전달한다. controller는 Exposed transaction을 열거나 정책 lifecycle을 직접 판단하지 않는다.
 */
@Tag(name = "Tenant Scheduling Policies", description = "Tenant baseline policy lifecycle and effective reads")
@RestController
@ConditionalOnBean(SchedulingPolicyAdministrationService::class)
@RequestMapping("/api/{tenantCode}/admin/scheduling-policies")
class TenantSchedulingPolicyController(
    private val administrationService: SchedulingPolicyAdministrationService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
    private val actorContextResolver: ActorContextResolver,
    private val properties: SchedulingPolicyProperties,
) {

    @Operation(summary = "Create a tenant scheduling-policy draft")
    @PolicyMutationResponses
    @PostMapping("/drafts")
    fun createDraft(
        @PathVariable tenantCode: String,
        @RequestBody request: CreateSchedulingPolicyDraftRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<SchedulingPolicyMutationResponse>> {
        val context = context(tenantCode, authentication, servletRequest)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse.ok(administrationService.createDraft(context.scope, context.actor, request))
        )
    }

    @Operation(summary = "Validate the current tenant policy draft")
    @PolicyMutationResponses
    @PostMapping("/{id}/validate")
    fun validate(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
        @RequestBody request: ValidateSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<SchedulingPolicyMutationResponse> {
        val context = context(tenantCode, authentication, servletRequest)
        return ApiResponse.ok(administrationService.validate(context.scope, context.actor, id, request))
    }

    @Operation(summary = "Submit a bounded tenant policy impact preview")
    @ApiResponses(
        OpenApiResponse(
            responseCode = "200",
            description = "Preview completed synchronously",
            content = [Content(schema = Schema(implementation = SchedulingPolicyPreviewApiResponse::class))],
        ),
        OpenApiResponse(
            responseCode = "202",
            description = "Preview accepted for durable asynchronous processing",
            headers = [
                Header(name = HttpHeaders.LOCATION, schema = Schema(type = "string", format = "uri")),
                Header(name = HttpHeaders.RETRY_AFTER, schema = Schema(type = "integer", minimum = "1")),
            ],
            content = [Content(schema = Schema(implementation = SchedulingPolicyPreviewApiResponse::class))],
        ),
        OpenApiResponse(
            responseCode = "400",
            content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
        OpenApiResponse(
            responseCode = "409",
            content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
        OpenApiResponse(
            responseCode = "429",
            headers = [Header(name = HttpHeaders.RETRY_AFTER, schema = Schema(type = "integer"))],
            content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
    )
    @PostMapping("/{id}/preview")
    fun preview(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
        @RequestBody request: PreviewSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<SchedulingPolicyPreviewResponse>> {
        val context = context(tenantCode, authentication, servletRequest)
        val submission = administrationService.preview(context.scope, context.actor, id, request)
        if (!submission.asynchronous) {
            return ResponseEntity.ok(ApiResponse.ok(submission.response))
        }
        val location = URI.create(
            "/api/$tenantCode/admin/scheduling-policies/preview-jobs/${submission.response.jobId}"
        )
        return ResponseEntity.accepted()
            .location(location)
            .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds())
            .body(ApiResponse.ok(submission.response))
    }

    @Operation(summary = "Approve a tenant policy draft revision")
    @PolicyApprovalResponses
    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
        @RequestBody request: ApproveSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<SchedulingPolicyApprovalResponse> {
        val context = context(tenantCode, authentication, servletRequest)
        return ApiResponse.ok(administrationService.approve(context.scope, context.actor, id, request))
    }

    @Operation(summary = "Schedule a tenant policy activation")
    @PolicyScheduledActivationResponses
    @PostMapping("/{id}/schedule")
    fun schedule(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
        @RequestBody request: ScheduleSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<SchedulingPolicyActivationResponse>> {
        val context = context(tenantCode, authentication, servletRequest)
        return ResponseEntity.accepted().body(
            ApiResponse.ok(administrationService.schedule(context.scope, context.actor, id, request))
        )
    }

    @Operation(summary = "Activate a tenant policy immediately")
    @PolicyImmediateActivationResponses
    @PostMapping("/{id}/activate")
    fun activate(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
        @Parameter(
            name = "Idempotency-Key",
            description = "Opaque caller key for one immediate activation intent",
            required = true,
            `in` = ParameterIn.HEADER,
        )
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody request: ActivateSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<SchedulingPolicyActivationResponse> {
        val context = context(tenantCode, authentication, servletRequest)
        return ApiResponse.ok(
            administrationService.activate(context.scope, context.actor, id, idempotencyKey, request)
        )
    }

    @Operation(summary = "Retire a tenant policy definition")
    @PolicyMutationResponses
    @PostMapping("/{id}/retire")
    fun retire(
        @PathVariable tenantCode: String,
        @PathVariable id: Long,
        @RequestBody request: RetireSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<SchedulingPolicyMutationResponse> {
        val context = context(tenantCode, authentication, servletRequest)
        return ApiResponse.ok(administrationService.retire(context.scope, context.actor, id, request))
    }

    @Operation(summary = "Replay a missed tenant policy activation")
    @PolicyImmediateActivationResponses
    @PostMapping("/activation-commands/{commandId}/replay")
    fun replay(
        @PathVariable tenantCode: String,
        @PathVariable commandId: Long,
        @Parameter(
            name = "Idempotency-Key",
            description = "Fresh opaque caller key for the replay command",
            required = true,
            `in` = ParameterIn.HEADER,
        )
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody request: ReplaySchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<SchedulingPolicyActivationResponse> {
        val context = context(tenantCode, authentication, servletRequest)
        return ApiResponse.ok(
            administrationService.replay(
                context.scope,
                context.actor,
                commandId,
                idempotencyKey,
                request,
            )
        )
    }

    @Operation(
        summary = "Read the effective tenant baseline at explicit decision and service instants",
        parameters = [
            Parameter(name = "decisionAt", required = true, schema = Schema(type = "string", format = "date-time")),
            Parameter(name = "serviceAt", required = true, schema = Schema(type = "string", format = "date-time")),
        ],
    )
    @ApiResponses(
        OpenApiResponse(
            responseCode = "200",
            content = [Content(schema = Schema(implementation = EffectiveSchedulingPolicyApiResponse::class))],
        ),
        OpenApiResponse(
            responseCode = "400",
            content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
        OpenApiResponse(
            responseCode = "409",
            content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
        OpenApiResponse(
            responseCode = "503",
            headers = [Header(name = HttpHeaders.RETRY_AFTER, schema = Schema(type = "integer"))],
            content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
    )
    @GetMapping("/effective")
    fun effective(
        @PathVariable tenantCode: String,
        @RequestParam decisionAt: String,
        @RequestParam serviceAt: String,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<EffectiveSchedulingPolicyResponse> {
        val context = context(tenantCode, authentication, servletRequest)
        val decisionInstant = parseRequiredOffsetInstant(decisionAt)
        val serviceInstant = parseRequiredOffsetInstant(serviceAt)
        require(!serviceInstant.isBefore(decisionInstant)) { "serviceAt must not be before decisionAt" }
        return ApiResponse.ok(
            administrationService.tenantEffective(
                context.scope,
                context.actor,
                decisionInstant,
                serviceInstant,
            )
        )
    }

    private fun context(
        tenantCode: String,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): PolicyHttpContext {
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        val actor = actorContextResolver.resolve(
            authentication,
            tenantCode,
            clinicId = null,
            correlationId = request.correlationId(),
        )
        return PolicyHttpContext(
            PolicyScopeRef(tenant.id, PolicyScope.TENANT_DEFAULT),
            actor,
        )
    }

    private fun retryAfterSeconds(): String = properties.previewRetryAfterSeconds()
}

/** controller가 application service에 넘기는 신뢰된 scope/actor pair다. */
internal data class PolicyHttpContext(
    val scope: PolicyScopeRef,
    val actor: ActorContext,
)

/** RFC 3339 offset 또는 UTC timestamp만 허용하고 즉시 UTC [Instant]로 정규화한다. */
internal fun parseRequiredOffsetInstant(value: String): Instant =
    try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("Timestamp must be RFC 3339 with UTC or an explicit offset")
    }

/** 인증 전 filter가 수립한 correlation ID를 사용하고 테스트 경계에서만 안전한 fallback을 만든다. */
internal fun HttpServletRequest.correlationId(): String =
    getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as? String
        ?: UUID.randomUUID().toString()

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponses(
    OpenApiResponse(
        responseCode = "200",
        content = [Content(schema = Schema(implementation = SchedulingPolicyMutationApiResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "400",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "403",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "404",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "409",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
)
internal annotation class PolicyMutationResponses

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponses(
    OpenApiResponse(
        responseCode = "200",
        content = [Content(schema = Schema(implementation = SchedulingPolicyApprovalApiResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "400",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "403",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "404",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "409",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "422",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
)
internal annotation class PolicyApprovalResponses

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponses(
    OpenApiResponse(
        responseCode = "200",
        content = [Content(schema = Schema(implementation = SchedulingPolicyActivationApiResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "400",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "403",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "404",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "409",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "422",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
)
internal annotation class PolicyImmediateActivationResponses

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponses(
    OpenApiResponse(
        responseCode = "202",
        content = [Content(schema = Schema(implementation = SchedulingPolicyActivationApiResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "400",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "403",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "404",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "409",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
    OpenApiResponse(
        responseCode = "422",
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
)
internal annotation class PolicyScheduledActivationResponses

/**
 * HTTP `Retry-After`가 소수 초를 표현하지 못하므로 설정된 polling 간격을 올림한다.
 *
 * [SchedulingPolicyProperties]가 polling interval을 1ms 이상 1분 이하로 검증하므로
 * 계산 결과는 항상 `1..60`이고 overflow가 발생하지 않는다.
 */
internal fun SchedulingPolicyProperties.previewRetryAfterSeconds(): String =
    ((previewPollInterval.toMillis() + 999L) / 1_000L)
        .coerceAtLeast(1L)
        .toString()
