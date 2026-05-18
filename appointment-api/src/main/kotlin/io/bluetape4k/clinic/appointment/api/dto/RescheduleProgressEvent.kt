package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.logging.KLogging
import java.io.Serializable

/**
 * SSE payload emitted for each appointment processed during a batch reschedule stream.
 *
 * ## Behavior / Contract
 * - One event is emitted per appointment as it completes candidate search.
 * - A final event with [done] = true and [appointmentId] = -1 signals stream completion.
 *
 * @param appointmentId ID of the processed appointment; -1 for the terminal completion event
 * @param candidateCount number of candidate slots found for this appointment
 * @param totalProcessed running count of appointments processed so far
 * @param done true only on the terminal event that signals stream completion
 */
data class RescheduleProgressEvent(
    val appointmentId: Long,
    val candidateCount: Int,
    val totalProcessed: Int,
    val done: Boolean,
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID: Long = 1L

        /** Terminal event signalling batch completion. */
        fun completed(totalProcessed: Int): RescheduleProgressEvent =
            RescheduleProgressEvent(
                appointmentId = -1L,
                candidateCount = 0,
                totalProcessed = totalProcessed,
                done = true,
            )
    }
}
