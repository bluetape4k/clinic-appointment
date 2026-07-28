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
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyActivationResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyMutationResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewResponse
import io.bluetape4k.clinic.appointment.api.dto.ValidateSchedulingPolicyRequest
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyAdministrationService
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
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

/**
 * 한 병원의 partial override를 관리하는 clinic-scoped scheduling-policy API다.
 *
 * [tenantCode]와 [clinicId]는 body 값보다 먼저 데이터베이스 소유관계와 Gateway principal
 * membership으로 검증한다. 검증된 path만 [PolicyScope.CLINIC_OVERRIDE]를 구성하므로
 * 다른 병원의 definition, preview job, activation command ID를 넣어도 application 계층의
 * exact-scope 조회가 `POLICY_RESOURCE_NOT_FOUND`로 숨긴다.
 *
 * controller는 HTTP status/header, 엄격한 request DTO, 명시적 시각 정규화만 담당한다.
 * override 허용 여부, revision/generation CAS, preview 증거, 승인과 활성화 lifecycle은
 * [SchedulingPolicyAdministrationService]가 기존 transactional 명령 서비스에 위임한다.
 */
@Tag(name = "Clinic Scheduling Policies", description = "Clinic override policy lifecycle and effective reads")
@RestController
@ConditionalOnBean(SchedulingPolicyAdministrationService::class)
@RequestMapping("/api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies")
class ClinicSchedulingPolicyController(
    private val administrationService: SchedulingPolicyAdministrationService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
    private val actorContextResolver: ActorContextResolver,
    private val properties: SchedulingPolicyProperties,
) {

    @Operation(summary = "Create a clinic scheduling-policy override draft")
    @PolicyMutationResponses
    @PostMapping("/drafts")
    fun createDraft(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @RequestBody request: CreateSchedulingPolicyDraftRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<SchedulingPolicyMutationResponse>> {
        val context = context(tenantCode, clinicId, authentication, servletRequest)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse.ok(administrationService.createDraft(context.scope, context.actor, request))
        )
    }

    @Operation(summary = "Validate the current clinic policy override draft")
    @PolicyMutationResponses
    @PostMapping("/{id}/validate")
    fun validate(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable id: Long,
        @RequestBody request: ValidateSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<SchedulingPolicyMutationResponse> {
        val context = context(tenantCode, clinicId, authentication, servletRequest)
        return ApiResponse.ok(administrationService.validate(context.scope, context.actor, id, request))
    }

    @Operation(summary = "Submit a bounded clinic policy impact preview")
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
        @PathVariable clinicId: Long,
        @PathVariable id: Long,
        @RequestBody request: PreviewSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<SchedulingPolicyPreviewResponse>> {
        val context = context(tenantCode, clinicId, authentication, servletRequest)
        val submission = administrationService.preview(context.scope, context.actor, id, request)
        if (!submission.asynchronous) {
            return ResponseEntity.ok(ApiResponse.ok(submission.response))
        }
        val location = URI.create(
            "/api/$tenantCode/admin/clinics/$clinicId/scheduling-policies/" +
                "preview-jobs/${submission.response.jobId}"
        )
        return ResponseEntity.accepted()
            .location(location)
            .header(HttpHeaders.RETRY_AFTER, properties.previewRetryAfterSeconds())
            .body(ApiResponse.ok(submission.response))
    }

    @Operation(summary = "Approve a clinic policy override draft revision")
    @PolicyApprovalResponses
    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable id: Long,
        @RequestBody request: ApproveSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ) = context(tenantCode, clinicId, authentication, servletRequest).let { context ->
        ApiResponse.ok(administrationService.approve(context.scope, context.actor, id, request))
    }

    @Operation(summary = "Schedule a clinic policy override activation")
    @PolicyScheduledActivationResponses
    @PostMapping("/{id}/schedule")
    fun schedule(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable id: Long,
        @RequestBody request: ScheduleSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ) = context(tenantCode, clinicId, authentication, servletRequest).let { context ->
        ResponseEntity.accepted().body(
            ApiResponse.ok(administrationService.schedule(context.scope, context.actor, id, request))
        )
    }

    @Operation(summary = "Activate a clinic policy override immediately")
    @PolicyImmediateActivationResponses
    @PostMapping("/{id}/activate")
    fun activate(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
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
    ) = context(tenantCode, clinicId, authentication, servletRequest).let { context ->
        ApiResponse.ok(
            administrationService.activate(context.scope, context.actor, id, idempotencyKey, request)
        )
    }

    @Operation(summary = "Retire a clinic policy override definition")
    @PolicyMutationResponses
    @PostMapping("/{id}/retire")
    fun retire(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable id: Long,
        @RequestBody request: RetireSchedulingPolicyRequest,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ) = context(tenantCode, clinicId, authentication, servletRequest).let { context ->
        ApiResponse.ok(administrationService.retire(context.scope, context.actor, id, request))
    }

    @Operation(summary = "Replay a missed clinic policy activation")
    @PolicyImmediateActivationResponses
    @PostMapping("/activation-commands/{commandId}/replay")
    fun replay(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
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
    ) = context(tenantCode, clinicId, authentication, servletRequest).let { context ->
        ApiResponse.ok(
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
        summary = "Read the resolved clinic policy at explicit decision and service instants",
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
        @PathVariable clinicId: Long,
        @RequestParam decisionAt: String,
        @RequestParam serviceAt: String,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ApiResponse<EffectiveSchedulingPolicyResponse> {
        val context = context(tenantCode, clinicId, authentication, servletRequest)
        val decisionInstant = parseRequiredOffsetInstant(decisionAt)
        val serviceInstant = parseRequiredOffsetInstant(serviceAt)
        require(!serviceInstant.isBefore(decisionInstant)) { "serviceAt must not be before decisionAt" }
        return ApiResponse.ok(
            administrationService.clinicEffective(
                context.scope,
                context.actor,
                decisionInstant,
                serviceInstant,
            )
        )
    }

    /**
     * path tenant/clinic ownership과 principal membership을 모두 통과한 scope를 만든다.
     *
     * DB 검사는 clinic ID가 tenant에 속하는지 확인하고, resolver 검사는 같은 clinic이
     * JWT allow-list에도 있는지 확인한다. 둘 중 하나라도 실패하면 application service는
     * 호출되지 않는다.
     */
    private fun context(
        tenantCode: String,
        clinicId: Long,
        authentication: Authentication?,
        request: HttpServletRequest,
    ): PolicyHttpContext {
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val actor = actorContextResolver.resolve(
            authentication,
            tenantCode,
            clinicId,
            request.correlationId(),
        )
        return PolicyHttpContext(
            scope = PolicyScopeRef(tenant.id, PolicyScope.CLINIC_OVERRIDE, clinicId),
            actor = actor,
        )
    }
}
