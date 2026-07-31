package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentCommitmentResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentProposalResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.ApproveProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CancelAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateChangeProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectConfirmRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectCreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Gateway가 인증한 병원 관리자의 직접 생성·승인·변경 제안 API이다.
 *
 * 관리자도 정책 mode, 동의 허용 유형, 약관 hash, 담당자·자원 mapping을 body로 선택할
 * 수 없다. application resolver가 actor의 단일 tenant·clinic scope에서 이를 결정한다.
 */
@Tag(name = "Appointment Commitments - Administrator")
@RestController
@RequestMapping("/api/v2")
@ConditionalOnProperty(
    prefix = "appointment.commitment",
    name = ["api-enabled"],
    havingValue = "true",
)
class AdminAppointmentV2Controller(
    private val service: AppointmentCommitmentApplicationService,
    private val actorContextResolver: ActorContextResolver,
    @Value("\${appointment.commitment.ingress-enabled:true}")
    private val ingressEnabled: Boolean = true,
) {

    @Operation(
        summary = "Create and confirm an administrator appointment",
        description = "Direct confirmation succeeds only when the effective clinic policy permits it.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Appointment created and confirmed"),
        ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "Missing or invalid Gateway identity", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "Administrator, member, or clinic scope rejected", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Plan member not found", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "Policy, idempotency, resource, or ambiguous member reference conflict", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "422", description = "No feasible proposal or plan limit exceeded", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "428", description = "Creation precondition missing", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "500", description = "Internal scheduling error", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "503", description = "New appointment intake is disabled or the member directory is unavailable", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @PostMapping("/admin/appointments")
    fun directCreate(
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
        @Parameter(required = true, example = "direct_01J1M6Y6XRK8N0W2M3P4Q5R6S7")
        @RequestHeader("Idempotency-Key", required = false)
        idempotencyKey: String?,
        @Parameter(required = true, example = "*")
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false)
        ifNoneMatch: String?,
        @Valid @RequestBody request: DirectCreateAppointmentRequest,
    ): ResponseEntity<AppointmentCommitmentResponse> {
        requireAppointmentIngress(ingressEnabled)
        val actor = actorContextResolver.resolveAppointmentActor(authentication, servletRequest)
            .requireAdminActor()
        return service.directCreate(
            actor,
            requireIdempotencyKey(idempotencyKey),
            requireCreateOnly(ifNoneMatch),
            request,
        ).createdResponse()
    }

    @Operation(
        summary = "Approve a customer proposal",
        description = "Confirms the exact customer-consented proposal. Use the latest ETag in If-Match.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Customer proposal approved"),
        ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "Missing or invalid Gateway identity", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "Administrator or clinic scope rejected", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Commitment or proposal not found", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "Consent, proposal, or resource conflict", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "410", description = "Proposal or consent expired", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "412", description = "ETag does not match current version", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "422", description = "Consent evidence required or invalid", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "428", description = "Mutation precondition missing", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "500", description = "Internal scheduling error", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @PostMapping("/appointments/{id}/approve")
    fun approveProposal(
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
        @PathVariable id: Long,
        @Parameter(required = true, example = "approve_01J1M6Y6XRK8N0W2M3P4Q5R6S7")
        @RequestHeader("Idempotency-Key", required = false)
        idempotencyKey: String?,
        @Parameter(required = true, example = "\"1\"")
        @RequestHeader(HttpHeaders.IF_MATCH, required = false)
        ifMatch: String?,
        @Valid @RequestBody request: ApproveProposalRequest,
    ): ResponseEntity<AppointmentCommitmentResponse> {
        val actor = actorContextResolver.resolveAppointmentActor(authentication, servletRequest)
            .requireAdminActor()
        return service.approveProposal(
            actor,
            id,
            requireExpectedVersion(ifMatch),
            requireIdempotencyKey(idempotencyKey),
            request,
        ).okResponse()
    }

    @Operation(summary = "Directly confirm the selected proposal")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Selected proposal confirmed"),
        ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "Missing or invalid Gateway identity", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "Administrator or clinic scope rejected", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Commitment or proposal not found", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "Policy, proposal, or resource conflict", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "410", description = "Proposal expired", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "412", description = "ETag does not match current version", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "422", description = "Consent evidence required or invalid", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "428", description = "Mutation precondition missing", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "500", description = "Internal scheduling error", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @PostMapping("/appointments/{id}/confirm")
    fun directConfirm(
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
        @PathVariable id: Long,
        @Parameter(required = true, example = "confirm_01J1M6Y6XRK8N0W2M3P4Q5R6S7")
        @RequestHeader("Idempotency-Key", required = false)
        idempotencyKey: String?,
        @Parameter(required = true, example = "\"2\"")
        @RequestHeader(HttpHeaders.IF_MATCH, required = false)
        ifMatch: String?,
        @Valid @RequestBody request: DirectConfirmRequest,
    ): ResponseEntity<AppointmentCommitmentResponse> {
        val actor = actorContextResolver.resolveAppointmentActor(authentication, servletRequest)
            .requireAdminActor()
        return service.directConfirm(
            actor,
            id,
            requireExpectedVersion(ifMatch),
            requireIdempotencyKey(idempotencyKey),
            request,
        ).okResponse()
    }

    @Operation(
        summary = "Expire a proposal that reached its server-side deadline",
        description = "Releases a held initial proposal or preserves the current confirmation for an expired change proposal.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Proposal expiry recorded"),
        ApiResponse(responseCode = "401", description = "Missing or invalid Gateway identity"),
        ApiResponse(responseCode = "403", description = "Administrator or clinic scope rejected"),
        ApiResponse(responseCode = "404", description = "Commitment or proposal not found"),
        ApiResponse(responseCode = "409", description = "Proposal is not current or already expired"),
        ApiResponse(responseCode = "412", description = "ETag does not match current version"),
        ApiResponse(responseCode = "428", description = "Mutation precondition missing"),
        ApiResponse(responseCode = "500", description = "Internal scheduling error"),
    )
    @PostMapping("/appointments/{id}/proposals/{proposalId}/expire")
    fun expireProposal(
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
        @PathVariable id: Long,
        @PathVariable proposalId: Long,
        @Parameter(required = true)
        @RequestHeader("Idempotency-Key", required = false)
        idempotencyKey: String?,
        @Parameter(required = true)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false)
        ifMatch: String?,
    ): ResponseEntity<AppointmentCommitmentResponse> {
        val actor = actorContextResolver.resolveAppointmentActor(authentication, servletRequest)
            .requireAdminActor()
        return service.expireProposal(
            actor,
            id,
            proposalId,
            requireExpectedVersion(ifMatch),
            requireIdempotencyKey(idempotencyKey),
        ).okResponse()
    }

    @Operation(
        summary = "Cancel a proposed, held, or confirmed appointment",
        description = "Releases active allocations and records only a registered cancellation reason code.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Appointment cancelled"),
        ApiResponse(responseCode = "400", description = "Invalid cancellation reason"),
        ApiResponse(responseCode = "401", description = "Missing or invalid Gateway identity"),
        ApiResponse(responseCode = "403", description = "Administrator or clinic scope rejected"),
        ApiResponse(responseCode = "404", description = "Commitment not found"),
        ApiResponse(responseCode = "409", description = "Appointment cannot be cancelled from its current state"),
        ApiResponse(responseCode = "412", description = "ETag does not match current version"),
        ApiResponse(responseCode = "428", description = "Mutation precondition missing"),
        ApiResponse(responseCode = "500", description = "Internal scheduling error"),
    )
    @PostMapping("/appointments/{id}/cancel")
    fun cancelAppointment(
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
        @PathVariable id: Long,
        @Parameter(required = true)
        @RequestHeader("Idempotency-Key", required = false)
        idempotencyKey: String?,
        @Parameter(required = true)
        @RequestHeader(HttpHeaders.IF_MATCH, required = false)
        ifMatch: String?,
        @Valid @RequestBody request: CancelAppointmentRequest,
    ): ResponseEntity<AppointmentCommitmentResponse> {
        val actor = actorContextResolver.resolveAppointmentActor(authentication, servletRequest)
            .requireAdminActor()
        return service.cancelAppointment(
            actor,
            id,
            requireExpectedVersion(ifMatch),
            requireIdempotencyKey(idempotencyKey),
            request,
        ).okResponse()
    }

    @Operation(
        summary = "Create a replacement proposal",
        description = "Keeps the confirmed appointment until the patient accepts this proposal.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Replacement proposal created"),
        ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "Missing or invalid Gateway identity", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "Administrator or clinic scope rejected", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Commitment not found", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "Proposal or idempotency conflict", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "412", description = "ETag does not match current version", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "422", description = "No feasible proposal or plan limit exceeded", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "428", description = "Mutation precondition missing", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "500", description = "Internal scheduling error", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @PostMapping("/appointments/{id}/change-proposals")
    fun createChangeProposal(
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
        @PathVariable id: Long,
        @Parameter(required = true, example = "change_01J1M6Y6XRK8N0W2M3P4Q5R6S7")
        @RequestHeader("Idempotency-Key", required = false)
        idempotencyKey: String?,
        @Parameter(required = true, example = "\"2\"")
        @RequestHeader(HttpHeaders.IF_MATCH, required = false)
        ifMatch: String?,
        @Valid @RequestBody request: CreateChangeProposalRequest,
    ): ResponseEntity<AppointmentProposalResponse> {
        val actor = actorContextResolver.resolveAppointmentActor(authentication, servletRequest)
            .requireAdminActor()
        return service.createChangeProposal(
            actor,
            id,
            requireExpectedVersion(ifMatch),
            requireIdempotencyKey(idempotencyKey),
            request,
        ).acceptedResponse()
    }
}
