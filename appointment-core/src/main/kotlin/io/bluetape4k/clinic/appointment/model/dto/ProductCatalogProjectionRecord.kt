package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import java.io.Serializable
import java.time.Instant

/**
 * Persisted immutable catalog aggregate.
 */
data class ProductCatalogProjectionRecord(
    val id: Long? = null,
    val definition: ProductCatalogDefinition,
    val payloadHash: String,
    val createdAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
