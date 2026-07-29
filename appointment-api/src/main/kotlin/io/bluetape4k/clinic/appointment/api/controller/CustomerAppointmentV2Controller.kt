package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentCommitmentResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentProposalResponse
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateAppointmentRequestV2
import io.bluetape4k.clinic.appointment.api.dto.commitment.DeclineProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.ProposalDecisionRequest
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
 * Gateway가 인증한 고객이 가예약을 만들고 변경 proposal을 결정하는 v2 API이다.
 *
 * 인증 actor와 SaaS scope는 [ActorContextResolver]에서만 얻는다. body는 일정 의도와
 * opaque 동의 증빙만 표현하며 HTTP 멱등성·version precondition은 header로 분리한다.
 */
@Tag(name = "Appointment Commitments - Customer")
@RestController
@RequestMapping("/api/v2")
@ConditionalOnProperty(
    prefix = "appointment.commitment",
    name = ["api-enabled"],
    havingValue = "true",
)
class CustomerAppointmentV2Controller(
    private val service: AppointmentCommitmentApplicationService,
    private val actorContextResolver: ActorContextResolver,
    @Value("\${appointment.commitment.ingress-enabled:true}")
    private val ingressEnabled: Boolean = true,
) {

    @Operation(
        summary = "Request a provisional appointment",
        description = "Creates a PROPOSED appointment from the authenticated patient subject. " +
            "Send If-None-Match: * and reuse Idempotency-Key for safe retries.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Provisional appointment created"),
        ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "Missing or invalid Gateway identity", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "Patient or clinic scope rejected", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "Idempotency or resource conflict", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "422", description = "No feasible proposal or plan limit exceeded", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "428", description = "Creation precondition missing", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "500", description = "Internal scheduling error", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @PostMapping("/appointment-requests")
    fun requestAppointment(
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
        @Parameter(required = true, example = "request_01J1M6Y6XRK8N0W2M3P4Q5R6S7")
        @RequestHeader("Idempotency-Key", required = false)
        idempotencyKey: String?,
        @Parameter(required = true, example = "*")
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false)
        ifNoneMatch: String?,
        @Valid @RequestBody request: CreateAppointmentRequestV2,
    ): ResponseEntity<AppointmentProposalResponse> {
        requireAppointmentIngress(ingressEnabled)
        val actor = actorContextResolver.resolveAppointmentActor(authentication, servletRequest)
            .requirePatientActor()
        return service.requestAppointment(
            actor = actor,
            idempotencyKey = requireIdempotencyKey(idempotencyKey),
            createOnly = requireCreateOnly(ifNoneMatch),
            request = request,
        ).acceptedResponse()
    }

    @Operation(
        summary = "Accept a current change proposal",
        description = "If-Match must contain the ETag returned by the latest commitment read.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Proposal accepted"),
        ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "Missing or invalid Gateway identity", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "Patient or clinic scope rejected", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Commitment or proposal not found", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "Proposal or resource conflict", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "410", description = "Proposal or consent expired", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "412", description = "ETag does not match current version", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "428", description = "Mutation precondition missing", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "500", description = "Internal scheduling error", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @PostMapping("/appointments/{id}/proposals/{proposalId}/accept")
    fun acceptProposal(
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
        @PathVariable id: Long,
        @PathVariable proposalId: Long,
        @Parameter(required = true, example = "accept_01J1M6Y6XRK8N0W2M3P4Q5R6S7")
        @RequestHeader("Idempotency-Key", required = false)
        idempotencyKey: String?,
        @Parameter(required = true, example = "\"3\"")
        @RequestHeader(HttpHeaders.IF_MATCH, required = false)
        ifMatch: String?,
        @Valid @RequestBody request: ProposalDecisionRequest,
    ): ResponseEntity<AppointmentCommitmentResponse> {
        val actor = actorContextResolver.resolveAppointmentActor(authentication, servletRequest)
            .requirePatientActor()
        return service.decideProposal(
            actor,
            id,
            proposalId,
            requireExpectedVersion(ifMatch),
            requireIdempotencyKey(idempotencyKey),
            request,
        ).okResponse()
    }

    @Operation(
        summary = "Decline a current change proposal",
        description = "Declining preserves the currently confirmed appointment and consumes the current ETag.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Proposal declined"),
        ApiResponse(responseCode = "400", description = "Invalid request", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "Missing or invalid Gateway identity", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "Patient or clinic scope rejected", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "Commitment or proposal not found", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "Proposal state conflict", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "410", description = "Proposal expired", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "412", description = "ETag does not match current version", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "428", description = "Mutation precondition missing", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
        ApiResponse(responseCode = "500", description = "Internal scheduling error", content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))]),
    )
    @PostMapping("/appointments/{id}/proposals/{proposalId}/decline")
    fun declineProposal(
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
        @PathVariable id: Long,
        @PathVariable proposalId: Long,
        @Parameter(required = true, example = "decline_01J1M6Y6XRK8N0W2M3P4Q5R6S7")
        @RequestHeader("Idempotency-Key", required = false)
        idempotencyKey: String?,
        @Parameter(required = true, example = "\"3\"")
        @RequestHeader(HttpHeaders.IF_MATCH, required = false)
        ifMatch: String?,
        @Valid @RequestBody request: DeclineProposalRequest,
    ): ResponseEntity<AppointmentCommitmentResponse> {
        val actor = actorContextResolver.resolveAppointmentActor(authentication, servletRequest)
            .requirePatientActor()
        return service.declineProposal(
            actor,
            id,
            proposalId,
            requireExpectedVersion(ifMatch),
            requireIdempotencyKey(idempotencyKey),
            request,
        ).okResponse()
    }
}
