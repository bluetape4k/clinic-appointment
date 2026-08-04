package io.bluetape4k.clinic.appointment.model.service

import java.io.Serializable

/**
 * 검증된 데이터베이스 권위 저장소의 테넌트-병원 범위다.
 *
 * 이 값은 인증 객체가 아니다. HTTP 또는 작업 경계에서 테넌트와 병원의 소속을 확인한 뒤
 * 생성해야 하며, 이후 core·solver·event·notification 경계에 명시적으로 전달한다.
 */
data class TenantClinicScope(
    val tenantGroupId: Long,
    val clinicId: Long,
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
    }

    /** 두 식별자를 충돌 없이 구분하는 캐시 키 표현이다. */
    fun cacheKey(): String = "$tenantGroupId:$clinicId"
}
