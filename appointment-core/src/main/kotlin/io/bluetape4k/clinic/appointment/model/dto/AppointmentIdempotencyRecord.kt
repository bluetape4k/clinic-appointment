package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.Instant

/**
 * 예약 생성 요청의 멱등성 키와 생성 결과를 연결하는 레코드.
 */
data class AppointmentIdempotencyRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val clinicId: Long,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val appointmentId: Long,
    val expiresAt: Instant,
    val createdAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
