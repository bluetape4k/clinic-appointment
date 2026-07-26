package io.bluetape4k.clinic.appointment.model.catalog

import java.io.Serializable

/**
 * Stable result category of an immutable catalog-version synchronization.
 */
enum class CatalogSyncStatus {
    CREATED,
    UNCHANGED,
    STALE_IGNORED,
    VERSION_CONFLICT,
}

/**
 * Internal synchronization outcome shared by HTTP and future event adapters.
 */
data class CatalogSyncResult(
    val status: CatalogSyncStatus,
    val productId: String,
    val catalogVersion: Long,
    val payloadHash: String,
    val existingPayloadHash: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
