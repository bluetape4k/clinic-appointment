package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.dto.commitment.AppointmentCommitmentResponse
import io.bluetape4k.clinic.appointment.api.dto.SchedulingApiErrorResponse
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 고객과 관리자에게 commitment 전용 read model을 제공한다.
 *
 * legacy nullable projection을 읽지 않으며 actor의 patient/clinic scope 검증을 application
 * query 경계까지 전달한다.
 */
@Tag(name = "Appointment Commitments - Query")
@RestController
@RequestMapping("/api/{tenantCode}/appointments")
@ConditionalOnProperty(
    prefix = "appointment.commitment",
    name = ["api-enabled"],
    havingValue = "true",
)
class AppointmentCommitmentQueryController(
    private val service: AppointmentCommitmentApplicationService,
    private val actorContextResolver: ActorContextResolver,
) {

    @Operation(summary = "Read the current appointment commitment")
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Commitment found",
            headers = [
                Header(
                    name = "ETag",
                    description = "Strong aggregate version required by the next mutation",
                    schema = Schema(type = "string", example = "\"3\""),
                ),
            ],
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(implementation = AppointmentCommitmentResponse::class),
                ),
            ],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid appointment identifier",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Missing or invalid Gateway identity",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Actor or clinic scope rejected",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Commitment hidden by scope or not found",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "500",
            description = "Internal scheduling error",
            content = [Content(mediaType = "application/json", schema = Schema(implementation = SchedulingApiErrorResponse::class))],
        ),
    )
    @GetMapping("/{id}/commitment")
    fun query(
        @PathVariable tenantCode: String,
        authentication: Authentication?,
        servletRequest: HttpServletRequest,
        @PathVariable id: Long,
    ): ResponseEntity<AppointmentCommitmentResponse> {
        val actor = actorContextResolver.resolveAppointmentActor(authentication, tenantCode, servletRequest)
        when (actor.actorType) {
            ActorType.PATIENT -> actor.requirePatientActor()
            ActorType.ADMIN,
            ActorType.STAFF,
            -> actor.requireCommitmentReadActor()
            else -> throw AppointmentCommitmentApiException(AppointmentCommitmentApiError.SCOPE_FORBIDDEN)
        }
        return service.query(actor, id).okResponse()
    }
}
