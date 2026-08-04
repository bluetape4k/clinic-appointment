package io.bluetape4k.clinic.appointment.model.service

import java.io.Serializable
import java.time.LocalDate

/**
 * 예약 가능 슬롯 조회 요청 데이터.
 *
 * @property scope 검증된 테넌트-병원 권위 범위. 인증 객체가 아니다.
 * @property doctorId 의사 ID
 * @property treatmentTypeId 진료 유형 ID
 * @property date 조회 날짜
 * @property requestedDurationMinutes 요청 진료 시간(분). null이면 진료 유형의 기본값 사용.
 */
data class SlotQuery(
    val scope: TenantClinicScope,
    val doctorId: Long,
    val treatmentTypeId: Long,
    val date: LocalDate,
    val requestedDurationMinutes: Int? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
