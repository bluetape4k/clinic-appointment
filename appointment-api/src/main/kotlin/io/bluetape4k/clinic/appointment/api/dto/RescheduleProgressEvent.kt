package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.logging.KLogging
import java.io.Serializable

/**
 * 일괄 재배정 stream에서 처리한 예약마다 내보내는 SSE payload입니다.
 *
 * ## 동작 / 계약
 * - 예약마다 후보 탐색을 완료할 때 event 하나를 내보냅니다.
 * - [done] = true이고 [appointmentId] = -1인 마지막 event가 stream 완료를 알립니다.
 *
 * @param appointmentId 처리한 예약 ID이며, 종결 완료 event에서는 -1입니다.
 * @param candidateCount 이 예약에서 찾은 후보 slot 수입니다.
 * @param totalProcessed 지금까지 처리한 예약의 누적 건수입니다.
 * @param done stream 완료를 알리는 종결 event에서만 true입니다.
 */
data class RescheduleProgressEvent(
    val appointmentId: Long,
    val candidateCount: Int,
    val totalProcessed: Int,
    val done: Boolean,
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID: Long = 1L

        /** 일괄 처리 완료를 알리는 종결 event입니다. */
        fun completed(totalProcessed: Int): RescheduleProgressEvent =
            RescheduleProgressEvent(
                appointmentId = -1L,
                candidateCount = 0,
                totalProcessed = totalProcessed,
                done = true,
            )
    }
}
