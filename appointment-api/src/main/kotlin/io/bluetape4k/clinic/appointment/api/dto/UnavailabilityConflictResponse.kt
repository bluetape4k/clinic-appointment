package io.bluetape4k.clinic.appointment.api.dto

import java.io.Serializable

/**
 * 장비 사용불가 스케줄 등록/수정 시 충돌 예약 목록 응답.
 *
 * @property unavailabilityId 장비 사용불가 스케줄 ID
 * @property conflictCount 충돌 예약 수
 * @property conflicts 충돌 예약 목록
 */
data class UnavailabilityConflictResponse(
    val unavailabilityId: Long,
    val conflictCount: Int,
    val conflicts: List<ConflictingAppointmentResponse>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
