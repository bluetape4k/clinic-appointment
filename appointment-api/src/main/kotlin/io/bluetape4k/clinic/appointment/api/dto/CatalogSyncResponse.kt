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
 * Concrete OpenAPI schema for the successful catalog synchronization envelope.
 *
 * Runtime responses continue to use [ApiResponse]; this non-generic type keeps
 * generated clients aware of the exact `data` contract.
 */
data class CatalogSyncApiResponse(
    val success: Boolean,
    val data: CatalogSyncResponse,
    val error: String? = null,
)
