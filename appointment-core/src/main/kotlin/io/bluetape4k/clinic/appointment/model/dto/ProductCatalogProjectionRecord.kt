package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import java.io.Serializable
import java.time.Instant

/**
 * 영속화된 불변 catalog aggregate입니다.
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
