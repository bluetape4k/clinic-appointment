package io.bluetape4k.clinic.appointment.event.waitlist

import java.io.Serializable
import java.time.Instant

/**
 * 예약 transaction이 만든 durable vacancy job을 다시 처리하도록 깨우는 opaque fast signal입니다.
 *
 * 회원, 예약, 연락처, 정책 상세는 포함하지 않습니다. event가 유실되어도 vacancy job이
 * recovery authority로 남으며, listener는 이 식별자들로 durable state를 다시 조회해야 합니다.
 */
data class SlotAvailable(
    val vacancyJobId: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val correlationId: String,
    val occurredAt: Instant,
) : Serializable {

    init {
        require(vacancyJobId > 0L) { "vacancyJobId must be positive" }
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
        require(correlationId.length <= 128) { "correlationId must not exceed 128 characters" }
        require(correlationId.none(Char::isISOControl)) { "correlationId must not contain control characters" }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
