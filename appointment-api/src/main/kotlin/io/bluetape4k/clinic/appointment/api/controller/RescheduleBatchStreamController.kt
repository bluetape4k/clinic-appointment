package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.dto.RescheduleProgressEvent
import io.bluetape4k.clinic.appointment.api.tenant.TenantClinicAccessChecker
import io.bluetape4k.clinic.appointment.service.ClosureRescheduleService
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalDate

/**
 * SSE endpoint for streaming batch reschedule progress.
 *
 * ## Behavior / Contract
 * - One SSE event is sent per appointment as it completes candidate search.
 * - A terminal event with [RescheduleProgressEvent.done] = true signals completion.
 * - If no appointments are affected, only the terminal event is sent.
 * - On error, the SSE stream is completed with an error state.
 *
 * @param closureRescheduleService batch reschedule service
 */
@Tag(name = "Reschedule", description = "Appointment rescheduling")
@RestController
@RequestMapping("/api/{tenantCode}/reschedule")
class RescheduleBatchStreamController(
    private val closureRescheduleService: ClosureRescheduleService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
) {
    companion object : KLogging()

    /**
     * Streams batch closure reschedule progress as Server-Sent Events.
     *
     * @param clinicId clinic to reschedule
     * @param closureDate date of the clinic closure
     * @param searchDays number of days to search for alternative slots (default 7)
     * @return SSE stream of [RescheduleProgressEvent] — one per appointment plus a terminal event
     */
    @Operation(summary = "Stream batch closure reschedule progress via SSE")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "SSE stream started"),
        ApiResponse(responseCode = "400", description = "Invalid parameters"),
    )
    @GetMapping("/batch/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamBatchReschedule(
        @PathVariable tenantCode: String,
        @RequestParam clinicId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) closureDate: LocalDate,
        @Parameter(description = "Number of days to search for alternative slots")
        @RequestParam(defaultValue = "7") searchDays: Int,
    ): SseEmitter {
        searchDays.requireInRange(1, 30, "searchDays")
        tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        log.debug { "GET reschedule batch stream tenantCode=$tenantCode, clinic=$clinicId, date=$closureDate, searchDays=$searchDays" }

        val emitter = SseEmitter(0L) // no timeout — stream length is proportional to affected count
        Thread.ofVirtual().start {
            runCatching {
                var totalProcessed = 0

                val count = closureRescheduleService.streamClosureReschedule(
                    clinicId = clinicId,
                    closureDate = closureDate,
                    searchDays = searchDays,
                ) { appointmentId, candidateCount ->
                    totalProcessed++
                    val event = RescheduleProgressEvent(
                        appointmentId = appointmentId,
                        candidateCount = candidateCount,
                        totalProcessed = totalProcessed,
                        done = false,
                    )
                    emitter.send(
                        SseEmitter.event()
                            .name("progress")
                            .data(event, MediaType.APPLICATION_JSON)
                    )
                }

                val terminal = RescheduleProgressEvent.completed(count)
                emitter.send(
                    SseEmitter.event()
                        .name("complete")
                        .data(terminal, MediaType.APPLICATION_JSON)
                )
                emitter.complete()
            }.onFailure { ex ->
                log.warn(ex) { "SSE batch reschedule failed - clinic=$clinicId, date=$closureDate" }
                emitter.completeWithError(ex)
            }
        }

        return emitter
    }
}
