package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.ApiResponse
import io.bluetape4k.clinic.appointment.api.dto.NotificationRecommendedActionCode
import io.bluetape4k.clinic.appointment.api.dto.NotificationStatusCode
import io.bluetape4k.clinic.appointment.api.dto.NotificationStatusResponse
import io.bluetape4k.clinic.appointment.api.dto.ReNotifyRequest
import io.bluetape4k.clinic.appointment.api.dto.ReNotifyResponse
import io.bluetape4k.clinic.appointment.api.notification.NotificationReNotifyCommand
import io.bluetape4k.clinic.appointment.api.notification.NotificationReNotifyService
import io.bluetape4k.clinic.appointment.api.notification.toApprovalReference
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.notification.NotificationStatusAudience
import io.bluetape4k.clinic.appointment.notification.NotificationStatusQueryService
import io.bluetape4k.clinic.appointment.notification.NotificationStatusScope
import io.bluetape4k.support.requirePositiveNumber
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody as OpenApiRequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse as OpenApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 알림 운영 상태 조회와 제한된 수동 재알림 API입니다.
 *
 * controller는 path tenant·clinic 검증과 principal 전달만 담당합니다. appointment 소유권,
 * 현재 consent/template 적합성, 재알림 제외 정책은 하위 port와 service가 같은 scope로 다시
 * 검증합니다.
 */
@RestController
@RequestMapping("/api/{tenantCode}/clinics/{clinicId}/notifications")
@Tag(name = "Notification Operations", description = "Privacy-safe notification status and controlled re-notification")
class NotificationOperationsController(
    private val statusQueryServiceProvider: ObjectProvider<NotificationStatusQueryService>,
    private val reNotifyServiceProvider: ObjectProvider<NotificationReNotifyService>,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
) {

    @Operation(
        summary = "Read a privacy-safe appointment notification status",
        description = "Returns a bounded status and recommended action without member, destination, outbox, attempt, or provider identifiers.",
    )
    @ApiResponses(
        OpenApiResponse(responseCode = "200", description = "Notification status found", content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "exhausted", value = NotificationOpenApiExamples.EXHAUSTED_STATUS)])]),
        OpenApiResponse(responseCode = "403", description = "Tenant, clinic, or appointment scope rejected"),
        OpenApiResponse(responseCode = "404", description = "Notification status not found"),
        OpenApiResponse(responseCode = "503", description = "Notification operation is unavailable", headers = [Header(name = "Retry-After", description = "Seconds before retrying", schema = Schema(type = "integer", example = "5"))], content = [Content(mediaType = "application/json", schema = Schema(implementation = io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse::class), examples = [ExampleObject(name = "operationUnavailable", value = NotificationOpenApiExamples.NOTIFICATION_OPERATION_UNAVAILABLE)])]),
    )
    @GetMapping("/appointments/{appointmentId}/status")
    suspend fun getStatus(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @PathVariable appointmentId: Long,
        @RequestParam(defaultValue = "STAFF") audience: NotificationStatusAudience,
    ): ApiResponse<NotificationStatusResponse> {
        appointmentId.requirePositiveNumber("appointmentId")
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val statusQueryService = statusQueryServiceProvider.ifAvailable
            ?: throw NotificationOperationUnavailableException()
        val view = statusQueryService.find(
            scope = NotificationStatusScope(
                tenantGroupId = tenant.id,
                clinicId = clinicId,
                appointmentId = appointmentId,
            ),
            audience = audience,
        ) ?: throw NoSuchElementException("Notification status not found")
        return ApiResponse.ok(
            NotificationStatusResponse(
                status = NotificationStatusCode.valueOf(view.status.name),
                reasonCode = view.reasonCode,
                nextAttemptAt = view.nextAttemptAt,
                exhaustedAt = view.exhaustedAt,
                recommendedAction = NotificationRecommendedActionCode.valueOf(view.recommendedAction.name),
                patientVisible = view.patientVisible,
            )
        )
    }

    @Operation(
        summary = "Preview or execute a controlled re-notification batch",
        description = "Requires platform and clinic approval references. Start with dryRun=true; reuse the same generation when executing the approved batch.",
    )
    @ApiResponses(
        OpenApiResponse(responseCode = "200", description = "Re-notification batch evaluated", content = [Content(mediaType = "application/json", examples = [ExampleObject(name = "dryRun", value = NotificationOpenApiExamples.RE_NOTIFY_DRY_RUN_RESPONSE)])]),
        OpenApiResponse(responseCode = "400", description = "Invalid or duplicate appointment identifiers"),
        OpenApiResponse(responseCode = "403", description = "Required role, scope, or clinic authority missing"),
        OpenApiResponse(responseCode = "503", description = "Notification operation is unavailable", headers = [Header(name = "Retry-After", description = "Seconds before retrying", schema = Schema(type = "integer", example = "5"))], content = [Content(mediaType = "application/json", schema = Schema(implementation = io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse::class), examples = [ExampleObject(name = "operationUnavailable", value = NotificationOpenApiExamples.NOTIFICATION_OPERATION_UNAVAILABLE)])]),
    )
    @PostMapping("/re-notify")
    suspend fun reNotify(
        @PathVariable tenantCode: String,
        @PathVariable clinicId: Long,
        @OpenApiRequestBody(
            required = true,
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = ReNotifyRequest::class),
                examples = [ExampleObject(name = "dryRun", value = NotificationOpenApiExamples.RE_NOTIFY_DRY_RUN_REQUEST)],
            )],
        )
        @Valid @RequestBody request: ReNotifyRequest,
        authentication: Authentication,
    ): ApiResponse<ReNotifyResponse> {
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val reNotifyService = reNotifyServiceProvider.ifAvailable
            ?: throw NotificationOperationUnavailableException()
        require(request.appointmentIds.size == request.appointmentIds.toSet().size) {
            "appointmentIds must be unique"
        }
        val principal = authentication.principal as? SchedulingUserPrincipal
            ?: throw org.springframework.security.access.AccessDeniedException("principal required")
        val result = reNotifyService.reNotify(
            NotificationReNotifyCommand(
                tenantGroupId = tenant.id,
                clinicId = clinicId,
                appointmentIds = request.appointmentIds.toSet(),
                generation = request.generation,
                platformApproval = request.platformApproval.toApprovalReference(),
                clinicApproval = request.clinicApproval.toApprovalReference(),
                dryRun = request.dryRun,
                actor = principal,
            )
        )
        return ApiResponse.ok(
            ReNotifyResponse(
                generation = result.generation,
                dryRun = result.dryRun,
                requestedCount = result.requestedCount,
                acceptedCount = result.acceptedCount,
                skippedCount = result.skippedCount,
                skippedReasons = result.skippedReasons,
            )
        )
    }
}

/** rollout 또는 필수 adapter 미구성으로 알림 운영 기능을 제공할 수 없음을 나타냅니다. */
class NotificationOperationUnavailableException : RuntimeException("Notification operation is unavailable") {
    companion object {
        private const val serialVersionUID = 1L
    }
}
