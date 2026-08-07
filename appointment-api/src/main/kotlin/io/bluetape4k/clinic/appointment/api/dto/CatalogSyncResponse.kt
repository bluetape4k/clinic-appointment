package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncResult
import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncStatus

data class CatalogSyncResponse(
    val status: CatalogSyncStatus,
    val productId: String,
    val catalogVersion: Long,
) {
    companion object {
        fun from(result: CatalogSyncResult) = CatalogSyncResponse(
            status = result.status,
            productId = result.productId,
            catalogVersion = result.catalogVersion,
        )
    }
}

/**
 * 성공한 catalog 동기화 envelope를 위한 구체적인 OpenAPI 스키마입니다.
 *
 * 런타임 응답은 계속 [ApiResponse]를 사용합니다. 이 non-generic 타입은
 * 생성된 클라이언트가 정확한 `data` 계약을 인식하도록 합니다.
 */
data class CatalogSyncApiResponse(
    val success: Boolean,
    val data: CatalogSyncResponse,
    val error: String? = null,
)
