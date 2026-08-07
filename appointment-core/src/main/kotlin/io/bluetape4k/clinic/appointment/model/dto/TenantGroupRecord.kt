package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.Instant

/**
 * Tenant group record입니다.
 *
 * @property id tenant group ID입니다.
 * @property tenantCode URL 경로에서 사용하는 안정적인 tenant code
 * @property displayName 사람이 읽을 수 있는 tenant 이름
 * @property active 이 tenant를 요청에 사용할 수 있는지 여부
 * @property createdAt 생성 시각
 */
data class TenantGroupRecord(
    val id: Long? = null,
    val tenantCode: String,
    val displayName: String,
    val active: Boolean = true,
    val createdAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
