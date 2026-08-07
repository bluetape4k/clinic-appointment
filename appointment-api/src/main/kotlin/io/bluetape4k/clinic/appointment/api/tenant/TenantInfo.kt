package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.clinic.appointment.model.dto.TenantGroupRecord
import io.bluetape4k.support.requireNotNull
import java.io.Serializable

/**
 * 현재 API 요청에 대해 해석한 tenant 식별자입니다.
 *
 * @property id DB row에서 사용하는 tenant group ID
 * @property tenantCode 요청 경로에서 가져온 안정적인 tenant code
 * @property displayName 사람이 읽을 수 있는 tenant 이름
 */
data class TenantInfo(
    val id: Long,
    val tenantCode: String,
    val displayName: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        fun from(record: TenantGroupRecord): TenantInfo =
            TenantInfo(
                id = record.id.requireNotNull("record.id"),
                tenantCode = record.tenantCode,
                displayName = record.displayName,
            )
    }
}
