package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.config.SchedulingPolicyProperties
import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewApiResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingPolicyPreviewResponse
import io.bluetape4k.clinic.appointment.api.policy.SchedulingPolicyAdministrationService
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * durable scheduling-policy preview job을 exact tenant/clinic scope에서 조회한다.
 *
 * job ID는 전역 surrogate key지만 소유권은 path scope다. controller가 검증한
 * [PolicyScopeRef]를 application service에 전달하면 repository가
 * `(tenant_group_id, scope, clinic_scope_key, id)`를 한 SQL predicate로 비교한다.
 * 따라서 다른 tenant/clinic의 job 존재 여부를 응답이나 추가 조회 횟수로 노출하지 않는다.
 *
 * `PENDING|RUNNING`은 서버 설정으로 계산한 `Retry-After`를 매번 반환한다. application
 * 계층의 per-scope/job limiter가 그보다 빠른 polling을 429로 거절하며, 이 GET은 impact
 * scan을 재실행하지 않고 저장된 진행률만 읽는다.
 */
@Tag(name = "Scheduling Policy Preview Jobs", description = "Scoped durable preview polling")
@RestController
@ConditionalOnBean(SchedulingPolicyAdministrationService::class)
class SchedulingPolicyPreviewJobController(
    private val administrationService: SchedulingPolicyAdministrationService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
    private val actorContextResolver: ActorContextResolver,
    private val properties: SchedulingPolicyProperties,
) {

    @Operation(summary = "Poll a tenant scheduling-policy preview job")
    @PreviewJobResponses
    @GetMapping("/api/{tenantCode}/admin/scheduling-policies/preview-jobs/{jobId}")
    fun tenantJob(
        @PathVariable tenantCode: String,
        @PathVariable jobId: Long,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<SchedulingPolicyPreviewResponse>> {
        val tenant = tenantClinicAccessChecker.requireTenant(tenantCode)
        val actor = actorContextResolver.resolve(
            authentication,
            tenantCode,
            clinicId = null,
            correlationId = servletRequest.correlationId(),
        )
        return previewResponse(
            administrationService.previewJob(
                PolicyScopeRef(tenant.id, PolicyScope.TENANT_DEFAULT),
                actor,
                jobId,
            )
        )
    }

    @Operation(summary = "Poll a clinic scheduling-policy preview job")
    @PreviewJobResponses
    @GetMapping("/api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/preview-jobs/{jobId}")
    fun clinicJob(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable jobId: Long,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<SchedulingPolicyPreviewResponse>> {
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val actor = actorContextResolver.resolve(
            authentication,
            tenantCode,
            clinicId,
            servletRequest.correlationId(),
        )
        return previewResponse(
            administrationService.previewJob(
                PolicyScopeRef(tenant.id, PolicyScope.CLINIC_OVERRIDE, clinicId),
                actor,
                jobId,
            )
        )
    }

    /** 비종결 job에만 polling hint를 붙이고 모든 상태를 200 projection으로 반환한다. */
    private fun previewResponse(
        response: SchedulingPolicyPreviewResponse,
    ): ResponseEntity<ApiResponse<SchedulingPolicyPreviewResponse>> {
        val builder = ResponseEntity.ok()
        if (response.status == PolicyPreviewJobStatus.PENDING ||
            response.status == PolicyPreviewJobStatus.RUNNING
        ) {
            builder.header(HttpHeaders.RETRY_AFTER, properties.previewRetryAfterSeconds())
        }
        return builder.body(ApiResponse.ok(response))
    }
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponses(
    OpenApiResponse(
        responseCode = "200",
        headers = [
            Header(
                name = HttpHeaders.RETRY_AFTER,
                description = "Present for PENDING or RUNNING jobs",
                schema = Schema(type = "integer", minimum = "1"),
            ),
        ],
        content = [Content(schema = Schema(implementation = SchedulingPolicyPreviewApiResponse::class))],
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
        responseCode = "429",
        headers = [Header(name = HttpHeaders.RETRY_AFTER, schema = Schema(type = "integer", minimum = "1"))],
        content = [Content(schema = Schema(implementation = SchedulingApiErrorResponse::class))],
    ),
)
internal annotation class PreviewJobResponses
