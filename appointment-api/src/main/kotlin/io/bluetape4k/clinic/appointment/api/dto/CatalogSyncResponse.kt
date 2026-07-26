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
