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
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * 일괄 재배정 진행률을 스트리밍하는 SSE endpoint입니다.
 *
 * ## 동작 / 계약
 * - 예약마다 후보 탐색을 완료할 때 SSE event 하나를 전송합니다.
 * - [RescheduleProgressEvent.done] = true인 종결 event가 완료를 알립니다.
 * - 영향을 받는 예약이 없으면 종결 event만 전송합니다.
 * - 오류가 발생하면 오류 상태로 SSE stream을 완료합니다.
 *
 * @param closureRescheduleService 일괄 재배정 서비스
 */
@Tag(name = "Reschedule", description = "Appointment rescheduling")
@RestController
@RequestMapping("/api/{tenantCode}/reschedule")
class RescheduleBatchStreamController(
    private val closureRescheduleService: ClosureRescheduleService,
    private val tenantClinicAccessChecker: TenantClinicAccessChecker,
    @Value("\${appointment.reschedule.sse-timeout-millis:120000}")
    private val streamTimeoutMillis: Long,
) {
    companion object : KLogging()

    /**
     * 병원 휴진 일괄 재배정 진행률을 Server-Sent Events로 스트리밍합니다.
     *
     * @param clinicId 재배정할 clinic
     * @param closureDate clinic 휴진 날짜
     * @param searchDays 대체 slot을 탐색할 일수(기본값 7)
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
        require(streamTimeoutMillis > 0L) { "appointment.reschedule.sse-timeout-millis must be positive" }
        val tenant = tenantClinicAccessChecker.verifyClinic(tenantCode, clinicId)
        val scope = io.bluetape4k.clinic.appointment.model.service.TenantClinicScope(tenant.id, clinicId)
        log.debug { "GET reschedule batch stream tenantCode=$tenantCode, clinic=$clinicId, date=$closureDate, searchDays=$searchDays" }

        val emitter = SseEmitter(streamTimeoutMillis)
        val workerRef = AtomicReference<Thread?>()
        val interruptWorker: () -> Unit = {
            workerRef.get()?.let { worker -> worker.interrupt() }
        }
        emitter.onCompletion(interruptWorker)
        emitter.onTimeout {
            interruptWorker()
            emitter.complete()
        }
        emitter.onError {
            interruptWorker()
        }

        val worker = Thread.ofVirtual().name("reschedule-stream-$clinicId").unstarted {
            try {
                var totalProcessed = 0

                val count = closureRescheduleService.streamClosureReschedule(
                    scope = scope,
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
            } catch (ex: CancellationException) {
                log.debug { "SSE batch reschedule cancelled - clinic=$clinicId, date=$closureDate" }
                emitter.complete()
                throw ex
            } catch (ex: InterruptedException) {
                log.debug { "SSE batch reschedule interrupted - clinic=$clinicId, date=$closureDate" }
                emitter.complete()
            } catch (ex: Exception) {
                log.warn(ex) { "SSE batch reschedule failed - clinic=$clinicId, date=$closureDate" }
                emitter.completeWithError(ex)
            }
        }
        workerRef.set(worker)
        worker.start()

        return emitter
    }
}
